/**
 * 检查手机宽度下的页面溢出，输出超出视口的元素以定位布局问题。
 * 关联模块：依赖前端开发服务和 Playwright；与 styles.css 的响应式规则及各页面布局有关。
 */
import { chromium } from "@playwright/test";
const browser = await chromium.launch({ channel: "msedge" });
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
for (const path of [
  "/system/users",
  "/files",
  "/applications",
  "/monitor/overview",
]) {
  await page.goto("http://127.0.0.1:5173" + path);
  await page.waitForTimeout(300);
  console.log(
    path,
    await page.evaluate(() => ({
      width: document.documentElement.scrollWidth,
      items: [...document.querySelectorAll("body *")]
        .filter((e) => {
          const r = e.getBoundingClientRect();
          return r.right > innerWidth + 2 && !e.closest(".table-scroll");
        })
        .slice(0, 16)
        .map((e) => ({
          tag: e.tagName,
          cls: e.className,
          right: Math.round(e.getBoundingClientRect().right),
        })),
    })),
  );
}
await browser.close();
