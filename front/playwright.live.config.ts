/**
 * 配置连接真实后台的浏览器与接口测试，先检查后台可用性。
 * 关联模块：tests-live/setup.ts 检查服务，helpers.ts 管理会话；完整套件需要专用演示测试库及对应账号。
 */
import { defineConfig } from "@playwright/test";
export default defineConfig({
  globalSetup: './tests-live/setup.ts',
  testDir: "./tests-live",
  timeout: 45_000,
  retries: 0,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:5174",
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
    viewport: { width: 1536, height: 1024 },
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev -- --port 5174 --strictPort",
    url: "http://127.0.0.1:5174",
    reuseExistingServer: !process.env.CI,
    env: { VITE_USE_MOCK: "false" },
  },
  reporter: [
    ["list"],
    ["html", { outputFolder: "playwright-report-live", open: "never" }],
  ],
});
