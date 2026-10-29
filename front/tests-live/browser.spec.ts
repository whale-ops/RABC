/**
 * 验证真实后台模式下的登录、系统管理界面和浏览器操作链路。
 * 关联模块：调用 helpers.ts 并访问真实 API；需要专用演示账号和数据，与原型测试分开运行。
 */
import { test, expect } from "@playwright/test";
import { login, tag, client, data } from "./helpers";
test("real login, five modules, reload session and mobile layout", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page);
  await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
  await page.screenshot({path:'qa/live-home.png',fullPage:true});
  for (const [path, title] of [
    ["users", "用户管理"],
    ["roles", "角色管理"],
    ["menus", "菜单管理"],
    ["departments", "部门管理"],
    ["posts", "岗位管理"],
  ]) {
    await page.goto("/system/" + path);
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(page.locator("tbody tr").first()).toBeVisible();
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.screenshot({path:'qa/live-'+path+'.png',fullPage:true});
  }
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "岗位管理", exact: true }),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/system/users");
  await expect(page.locator("tbody tr").first()).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({path:'qa/live-mobile-users.png',fullPage:true});
  expect(errors).toEqual([]);
});
test("create edit search export delete a post through UI and confirm SQL-backed API", async ({
  page,
}) => {
  const suffix = tag();
  await login(page);
  await page.goto("/system/posts");
  await page.getByRole("button", { name: "新增岗位", exact: true }).click();
  await page
    .locator(".semi-sidesheet")
    .getByLabel("岗位名称", { exact: true })
    .fill("联调岗位" + suffix);
  await page
    .locator(".semi-sidesheet")
    .getByLabel("编码", { exact: true })
    .fill("ui_" + suffix);
  await page.getByRole("button", { name: "确认创建", exact: true }).click();
  await expect(page.locator(".semi-sidesheet")).toHaveCount(0);
  await page.getByPlaceholder("请输入岗位关键字").fill(suffix);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const row = page.locator("tbody tr").filter({ hasText: suffix });
  await expect(row).toHaveCount(1);
  await row.getByRole("button", { name: "编辑", exact: true }).click();
  await page
    .locator(".semi-sidesheet")
    .getByLabel("岗位名称", { exact: true })
    .fill("联调修改" + suffix);
  await page.getByRole("button", { name: "保存修改", exact: true }).click();
  await expect(row).toContainText("联调修改" + suffix);
  await page.reload();
  await page.getByPlaceholder("请输入岗位关键字").fill(suffix);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(row).toContainText("联调修改" + suffix);
  const api = await client();
  try {
    const persisted = await data(
      await api.api.get("system/posts", {
        headers: api.headers,
        params: { keyword: suffix },
      }),
    );
    expect(persisted.total).toBe(1);
    expect(persisted.records[0].name).toBe("联调修改" + suffix);
  } finally {
    await api.close();
  }
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出", exact: true }).click();
  expect((await download).suggestedFilename()).toBe("岗位管理.csv");
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(row).toHaveCount(0);
});
test("user multi-role and post selection, initial password, reset password and deletion", async ({
  page,
}) => {
  await login(page);
  await page.goto("/system/users");
  const suffix = tag();
  await page.getByRole("button", { name: "新增用户", exact: true }).click();
  await page
    .locator(".semi-sidesheet")
    .getByLabel("用户账号", { exact: true })
    .fill("ui_user_" + suffix);
  await page
    .locator(".semi-sidesheet")
    .getByLabel("用户姓名", { exact: true })
    .fill("联调用户" + suffix);
  await page
    .locator(".semi-sidesheet")
    .getByLabel("初始密码", { exact: true })
    .fill("InitialPassword2026");
  await page.locator("#field-deptId").click();
  await page.getByRole("option", { name: "研发部", exact: true }).click();
  await page.locator("#field-roleIds").click();
  await page.getByRole("option", { name: "普通用户", exact: true }).click();
  await page.getByRole("option", { name: "部门查看员", exact: true }).click();
  await page.locator(".drawer-intro").click();
  await page.locator("#field-postIds").click();
  await page.getByRole("option", { name: "开发工程师", exact: true }).click();
  await page.locator(".drawer-intro").click();
  await page.getByRole("button", { name: "确认创建", exact: true }).click();
  await expect(page.locator(".semi-sidesheet")).toHaveCount(0);
  await page.getByPlaceholder("账号 / 姓名 / 手机号").fill(suffix);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const row = page.locator("tbody tr").filter({ hasText: suffix });
  await expect(row).toContainText("普通用户、部门查看员");
  await row.getByRole("button", { name: "重置密码", exact: true }).click();
  await page.getByLabel("新临时密码").fill("ResetPassword2026");
  await page.getByRole("button", { name: "确认重置密码", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "确认重置密码", exact: true }),
  ).toHaveCount(0);
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(row).toHaveCount(0);
});
test("role and department UI saves permissions by menu and department IDs", async ({
  page,
}) => {
  await login(page);
  const suffix = tag();
  await page.goto("/system/departments");
  await page.getByRole("button", { name: "新增部门", exact: true }).click();
  await page
    .locator(".semi-sidesheet")
    .getByLabel("部门名称", { exact: true })
    .fill("联调部门" + suffix);
  await page
    .locator(".semi-sidesheet")
    .getByLabel("负责人", { exact: true })
    .fill("负责人");
  await page.getByRole("button", { name: "确认创建", exact: true }).click();
  await expect(page.locator(".semi-sidesheet")).toHaveCount(0);
  await page.goto("/system/roles");
  await page.getByRole("button", { name: "新增角色", exact: true }).click();
  await page
    .locator(".semi-sidesheet")
    .getByLabel("角色名称", { exact: true })
    .fill("联调角色" + suffix);
  await page
    .locator(".semi-sidesheet")
    .getByLabel("编码", { exact: true })
    .fill("ui_role_" + suffix);
  await page.getByRole("button", { name: "确认创建", exact: true }).click();
  await expect(page.locator(".semi-sidesheet")).toHaveCount(0);
  await page.getByPlaceholder("请输入角色关键字").fill(suffix);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const row = page.locator("tbody tr").filter({ hasText: suffix });
  await row.getByRole("button", { name: "权限", exact: true }).click();
  await page
    .locator(".live-permission-tree")
    .getByLabel("用户管理", { exact: true })
    .check();
  await page
    .locator(".live-permission-tree section")
    .filter({ has: page.locator("strong").filter({ hasText: "用户管理" }) })
    .getByLabel(/查看详情/)
    .check();
  await page.locator(".scope-field .semi-select").click();
  await page.getByRole("option", { name: "自定义部门", exact: true }).click();
  await page
    .locator(".department-checks")
    .getByLabel("联调部门" + suffix, { exact: true })
    .check();
  await page.getByRole("button", { name: "保存权限", exact: true }).click();
  await expect(row).toContainText("自定义部门");
  await row.getByRole("button", { name: "权限", exact: true }).click();
  await expect(
    page
      .locator(".department-checks")
      .getByLabel("联调部门" + suffix, { exact: true }),
  ).toBeChecked();
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(row).toHaveCount(0);
  await page.goto("/system/departments");
  await page.getByPlaceholder("请输入部门关键字").fill(suffix);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const dept = page.locator("tbody tr").filter({ hasText: suffix });
  await dept.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(dept).toHaveCount(0);
});
test("menu UI CRUD and persisted parent relation", async ({ page }) => {
  await login(page);
  await page.goto("/system/menus");
  const suffix = tag();
  await page.getByRole("button", { name: "新增菜单", exact: true }).click();
  await page
    .locator(".semi-sidesheet")
    .getByLabel("菜单名称", { exact: true })
    .fill("联调菜单" + suffix);
  await page.locator("#field-parentId").click();
  await page.getByRole("option", { name: "系统管理", exact: true }).click();
  await expect(page.locator("#field-parentId")).toContainText("系统管理");
  await page
    .locator(".semi-sidesheet")
    .getByLabel("路由地址", { exact: true })
    .fill("/system/qa-" + suffix);
  await page
    .locator(".semi-sidesheet")
    .getByLabel("权限标识", { exact: true })
    .fill("system:post:list");
  await page.getByRole("button", { name: "确认创建", exact: true }).click();
  await expect(page.locator(".semi-sidesheet")).toHaveCount(0);
  await page.getByPlaceholder("请输入菜单关键字").fill(suffix);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const row = page.locator("tbody tr").filter({ hasText: suffix });
  await row.getByRole("button", { name: "详情", exact: true }).click();
  await expect(page.locator(".detail-list")).toContainText("系统管理");
  await page.locator(".semi-modal-close").click();
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(row).toHaveCount(0);
});
test("read-only user sees scoped records and no mutation buttons", async ({
  page,
}) => {
  await login(page, "reader");
  await page.goto("/system/users");
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await expect(page.locator("tbody")).toContainText("reader");
  await expect(
    page.getByRole("button", { name: "新增用户", exact: true }),
  ).toHaveCount(0);
  await page.goto("/system/roles");
  await expect(
    page.getByRole("heading", { name: "当前页面不可访问" }),
  ).toBeVisible();
});
