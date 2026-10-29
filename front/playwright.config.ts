/**
 * 配置原型前端自动化测试，使用独立端口和浏览器执行桌面及手机用例。
 * 关联模块：tests 目录的用例在演示模式下运行，避免复用真实联调页面或修改真实后台数据。
 */
import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  timeout: 30_000,
  retries: 0,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:5185",
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
    viewport: { width: 1536, height: 1024 },
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev -- --port 5185 --strictPort",
    url: "http://127.0.0.1:5185",
    env: { VITE_USE_MOCK: "true" },
    reuseExistingServer: false,
  },
  reporter: [["list"], ["html", { open: "never" }]],
});
