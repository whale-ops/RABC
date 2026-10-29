/**
 * 验证真实认证、系统管理、权限与导出等接口约定。
 * 关联模块：使用 helpers.ts 连接 Spring Boot、MySQL 和 Redis；完整用例依赖专用演示数据，不应对现有业务库直接运行。
 */
import { test, expect, request as playwrightRequest } from "@playwright/test";
import { client, data, tag, password, xlsxXml } from "./helpers";
test("authentication requires CSRF, rejects invalid credentials and isolates tenant", async () => {
  const anon = await playwrightRequest.newContext({
    baseURL: "http://127.0.0.1:8080/api/v1/",
  });
  expect((await anon.get("system/users")).status()).toBe(401);
  expect(
    (
      await anon.post("auth/login", {
        data: { tenantCode: "star", username: "admin", password },
      })
    ).status(),
  ).toBe(403);
  const csrf = (await data(await anon.get("auth/csrf"))).token;
  expect(
    (
      await anon.post("auth/login", {
        data: { tenantCode: "star", username: "admin", password: "wrong" },
        headers: { "X-CSRF-Token": csrf },
      })
    ).status(),
  ).toBe(401);
  const a = await client(),
    b = await client("admin", "demo");
  try {
    const own = await data(
      await a.api.get("system/users", { headers: a.headers }),
    );
    expect(own.records.some((r: any) => r.id === "1001")).toBeTruthy();
    expect(
      own.records.every((r: any) => !r.passwordHash && !r.tenantId),
    ).toBeTruthy();
    expect(
      (await a.api.get("system/users/11001", { headers: a.headers })).status(),
    ).toBe(404);
    expect(
      (await b.api.get("system/users/1001", { headers: b.headers })).status(),
    ).toBe(404);
    const malicious = {
      username: "inject_" + tag(),
      name: "跨租户",
      deptId: "10002",
      roleIds: ["2"],
      postIds: [],
      email: "",
      phone: "",
      remark: "",
      status: "启用",
      initialPassword: "ValidPassword2026",
    };
    expect(
      (
        await a.api.post("system/users", {
          headers: a.headers,
          data: malicious,
        })
      ).status(),
    ).toBe(404);
    expect(
      (
        await a.api.post("system/posts", {
          headers: a.headers,
          data: {
            name: "注入",
            code: "test",
            status: "启用",
            sort: 0,
            tenantId: "2",
          },
        })
      ).status(),
    ).toBe(400);
  } finally {
    await a.close();
    await b.close();
    await anon.dispose();
  }
});
test("SELF and department descendants restrict lists, detail and export; no function privilege", async () => {
  const a = await client("reader"),
    b = await client("dept_reader");
  try {
    const own = await data(
      await a.api.get("system/users", { headers: a.headers }),
    );
    expect(own.total).toBe(1);
    expect(own.records[0].username).toBe("reader");
    expect(
      (await a.api.get("system/users/1001", { headers: a.headers })).status(),
    ).toBe(404);
    expect(
      (await a.api.get("system/roles", { headers: a.headers })).status(),
    ).toBe(403);
    expect(
      (
        await a.api.post("system/posts", {
          headers: a.headers,
          data: { name: "禁止", code: "blocked", status: "启用", sort: 0 },
        })
      ).status(),
    ).toBe(403);
    const excel = await a.api.get("system/users/export", { headers: a.headers });
    expect(excel.status()).toBe(200);
    expect(excel.headers()["content-type"]).toContain("spreadsheetml.sheet");
    expect(xlsxXml(await excel.body())).toContain("reader");
    expect(xlsxXml(await excel.body())).not.toContain("admin@");
    const dept = await data(
      await b.api.get("system/users", { headers: b.headers }),
    );
    expect(dept.records.map((r: any) => r.username)).toEqual(
      expect.arrayContaining(["reader", "dept_reader", "developer"]),
    );
    expect(dept.records.some((r: any) => r.username === "admin")).toBeFalsy();
  } finally {
    await a.close();
    await b.close();
  }
});
test("posts CRUD: duplicate, optimistic version, batch atomicity and CSV", async () => {
  const a = await client(),
    id = tag();
  let row: any;
  try {
    const body = {
      name: "岗位_" + id,
      code: "post_" + id,
      status: "启用",
      sort: 10,
      remark: "=SUM(1+1)",
    };
    row = await data(
      await a.api.post("system/posts", { headers: a.headers, data: body }),
      201,
    );
    const overview = await data(await a.api.get("dashboard/overview", { headers: a.headers }));
    const activity = overview.activities.find((item: any) => item.resourceId === row.id);
    expect(activity).toBeTruthy();
    expect(activity.createdAt).toMatch(/Z$/);
    expect(Math.abs(Date.now() - Date.parse(activity.createdAt))).toBeLessThan(60_000);
    expect(
      (
        await a.api.post("system/posts", { headers: a.headers, data: body })
      ).status(),
    ).toBe(409);
    const changed = await data(
      await a.api.patch("system/posts/" + row.id, {
        headers: a.headers,
        data: { ...body, name: body.name + "_改", version: row.version },
      }),
    );
    expect(
      (
        await a.api.patch("system/posts/" + row.id, {
          headers: a.headers,
          data: { ...body, version: row.version },
        })
      ).status(),
    ).toBe(409);
    row = changed;
    expect(
      (
        await a.api.post("system/posts/batch-delete", {
          headers: a.headers,
          data: {
            items: [
              { id: row.id, version: row.version },
              { id: "999999999", version: 0 },
            ],
          },
        })
      ).status(),
    ).toBe(404);
    expect(
      (
        await a.api.get("system/posts/" + row.id, { headers: a.headers })
      ).status(),
    ).toBe(200);
    const excel = await a.api.get("system/posts/export", {
      headers: a.headers,
      params: { keyword: id },
    });
    const xml = xlsxXml(await excel.body());
    expect(xml).toContain("=SUM");
    expect(xml).not.toMatch(/<f[ >]/);
    expect(
      (
        await a.api.get("system/posts", {
          headers: a.headers,
          params: { sort: "name;drop table sys_user" },
        })
      ).status(),
    ).toBe(400);
    expect(
      (
        await a.api.get("system/posts", {
          headers: a.headers,
          params: { size: 101 },
        })
      ).status(),
    ).toBe(400);
  } finally {
    if (row)
      await a.api.delete("system/posts/" + row.id, {
        headers: a.headers,
        params: { version: row.version },
      });
    await a.close();
  }
});
test("hierarchy prevents cycles, referenced deletion and enabled child under disabled parent", async () => {
  const a = await client(),
    suffix = tag();
  let root: any, child: any;
  try {
    root = await data(
      await a.api.post("system/depts", {
        headers: a.headers,
        data: {
          name: "部门_" + suffix,
          parentId: "",
          leader: "测试",
          phone: "",
          sort: 1,
          status: "启用",
          remark: "",
        },
      }),
      201,
    );
    child = await data(
      await a.api.post("system/depts", {
        headers: a.headers,
        data: {
          name: "子部门_" + suffix,
          parentId: root.id,
          leader: "测试",
          phone: "",
          sort: 1,
          status: "启用",
          remark: "",
        },
      }),
      201,
    );
    expect(
      (
        await a.api.patch("system/depts/" + root.id, {
          headers: a.headers,
          data: {
            name: root.name,
            parentId: child.id,
            leader: "测试",
            phone: "",
            sort: 1,
            status: "启用",
            remark: "",
            version: 0,
          },
        })
      ).status(),
    ).toBe(400);
    expect(
      (
        await a.api.delete("system/depts/" + root.id, {
          headers: a.headers,
          params: { version: 0 },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await a.api.patch("system/depts/" + root.id + "/status", {
          headers: a.headers,
          data: { status: "停用", version: 0 },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await a.api.delete("system/depts/2", {
          headers: a.headers,
          params: { version: 0 },
        })
      ).status(),
    ).toBe(409);
  } finally {
    for (const r of [child, root])
      if (r)
        await a.api.delete("system/depts/" + r.id, {
          headers: a.headers,
          params: { version: r.version },
        });
    await a.close();
  }
});
test("built-in admin protection and password policy", async () => {
  const a = await client();
  try {
    expect(
      (
        await a.api.patch("system/users/1001/status", {
          headers: a.headers,
          data: { status: "停用", version: 0 },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await a.api.delete("system/roles/1", {
          headers: a.headers,
          params: { version: 0 },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await a.api.post("system/users", {
          headers: a.headers,
          data: {
            username: "short_" + tag(),
            name: "短密码",
            deptId: "2",
            roleIds: ["2"],
            postIds: [],
            email: "",
            phone: "",
            remark: "",
            status: "启用",
            initialPassword: "short",
          },
        })
      ).status(),
    ).toBe(400);
    expect(
      (
        await a.api.post("system/users", {
          headers: a.headers,
          data: {
            username: "admin_" + tag(),
            name: "越权管理员",
            deptId: "2",
            roleIds: ["1"],
            postIds: [],
            email: "",
            phone: "",
            remark: "",
            status: "启用",
            initialPassword: "ValidPassword2026",
          },
        })
      ).status(),
    ).toBe(403);
  } finally {
    await a.close();
  }
});
test("refresh rotation, replay revocation and logout revoke bearer", async () => {
  const a = await client();
  try {
    const oldCookies = await a.api.storageState();
    let csrf = (await data(await a.api.get("auth/csrf"))).token;
    const fresh = await data(
      await a.api.post("auth/refresh", { headers: { "X-CSRF-Token": csrf } }),
    );
    const replay = await playwrightRequest.newContext({
      baseURL: "http://127.0.0.1:8080/api/v1/",
      storageState: oldCookies,
    });
    csrf = (await data(await replay.get("auth/csrf"))).token;
    expect(
      (
        await replay.post("auth/refresh", { headers: { "X-CSRF-Token": csrf } })
      ).status(),
    ).toBe(401);
    expect(
      (
        await a.api.get("auth/me", {
          headers: { Authorization: "Bearer " + fresh.accessToken },
        })
      ).status(),
    ).toBe(401);
    await replay.dispose();
  } finally {
    await a.close();
  }
  const b = await client();
  const token = b.token;
  await b.close();
  const anonymous = await playwrightRequest.newContext();
  expect(
    (
      await anonymous.get("http://127.0.0.1:8080/api/v1/system/users", {
        headers: { Authorization: "Bearer " + token },
      })
    ).status(),
  ).toBe(401);
  await anonymous.dispose();
});
test("role grants and custom data scope take effect immediately; password change is optional; disabled sessions fail", async () => {
  const a = await client(),
    suffix = tag();
  let role: any, user: any, member: any;
  try {
    role = await data(
      await a.api.post("system/roles", {
        headers: a.headers,
        data: {
          name: "授权_" + suffix,
          code: "role_" + suffix,
          scope: "仅本人",
          status: "启用",
          remark: "",
        },
      }),
      201,
    );
    role = await data(
      await a.api.put("system/roles/" + role.id + "/permissions", {
        headers: a.headers,
        data: {
          menuIds: ["90", "100", "110", "111", "115"],
          scope: "自定义部门",
          deptIds: ["2"],
          version: role.version,
        },
      }),
    );
    user = await data(
      await a.api.post("system/users", {
        headers: a.headers,
        data: {
          username: "member_" + suffix,
          name: "测试成员",
          deptId: "2",
          roleIds: [role.id],
          postIds: ["2"],
          email: "",
          phone: "",
          remark: "",
          status: "启用",
          initialPassword: "InitialPassword2026",
        },
      }),
      201,
    );
    member = await client(user.username, "star", "InitialPassword2026");
    expect(
      (
        await member.api.get("system/users", { headers: member.headers })
      ).status(),
    ).toBe(200);
    expect(
      (
        await member.api.patch("auth/password", {
          headers: member.headers,
          data: {
            oldPassword: "InitialPassword2026",
            newPassword: "ChangedPassword2026",
          },
        })
      ).status(),
    ).toBe(204);
    expect(
      (await member.api.get("auth/me", { headers: member.headers })).status(),
    ).toBe(401);
    await member.close();
    member = await client(user.username, "star", "ChangedPassword2026");
    const limited = await data(
      await member.api.get("system/users", { headers: member.headers }),
    );
    expect(limited.total).toBeGreaterThan(1);
    expect(limited.records.every((r: any) => r.deptId === "2")).toBeTruthy();
    role = await data(
      await a.api.put("system/roles/" + role.id + "/permissions", {
        headers: a.headers,
        data: {
          menuIds: ["90"],
          scope: "仅本人",
          deptIds: [],
          version: role.version,
        },
      }),
    );
    expect(
      (
        await member.api.get("system/users", { headers: member.headers })
      ).status(),
    ).toBe(403);
    user = await data(
      await a.api.get("system/users/" + user.id, { headers: a.headers }),
    );
    user = await data(
      await a.api.patch("system/users/" + user.id + "/status", {
        headers: a.headers,
        data: { status: "停用", version: user.version },
      }),
    );
    expect(
      (await member.api.get("auth/me", { headers: member.headers })).status(),
    ).toBe(401);
  } finally {
    if (member) await member.close();
    if (user) {
      const latest = await data(
        await a.api.get("system/users/" + user.id, { headers: a.headers }),
      );
      await a.api.delete("system/users/" + user.id, {
        headers: a.headers,
        params: { version: latest.version },
      });
    }
    if (role)
      await a.api.delete("system/roles/" + role.id, {
        headers: a.headers,
        params: { version: role.version },
      });
    await a.close();
  }
});
