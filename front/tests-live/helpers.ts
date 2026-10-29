/**
 * 封装真实测试的登录会话、接口响应断言与 XLSX 内容解析。
 * 关联模块：api.spec.ts 和 browser.spec.ts 复用；依赖真实认证接口和专用测试账号。
 */
import {
  request as playwrightRequest,
  expect,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { inflateRawSync } from "node:zlib";

/** 通过 ZIP 中央目录读取测试用小型 XLSX 的 XML，验证导出内容而非仅检查扩展名。 */
export function xlsxXml(buffer: Buffer): string {
  let end = buffer.length - 22;
  while (end >= Math.max(0, buffer.length - 65557) && buffer.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0) throw new Error("Invalid XLSX ZIP directory");
  let offset = buffer.readUInt32LE(end + 16);
  const xml: string[] = [];
  for (let i = 0; i < buffer.readUInt16LE(end + 10); i++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) throw new Error("Invalid ZIP entry");
    const method = buffer.readUInt16LE(offset + 10), size = buffer.readUInt32LE(offset + 20);
    const length = buffer.readUInt16LE(offset + 28), extra = buffer.readUInt16LE(offset + 30), comment = buffer.readUInt16LE(offset + 32);
    const name = buffer.subarray(offset + 46, offset + 46 + length).toString();
    const local = buffer.readUInt32LE(offset + 42);
    if (/^xl\/(sharedStrings|worksheets\/sheet\d+)\.xml$/.test(name)) {
      const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
      const bytes = buffer.subarray(start, start + size);
      xml.push((method === 8 ? inflateRawSync(bytes) : bytes).toString("utf8"));
    }
    offset += 46 + length + extra + comment;
  }
  return xml.join("\n");
}
export const password = process.env.TEST_ADMIN_PASSWORD || "StarAdmin@2026";
export async function client(
  username = "admin",
  tenantCode = "star",
  pass = password,
) {
  const api = await playwrightRequest.newContext({
    baseURL: "http://127.0.0.1:8080/api/v1/",
    extraHTTPHeaders: { Origin: "http://127.0.0.1:5174" },
  });
  const csrf = (await (await api.get("auth/csrf")).json()).data.token;
  const response = await api.post("auth/login", {
    data: { tenantCode, username, password: pass },
    headers: { "X-CSRF-Token": csrf },
  });
  expect(response.status(), await response.text()).toBe(200);
  const token = (await response.json()).data.accessToken;
  return {
    api,
    token,
    headers: { Authorization: "Bearer " + token },
    async close() {
      const csrf = (await (await api.get("auth/csrf")).json()).data.token;
      await api.post("auth/logout", {
        headers: { Authorization: "Bearer " + token, "X-CSRF-Token": csrf },
      });
      await api.dispose();
    },
  };
}
export async function data(
  response: Awaited<ReturnType<APIRequestContext["get"]>>,
  status = 200,
) {
  expect(response.status(), await response.text()).toBe(status);
  return (await response.json()).data;
}
export async function login(
  page: Page,
  username = "admin",
  pass = password,
  tenant = "star",
) {
  await page.goto("/login");
  await page.getByLabel("租户编码").fill(tenant);
  await page.getByLabel("用户账号", { exact: true }).fill(username);
  await page.getByLabel("登录密码", { exact: true }).fill(pass);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).not.toHaveURL(/\/login/);
}
export const tag = () =>
  Date.now().toString() + Math.floor(Math.random() * 1000);
