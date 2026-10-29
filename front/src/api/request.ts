/**
 * 封装 Axios 实例、访问令牌、失败重试及统一错误处理，统一真实与演示请求入口。
 * 关联模块：auth.ts 注册刷新逻辑；真实请求经 Vite 代理到后台，演示模式交给 mock.ts，页面通过 request 调用。
 */
import axios, { type AxiosRequestConfig } from "axios";

export interface ApiEnvelope<T> {
  code: string | number;
  message: string;
  data: T;
  traceId?: string;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status = 0,
    public code = "NETWORK_ERROR",
    public traceId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export const useMock = import.meta.env.VITE_USE_MOCK === "true";
let accessToken = "";
let refreshHandler: (() => Promise<void>) | undefined;
export const hasAccessToken = () => !!accessToken;
export function registerRefreshHandler(handler: () => Promise<void>) {
  refreshHandler = handler;
}
export function setAccessToken(value: string) {
  accessToken = value;
}

/** 所有应用请求共用此实例，包括浏览器内的演示适配器。 */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api/v1",
  timeout: 15000,
  withCredentials: true,
  headers: { Accept: "application/json" },
  ...(useMock
    ? {
        adapter: async (config) => (await import("./mock")).mockAdapter(config),
      }
    : {}),
});

http.interceptors.request.use((config) => {
  if (accessToken) config.headers.set("Authorization", `Bearer ${accessToken}`);
  config.headers.set("X-Request-Id", crypto.randomUUID());
  return config;
});

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (axios.isCancel(error)) return Promise.reject(error);
    const status = error.response?.status || 0;
    const config = error.config;
    if (
      !useMock &&
      status === 401 &&
      config &&
      !String(config.url).startsWith("/auth/") &&
      !config._retry &&
      refreshHandler
    ) {
      config._retry = true;
      let refreshed = false;
      try {
        await refreshHandler();
        refreshed = true;
      } catch {
        /* 刷新失败后继续返回统一的认证错误。 */
      }
      if (refreshed) return http.request(config);
    }
    let responseData = error.response?.data;
    if (responseData instanceof Blob) {
      try {
        responseData = JSON.parse(await responseData.text());
      } catch {}
    }
    const body = responseData as Partial<ApiEnvelope<unknown>> | undefined;
    if (status === 401 && !String(error.config?.url).startsWith("/auth/")) {
      accessToken = "";
      window.dispatchEvent(new CustomEvent("star:unauthorized"));
    }
    return Promise.reject(
      new ApiError(
        body?.message ||
          (error.code === "ECONNABORTED"
            ? "请求超时，请稍后重试"
            : status === 403
              ? "您没有执行此操作的权限"
              : status === 0
                ? "网络连接失败，请检查服务地址"
                : "请求失败，请稍后重试"),
        status,
        String(body?.code || error.code || "REQUEST_FAILED"),
        body?.traceId,
      ),
    );
  },
);

/** 统一发起请求并解包业务响应；下载接口保留 Blob，页面不自行维护第二套 HTTP 客户端。 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiEnvelope<T>>(config);
  if (response.status === 204) return undefined as T;
  if (config.responseType === "blob" || config.responseType === "arraybuffer")
    return response.data as T;
  const body = response.data;
  if (!body || !["OK", 0, "0"].includes(body.code))
    throw new ApiError(
      body?.message || "接口返回格式不正确",
      response.status,
      String(body?.code || "INVALID_RESPONSE"),
      body?.traceId,
    );
  return body.data;
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "操作失败，请重试";
}
