/**
 * 封装真实系统管理接口的分页、详情、版本更新、授权、删除与导出参数。
 * 关联模块：SystemManagement.vue 调用；模块键映射到 SystemController，所有写操作使用后台版本校验。
 */
 import { request } from "./request";
import type { Row } from "../data/store";
export const liveResources: Record<string, string> = {
  users: "users",
  roles: "roles",
  menus: "menus",
  departments: "depts",
  posts: "posts",
};
const path = (key: string) => "/system/" + liveResources[key];
export interface ListQuery {
  page: number;
  size: number;
  keyword?: string;
  status?: string;
  deptId?: string;
  sort?: string;
  direction?: string;
}
export interface Options {
  departments: Row[];
  roles: Row[];
  posts: Row[];
  menus: Row[];
}
export const liveApi = {
  list: (key: string, params: ListQuery) =>
    request<{ records: Row[]; total: number; page: number; size: number }>({
      url: path(key),
      params,
    }),
  options: () => request<Options>({ url: "/system/options" }),
  detail: (key: string, id: string) =>
    request<Row>({ url: path(key) + "/" + id }),
  save: (key: string, id: string | undefined, data: Record<string, unknown>) =>
    request<Row>({
      url: path(key) + (id ? "/" + id : ""),
      method: id ? "PATCH" : "POST",
      data,
    }),
  remove: (key: string, items: { id: string; version: number }[]) =>
    request<void>({
      url: path(key) + "/batch-delete",
      method: "POST",
      data: { items },
    }),
  status: (key: string, row: Row, status: string) =>
    request<Row>({
      url: path(key) + "/" + row.id + "/status",
      method: "PATCH",
      data: { status, version: row.version },
    }),
  grant: (row: Row, menuIds: string[], scope: string, deptIds: string[]) =>
    request<Row>({
      url: path("roles") + "/" + row.id + "/permissions",
      method: "PUT",
      data: { menuIds, scope, deptIds, version: row.version },
    }),
  reset: (row: Row, newPassword: string) =>
    request<void>({
      url: path("users") + "/" + row.id + "/password-reset",
      method: "POST",
      data: { newPassword, version: row.version },
    }),
  export: (key: string, params: ListQuery) =>
    request<Blob>({ url: path(key) + "/export", params, responseType: "blob" }),
};
