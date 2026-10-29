/**
 * 批量打开主要页面、保存桌面截图并收集浏览器异常，辅助界面验收。
 * 关联模块：依赖已启动的前端和 Playwright，截图写入 qa；真实模式需要可用登录会话。
 */
import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
await mkdir("qa", { recursive: true });
const browser = await chromium.launch({
  channel: process.platform === "win32" ? "msedge" : undefined,
  headless: true,
});
const page = await browser.newPage({
  viewport: { width: 1536, height: 1024 },
  deviceScaleFactor: 1,
});
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => {
  if (m.type() === "error") errors.push(m.text());
});
for (const [name, path] of [
  ["dashboard", "/dashboard"],
  ["users", "/system/users"],
  ["roles", "/system/roles"],
  ["files", "/files"],
  ["login", "/login"],
]) {
  await page.goto("http://127.0.0.1:5173" + path);
  await page.waitForTimeout(1100);
  await page.screenshot({ path: `qa/${name}.png`, fullPage: true });
  console.log(name, await page.locator("h1,h2").allTextContents());
}
console.log(JSON.stringify({ errors }));
await browser.close();
