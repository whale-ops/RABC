/**
 * 实现仅在浏览器运行的 Axios 演示适配器，模拟认证及原型数据管理。
 * 关联模块：request.ts 在演示模式装配，读写 store.ts；不会把演示密码或记录发送到真实后台。
 */
import {
  AxiosError,
  AxiosHeaders,
  type AxiosAdapter,
  type InternalAxiosRequestConfig,
  type AxiosResponse,
} from "axios";
import { db, saveRow, removeRows, audit, type Row } from "../data/store";
import { resourcePaths } from "./system";

function response(
  config: InternalAxiosRequestConfig,
  data: unknown,
  status = 200,
): AxiosResponse {
  return {
    data:
      status === 204
        ? null
        : {
            code: "OK",
            message: "成功",
            data: JSON.parse(JSON.stringify(data)),
            traceId: crypto.randomUUID(),
          },
    status,
    statusText: status === 201 ? "Created" : "OK",
    config,
    headers: new AxiosHeaders({ "Content-Type": "application/json" }),
  };
}
function fail(
  config: InternalAxiosRequestConfig,
  status: number,
  message: string,
  code: string,
): never {
  const result = response(config, null, status);
  result.data = { code, message, data: null, traceId: crypto.randomUUID() };
  throw new AxiosError(
    message,
    AxiosError.ERR_BAD_RESPONSE,
    config,
    undefined,
    result,
  );
}

/** 仅在浏览器中执行的演示适配器，不向服务器转发凭据或记录。 */
export const mockAdapter: AxiosAdapter = async (config) => {
  const path = (config.url || "").split("?")[0];
  const method = config.method?.toUpperCase() || "GET";
  const body =
    typeof config.data === "string"
      ? JSON.parse(config.data)
      : config.data || {};
  // 异步让出一次执行机会，使演示表单与真实请求具有相同的成功和失败处理过程。
  await Promise.resolve();
  if (config.signal?.aborted)
    throw new AxiosError("请求已取消", AxiosError.ERR_CANCELED, config);
  if (path === "/auth/csrf")
    return response(config, { token: "local-demo-csrf" });
  if (path === "/auth/login" && method === "POST") {
    if (body.username !== "admin" || body.password !== "admin123")
      fail(
        config,
        401,
        "演示账号为 admin，演示密码为 admin123",
        "INVALID_CREDENTIALS",
      );
    if (!String(body.tenantCode || "").trim())
      fail(config, 400, "请输入租户编码", "VALIDATION_ERROR");
    audit("登录", "用户登录系统");
    return response(config, {
      accessToken: `demo-${crypto.randomUUID()}`,
      expiresIn: 900,
      tokenType: "Bearer",
    });
  }
  if (path === "/auth/logout" && method === "POST")
    return response(config, null, 204);
  const resource = Object.entries(resourcePaths).find(
    ([, prefix]) => path === prefix || path.startsWith(prefix + "/"),
  );
  if (!resource) fail(config, 404, "演示接口不存在", "RESOURCE_NOT_FOUND");
  const [key, prefix] = resource;
  const [id, action] = path.slice(prefix.length + 1).split("/");
  if (method === "GET" && !id)
    return response(config, { records: db[key], total: db[key].length });
  if (method === "POST" && id === "batch-delete") {
    const ids = body.ids as string[];
    if (!Array.isArray(ids) || !ids.length)
      fail(config, 400, "请选择需要删除的记录", "VALIDATION_ERROR");
    if (
      (key === "users" && ids.includes("1001")) ||
      (key === "roles" && ids.includes("1"))
    )
      fail(config, 409, "系统内置管理员不能删除", "PROTECTED_RESOURCE");
    if (ids.some((id) => !db[key].some((r) => r.id === id)))
      fail(config, 404, "部分记录已不存在，请刷新后重试", "RESOURCE_NOT_FOUND");
    removeRows(key, ids);
    return response(config, null, 204);
  }
  if (method === "POST" && !id) {
    for (const field of ["username", "code"])
      if (body[field] && db[key].some((r) => r[field] === body[field]))
        fail(config, 409, "该标识已存在", "DUPLICATE_KEY");
    saveRow(key, body as Row, true);
    return response(config, db[key][0], 201);
  }
  const row = db[key].find((r) => r.id === id);
  if (!row) fail(config, 404, "记录不存在，请刷新列表", "RESOURCE_NOT_FOUND");
  if (method === "GET") return response(config, row);
  if (method === "PUT" && action === "permissions") {
    saveRow(
      key,
      {
        ...row,
        permissions: body.permissions,
        scope: body.scope,
        customDepts: body.customDepts,
      },
      false,
    );
  } else if (method === "PATCH") {
    if (action === "status" && key === "users" && row.id === "1001")
      fail(config, 409, "当前管理员账号不能停用", "PROTECTED_RESOURCE");
    for (const field of ["username", "code"])
      if (
        body[field] &&
        db[key].some((r) => r.id !== row.id && r[field] === body[field])
      )
        fail(config, 409, "该标识已存在", "DUPLICATE_KEY");
    saveRow(key, { ...row, ...body, id: row.id }, false);
  } else fail(config, 405, "演示接口不支持此操作", "METHOD_NOT_ALLOWED");
  return response(
    config,
    db[key].find((r) => r.id === id),
  );
};
