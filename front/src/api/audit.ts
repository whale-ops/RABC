/**
 * 定义登录与操作日志的数据结构，并封装日志列表、详情和 XLSX 导出接口。
 * 关联模块：AuditLogs.vue 调用 request.ts，后台由 AuditLogController 与 AuditLogService 提供只读数据。
 */
import { request } from "./request";

export interface AuditRecord {
  id: string; userId: string | null; tenantCode: string; username: string; operator: string;
  module: string; action: string; requestMethod: string; requestPath: string;
  ip: string; browser: string; os: string; status: string; httpStatus: number;
  errorCode: string; durationMs: number; traceId: string; createdAt: string; userAgent?: string;
}
export type AuditKind = "operations" | "logins";
export interface AuditQuery {
  page: number; size: number; keyword: string; status: string; action: string;
  start: string; end: string; direction: string;
}
export const auditApi = {
  list: (kind: AuditKind, params: AuditQuery) => request<{records: AuditRecord[]; total: number}>({url: `/audit/${kind}`, params}),
  detail: (kind: AuditKind, id: string) => request<AuditRecord>({url: `/audit/${kind}/${id}`}),
  export: (kind: AuditKind, params: AuditQuery) => request<Blob>({url: `/audit/${kind}/export`, params, responseType: "blob"}),
};
