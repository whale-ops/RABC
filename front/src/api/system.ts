/**
 * 封装通用原型管理页使用的资源路径和请求方法。
 * 关联模块：Management.vue 与 mock.ts 共用这些路径；真实系统管理的版本化请求由 system-live.ts 单独负责。
 */
import { request } from "./request";
import type { Row } from "../data/store";

export const resourcePaths: Record<string, string> = {
  users: "/system/users",
  roles: "/system/roles",
  menus: "/system/menus",
  departments: "/system/depts",
  posts: "/system/posts",
  projects: "/business/projects",
  operations: "/audit/operations",
  logins: "/audit/logins",
  sessions: "/monitor/sessions",
};

export const systemApi = {
  list: (key: string) =>
    request<{ records: Row[]; total: number }>({
      url: resourcePaths[key],
      method: "GET",
    }),
  save: (key: string, row: Row, create: boolean) =>
    request<Row>({
      url:
        resourcePaths[key] + (create ? "" : `/${encodeURIComponent(row.id)}`),
      method: create ? "POST" : "PATCH",
      data: row,
    }),
  remove: (key: string, ids: string[]) =>
    request<void>({
      url: resourcePaths[key] + "/batch-delete",
      method: "POST",
      data: { ids },
    }),
  status: (key: string, id: string, status: string) =>
    request<Row>({
      url: `${resourcePaths[key]}/${encodeURIComponent(id)}/status`,
      method: "PATCH",
      data: { status },
    }),
  permissions: (
    id: string,
    data: { permissions: string[]; scope: string; customDepts: string[] },
  ) =>
    request<Row>({
      url: `/system/roles/${encodeURIComponent(id)}/permissions`,
      method: "PUT",
      data,
    }),
};
