/** 文件管理真实接口；关联 StorageFileController，浏览器不接触 OSS / MinIO 凭据或对象键。 */
import { request } from "./request";
import type { StorageCategory } from "../utils/storage-category";

export interface StorageFile {
  id: string;
  name: string;
  size: number;
  owner: string;
  createdAt: string;
  provider: string;
}
export interface StorageFilePage {
  items: StorageFile[];
  total: number;
  totalSize: number;
}
export interface StorageDocumentPreview {
  kind: "spreadsheet" | "document";
  sheets: {
    name: string;
    rows: string[][];
    merges: {
      row: number;
      column: number;
      rowSpan: number;
      columnSpan: number;
    }[];
  }[];
  blocks: {
    kind: "paragraph" | "table" | "image";
    text: string;
    rows: string[][];
    heading: number;
    alignment: "left" | "center" | "right";
  }[];
  truncated: boolean;
}
export const storageApi = {
  // 只取经过授权的短期地址，不先下载整个视频；签名不写入本地存储。
  playback: (id: string, signal?: AbortSignal) =>
    request<{ url: string; expiresAt: number }>({
      url: `/storage/files/${id}/playback`,
      signal,
    }),
  list: (keyword: string, page: number, category?: StorageCategory) =>
    request<StorageFilePage>({
      url: "/storage/files",
      params: { keyword, page, pageSize: 12, category },
    }),
  upload: (file: File, category?: StorageCategory) => {
    const data = new FormData();
    data.append("file", file);
    if (category) data.append("category", category);
    return request<StorageFile>({
      url: "/storage/files",
      method: "POST",
      data,
      timeout: 120000,
    });
  },
  // 缩略图使用同一鉴权接口；页面切换时可取消未完成的图片请求。
  download: (id: string, signal?: AbortSignal) =>
    request<Blob>({
      url: `/storage/files/${id}/content`,
      responseType: "blob",
      timeout: 120000,
      signal,
    }),
  // 明确选择仅移除记录时使用独立接口，后端完全不访问 OSS / MinIO。
  removeRecord: (id: string) =>
    request<void>({ url: `/storage/files/${id}/record`, method: "DELETE" }),
  remove: (id: string) =>
    request<void>({ url: `/storage/files/${id}`, method: "DELETE" }),
  preview: (id: string, signal?: AbortSignal) =>
    request<StorageDocumentPreview>({
      url: `/storage/files/${id}/preview`,
      signal,
      timeout: 120000,
    }),
};
