/**
 * 验证原型模式的登录、路由、表单、管理操作、文件和手机界面。
 * 关联模块：使用 playwright.config.ts 的演示服务；数据保存在浏览器，不操作真实数据库。
 */
import { test, expect } from "@playwright/test";

test("login exposes registration and administrator-assisted recovery", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "忘记密码？", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "找回您的账号密码" }),
  ).toBeVisible();
  await expect(page.getByLabel("用户账号", { exact: true })).toBeVisible();
  await expect(page.getByLabel("联系邮箱", { exact: true })).toBeVisible();
  await expect(page.getByLabel("登录密码", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "提交找回申请", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("正确的邮箱和手机号");
  await page.getByRole("button", { name: "注册账号", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "申请加入工作空间" }),
  ).toBeVisible();
  await page.getByLabel("用户账号", { exact: true }).fill("new_user");
  await page.getByLabel("姓名", { exact: true }).fill("测试注册");
  await page.getByLabel("联系邮箱", { exact: true }).fill("user@example.test");
  await page.getByLabel("联系手机号", { exact: true }).fill("13800138000");
  await page.getByLabel("设置密码", { exact: true }).fill("short123");
  await page.getByRole("button", { name: "提交注册申请", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("至少 12 位");
  await page.getByRole("button", { name: "返回登录", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "登录您的工作空间" }),
  ).toBeVisible();
});

test("Axios instance runs interceptors and normalizes mock API errors", async ({
  page,
}) => {
  await page.goto("/dashboard");
  const result = await page.evaluate(async () => {
    const modulePath = "/src/api/request.ts";
    const api = await import(modulePath);
    const response = await api.http.get("/system/users");
    let failure: { status: number; code: string } | null = null;
    try {
      await api.request({ url: "/missing-demo-endpoint" });
    } catch (error) {
      const e = error as { status: number; code: string };
      failure = { status: e.status, code: e.code };
    }
    return {
      baseURL: api.http.defaults.baseURL,
      timeout: api.http.defaults.timeout,
      total: response.data.data.total,
      requestId: response.config.headers.get("X-Request-Id"),
      failure,
    };
  });
  expect(result.baseURL).toBe("/api/v1");
  expect(result.timeout).toBe(15000);
  expect(result.total).toBe(1248);
  expect(result.requestId).toMatch(/^[a-f0-9-]{36}$/);
  expect(result.failure).toEqual({ status: 404, code: "RESOURCE_NOT_FOUND" });
  await page.goto("/login");
  await page.getByLabel("登录密码").fill("incorrect");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator(".login-error")).toContainText("演示账号为 admin");
  await expect(page).toHaveURL(/login/);
});

test("all routes render without browser exceptions", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  for (const path of [
    "/dashboard",
    "/system/users",
    "/system/roles",
    "/system/menus",
    "/system/departments",
    "/system/posts",
    "/business/projects",
    "/business/orders",
    "/business/products",
    "/audit/operations",
    "/audit/logins",
    "/monitor/sessions",
    "/monitor/overview",
    "/monitor/cache",
    "/files",
    "/attachments",
    "/profile",
    "/help",
    "/not-a-page",
    "/login",
  ]) {
    await page.goto(path);
    await expect(page.locator("h1:visible,h2:visible").first()).toBeVisible();
    await page.waitForTimeout(100);
    await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  }
  expect(errors).toEqual([]);
});

test("create, search, edit, persist and delete a user", async ({ page }) => {
  await page.goto("/system/users");
  await page.getByRole("button", { name: "新增用户", exact: true }).click();
  await expect(page.locator(".semi-sidesheet")).toBeVisible();
  await page
    .getByRole("textbox", { name: "用户账号", exact: true })
    .fill("test_member");
  await page
    .getByRole("textbox", { name: "用户姓名", exact: true })
    .fill("测试成员");
  await page
    .getByRole("textbox", { name: "手机号码", exact: true })
    .fill("13812345678");
  await page.getByRole("button", { name: "确认创建", exact: true }).click();
  await expect(page.locator(".management-table")).toContainText("test_member");
  await page.getByPlaceholder("账号 / 姓名 / 手机号").fill("test_member");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(page.locator(".management-table tbody tr")).toHaveCount(1);
  await page
    .locator(".management-table")
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "用户姓名", exact: true })
    .fill("修改后的成员");
  await page.getByRole("button", { name: "保存修改" }).click();
  await expect(page.locator(".management-table")).toContainText("修改后的成员");
  await page.reload();
  await expect(page.locator(".management-table")).toContainText("修改后的成员");
  const row = page
    .locator(".management-table tbody tr")
    .filter({ hasText: "test_member" });
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(page.locator(".management-table")).not.toContainText(
    "test_member",
  );
});

test("required form validation and duplicate username validation", async ({
  page,
}) => {
  await page.goto("/system/users?create=1");
  await page.getByRole("button", { name: "确认创建" }).click();
  await expect(page.locator(".field-error")).toContainText([
    "请输入用户账号",
    "请输入用户姓名",
  ]);
  await page
    .getByRole("textbox", { name: "用户账号", exact: true })
    .fill("admin");
  await page
    .getByRole("textbox", { name: "用户姓名", exact: true })
    .fill("重复管理员");
  await page.getByRole("button", { name: "确认创建" }).click();
  await expect(page.locator(".field-error")).toContainText("该标识已存在");
});

test("role permissions are edited and persisted", async ({ page }) => {
  await page.goto("/system/roles");
  const row = page.locator("tbody tr").filter({ hasText: "运维人员" });
  await row.getByRole("button", { name: "权限", exact: true }).click();
  const checkbox = page.getByRole("checkbox", {
    name: "用户管理",
    exact: true,
  });
  await checkbox.check();
  await page.getByRole("button", { name: "保存权限" }).click();
  await page.reload();
  await page
    .locator("tbody tr")
    .filter({ hasText: "运维人员" })
    .getByRole("button", { name: "权限", exact: true })
    .click();
  await expect(
    page.getByRole("checkbox", { name: "用户管理", exact: true }),
  ).toBeChecked();
});

test("local upload, preview, persisted download and removal", async ({
  page,
}) => {
  await page.goto("/attachments");
  await page.getByRole("button", { name: "上传附件", exact: true }).click();
  await page.locator("input[type=file]").setInputFiles({
    name: "star-test.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("StarAdmin file round trip"),
  });
  await page.getByRole("button", { name: "开始上传" }).click();
  await expect(
    page.getByRole("button", { name: "star-test.txt", exact: true }),
  ).toBeVisible();
  await page.reload();
  await page
    .getByRole("button", { name: "star-test.txt", exact: true })
    .click();
  await expect(page.locator(".text-preview")).toHaveText(
    "StarAdmin file round trip",
  );
  const downloaded = page.waitForEvent("download");
  await page
    .locator(".semi-modal")
    .getByRole("button", { name: "下载文件", exact: true })
    .click();
  const file = await downloaded;
  expect(file.suggestedFilename()).toBe("star-test.txt");
  await page.keyboard.press("Escape");
  const card = page.locator("tbody tr").filter({ hasText: "star-test.txt" });
  await card.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await expect(
    page.getByRole("button", { name: "star-test.txt", exact: true }),
  ).toHaveCount(0);
});

test("table pagination, empty search, reset and export work", async ({
  page,
}) => {
  await page.goto("/system/users");
  await page.locator(".semi-page-item").filter({ hasText: /^2$/ }).click();
  await expect(page.locator("tbody")).toContainText("xujia");
  await page
    .getByPlaceholder("账号 / 姓名 / 手机号")
    .fill("no_such_member_123");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(page.getByText("没有找到相关记录")).toBeVisible();
  await page.getByRole("button", { name: "清空筛选", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(10);
  const downloaded = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出", exact: true }).click();
  expect((await downloaded).suggestedFilename()).toBe("用户管理.csv");
});

test("dashboard actions, notification, profile, login and logout", async ({
  page,
}) => {
  await page.goto("/dashboard");
  await page.getByRole("button", { name: "30天", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "30天", exact: true }),
  ).toHaveClass(/selected/);
  await page.getByRole("button", { name: "新增用户", exact: true }).click();
  await expect(page.locator(".semi-sidesheet")).toBeVisible();
  await page.keyboard.press("Escape");
  await page.goto("/profile");
  await page.locator("input#name").fill("平台管理员");
  await page.getByRole("button", { name: "保存修改" }).click();
  await expect(page.locator(".user-menu-button")).toContainText("平台管理员");
  await page.locator(".user-menu-button").click();
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/login/);
  await page.getByLabel("登录密码").fill("admin123");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL(/dashboard/);
});

test("mobile layout has no page overflow and navigation works", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const path of [
    "/dashboard",
    "/system/users",
    "/files",
    "/monitor/overview",
    "/profile",
    "/login",
  ]) {
    await page.goto(path);
    await expect(page.locator("h1:visible,h2:visible").first()).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth + 1,
      ),
      path,
    ).toBeTruthy();
  }
  await page.goto("/dashboard");
  await page.getByRole("button", { name: "打开导航" }).click();
  await page.getByRole("navigation", { name: "主导航" })
    .getByRole("button", { name: "系统管理", exact: true }).click();
  await page.getByRole("link", { name: "用户管理", exact: true }).click();
  await expect(page).toHaveURL(/system\/users/);
  await expect(page.locator(".app-shell")).not.toHaveClass(/mobile-open/);
  await page.screenshot({ path: "qa/mobile-users.png", fullPage: true });
});
