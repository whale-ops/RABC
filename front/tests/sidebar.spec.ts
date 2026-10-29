/**
 * 验证侧边栏的分组、授权、待开放入口、折叠记忆和手机键盘操作。
 * 关联模块：覆盖 Shell.vue、SidebarItem.vue 和 sidebar-navigation.ts，使用演示服务隔离真实数据。
 */
import { test, expect } from "@playwright/test";

test("removed application and data modules stay absent from menus, routes and restored demo records", async ({
  page,
}) => {
  await page.goto("/dashboard");
  const nav = page.getByRole("navigation", { name: "主导航" });
  await expect(nav).not.toContainText("部门应用");
  await expect(nav).not.toContainText("数据管理");
  const registered = await page.evaluate(async () => {
    const file = "/src/router.ts";
    const { router } = await import(file);
    return router
      .getRoutes()
      .filter(
        (r: any) =>
          r.path === "/applications" ||
          r.path === "/data" ||
          r.path.startsWith("/data/"),
      ).length;
  });
  expect(registered).toBe(0);
  await page.evaluate(() => {
    const saved = JSON.parse(
      localStorage.getItem("star-admin-demo-v1") || "{}",
    );
    saved.menus = [
      ...(saved.menus || []),
      { id: "retired-app", name: "部门应用", path: "/applications" },
      { id: "retired-data", name: "数据管理", path: "/data" },
      { id: "retired-child", name: "字典管理", path: "/data/dictionaries" },
    ];
    saved.settings = [{ id: "retired-setting" }];
    saved.dictionaries = [{ id: "retired-dict" }];
    saved.roles = [
      {
        id: "kept-role",
        name: "保留角色",
        permissions: ["用户管理", "部门应用", "字典管理", "参数设置"],
      },
    ];
    localStorage.setItem("star-admin-demo-v1", JSON.stringify(saved));
  });
  await page.reload();
  const restored = await page.evaluate(async () => {
    const file = "/src/data/store.ts";
    const { db } = await import(file);
    return {
      keys: Object.keys(db),
      menus: db.menus.map((r: any) => r.path),
      permissions: db.roles.find((r: any) => r.id === "kept-role").permissions,
    };
  });
  expect(restored.keys).not.toContain("dictionaries");
  expect(restored.keys).not.toContain("settings");
  expect(
    restored.menus.some(
      (p: string) =>
        p === "/applications" || p === "/data" || p?.startsWith("/data/"),
    ),
  ).toBe(false);
  expect(restored.permissions).toEqual(["用户管理"]);
  for (const url of ["/applications", "/data/dictionaries", "/data/settings"]) {
    await page.goto(url);
    await expect(
      page.getByRole("heading", { name: "页面暂时迷路了", exact: true }),
    ).toBeVisible();
  }
});

test("sidebar preserves groups, current page, collapse preference and keyboard navigation", async ({
  page,
}) => {
  await page.goto("/system/users");
  const nav = page.getByRole("navigation", { name: "主导航" });
  const system = nav.getByRole("button", { name: "系统管理", exact: true });
  await expect(system).toHaveAttribute("aria-expanded", "true");
  await expect(
    nav.getByRole("link", { name: "用户管理", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await system.click();
  await expect(
    nav.getByRole("link", { name: "用户管理", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "折叠导航", exact: true }).click();
  await expect(page.locator(".app-shell")).toHaveClass(/collapsed/);
  await expect(
    nav.getByRole("link", { name: "用户管理", exact: true }),
  ).toHaveCount(0);
  await page.reload();
  await expect(page.locator(".app-shell")).toHaveClass(/collapsed/);
  await system.click();
  await expect(page.locator(".app-shell")).not.toHaveClass(/collapsed/);
  const users = nav.getByRole("link", { name: "用户管理", exact: true });
  await expect(users).toBeVisible();
  await system.focus();
  await page.keyboard.press("ArrowDown");
  await expect(users).toBeFocused();
  await nav.getByRole("link", { name: "角色管理", exact: true }).click();
  await expect(page).toHaveURL(/\/system\/roles$/);
  await expect(
    nav.getByRole("link", { name: "角色管理", exact: true }),
  ).toHaveAttribute("aria-current", "page");
});

test("mobile sidebar closes with Escape, returns focus and contains scrolling", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/system/users");
  const trigger = page.getByRole("button", { name: "打开导航", exact: true });
  await trigger.click();
  await expect(
    page
      .getByRole("complementary", { name: "侧边导航" })
      .getByRole("button", { name: "关闭导航", exact: true }),
  ).toBeFocused();
  await expect(page.locator(".main-shell")).toHaveAttribute("inert", "");
  await page.keyboard.press("Escape");
  await expect(page.locator(".app-shell")).not.toHaveClass(/mobile-open/);
  await expect(trigger).toBeFocused();
  await trigger.click();
  await page
    .getByRole("navigation", { name: "主导航" })
    .getByRole("link", { name: "角色管理", exact: true })
    .click();
  await expect(page).toHaveURL(/\/system\/roles$/);
  await expect(page.locator(".app-shell")).not.toHaveClass(/mobile-open/);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("live sidebar keeps authorized nesting without removed placeholder modules", async ({
  page,
}) => {
  await page.goto("/dashboard");
  const result = await page.evaluate(async () => {
    const path = "/src/utils/sidebar-navigation.ts";
    const { sidebarNavigation, flattenNavigation } = await import(path);
    const menus = [
      {
        id: "root",
        name: "系统管理",
        path: "",
        icon: "settings",
        permission: "",
        children: [
          {
            id: "nested",
            name: "人员配置",
            path: "",
            icon: "users",
            permission: "",
            children: [
              {
                id: "users",
                name: "用户管理",
                path: "/system/users",
                icon: "user",
                permission: "system:user:list",
                children: [],
              },
            ],
          },
        ],
      },
      {
        id: "files",
        name: "文件管理",
        path: "/files",
        icon: "folder",
        permission: "",
        children: [
          {
            id: "images",
            name: "图片管理",
            path: "/images",
            icon: "image",
            permission: "storage:file:list",
            children: [],
          },
          {
            id: "attachments",
            name: "附件管理",
            path: "/attachments",
            icon: "paperclip",
            permission: "storage:file:list",
            children: [],
          },
        ],
      },
    ];
    const tree = sidebarNavigation(menus, false);
    const all = flattenNavigation(tree);
    return {
      roots: tree.map((entry: any) => entry.label),
      activePaths: all
        .filter((entry: any) => entry.path && !entry.pending)
        .map((entry: any) => entry.path),
      nested: tree.find((entry: any) => entry.label === "系统管理")?.children[0]
        .children[0].path,
      attachmentsReady: !all.filter(
        (entry: any) => entry.label === "附件管理",
      )[0].pending,
      fileChildren: tree
        .find((entry: any) => entry.label === "文件管理")
        ?.children.map((entry: any) => entry.label),
      filesWithoutGrant: flattenNavigation(
        sidebarNavigation(menus.slice(0, 1), false),
      ).some((entry: any) => ["/images", "/attachments"].includes(entry.path)),
      denied: all.some((entry: any) => entry.path === "/system/roles"),
    };
  });
  expect(result.roots).toEqual(["系统管理", "文件管理"]);
  expect(result.activePaths).toEqual([
    "/system/users",
    "/images",
    "/attachments",
  ]);
  expect(result.nested).toBe("/system/users");
  expect(result.attachmentsReady).toBe(true);
  expect(result.fileChildren).toEqual(["图片管理", "附件管理"]);
  expect(result.filesWithoutGrant).toBe(false);
  expect(result.denied).toBe(false);
});

test("file group expands, collapses and highlights the active child", async ({
  page,
}) => {
  await page.goto("/images");
  const nav = page.getByRole("navigation", { name: "主导航" });
  const group = nav.getByRole("button", { name: "文件管理", exact: true });
  await expect(
    nav.getByRole("button", { name: "系统管理", exact: true }),
  ).toHaveAttribute("aria-expanded", "false");
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(
    nav.getByRole("link", { name: "图片管理", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await expect(page.locator(".breadcrumb")).toContainText("文件管理");
  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(
    nav.getByRole("link", { name: "图片管理", exact: true }),
  ).toHaveCount(0);
  await group.click();
  await nav.getByRole("link", { name: "附件管理", exact: true }).click();
  await expect(page).toHaveURL(/\/attachments$/);
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(
    nav.getByRole("link", { name: "附件管理", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page.reload();
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(
    nav.getByRole("link", { name: "附件管理", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await expect(
    nav.getByRole("button", { name: "系统管理", exact: true }),
  ).toHaveAttribute("aria-expanded", "false");
  // 手动打开其他分组后切换页面，不保留无关模块的展开状态。
  await nav.getByRole("button", { name: "系统管理", exact: true }).click();
  await nav.getByRole("link", { name: "用户管理", exact: true }).click();
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(
    nav.getByRole("button", { name: "系统管理", exact: true }),
  ).toHaveAttribute("aria-expanded", "true");
  await nav.getByRole("link", { name: "首页", exact: true }).click();
  await expect(nav.locator('button[aria-expanded="true"]')).toHaveCount(0);
});
