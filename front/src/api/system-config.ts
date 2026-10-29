/** 系统配置接口及品牌状态；系统配置页保存，登录页与布局读取同一份品牌数据。 */
import { reactive } from "vue";
import { request, useMock } from "./request";
export interface PublicConfig {
  systemName: string;
  systemLogo: string;
  systemDescription: string;
  captchaEnabled: boolean;
  captchaLength: number;
  captchaExpireSeconds: number;
  registrationEnabled: boolean;
  registrationCaptchaEnabled: boolean;
}
export interface SystemConfig extends PublicConfig {
  version: number;
}
export const defaultConfig: PublicConfig = {
  systemName: "StarAdmin",
  systemLogo: "/favicon.svg",
  systemDescription: "企业级快速开发平台",
  captchaEnabled: false,
  captchaLength: 4,
  captchaExpireSeconds: 120,
  registrationEnabled: true,
  registrationCaptchaEnabled: false,
};
export const branding = reactive({ ...defaultConfig });
export function applyBranding(value: PublicConfig) {
  Object.assign(branding, value);
  const title = document.title.split(" · ")[0] || "工作台";
  document.title = `${title} · ${value.systemName}`;
}
export const systemConfigApi = {
  public: (tenantCode: string) =>
    useMock
      ? Promise.resolve({ ...defaultConfig })
      : request<PublicConfig>({
          url: "/auth/public-config",
          params: { tenantCode },
        }),
  get: () => request<SystemConfig>({ url: "/system/config" }),
  save: (tab: "basic" | "login" | "registration", data: object) =>
    request<SystemConfig>({
      url: `/system/config/${tab}`,
      method: "PUT",
      data,
    }),
};

/** 同租户的其他窗口收到更新通知后重新读取公开配置，消息本身不携带配置或凭据。 */
export function notifyConfigChanged(tenantCode: string) {
  window.dispatchEvent(
    new CustomEvent("star:system-config-changed", { detail: { tenantCode } }),
  );
  if (typeof BroadcastChannel !== "undefined") {
    const channel = new BroadcastChannel("star-system-config");
    channel.postMessage({ tenantCode });
    channel.close();
  }
}
export function subscribeConfigChanges(
  callback: (tenantCode?: string) => void,
) {
  const focus = () => callback();
  const visible = () => {
    if (!document.hidden) callback();
  };
  const local = (event: Event) =>
    callback((event as CustomEvent).detail?.tenantCode);
  const channel =
    typeof BroadcastChannel !== "undefined"
      ? new BroadcastChannel("star-system-config")
      : null;
  if (channel)
    channel.onmessage = (event) => {
      if (typeof event.data?.tenantCode === "string")
        callback(event.data.tenantCode);
    };
  window.addEventListener("focus", focus);
  document.addEventListener("visibilitychange", visible);
  window.addEventListener("star:system-config-changed", local);
  return () => {
    window.removeEventListener("focus", focus);
    document.removeEventListener("visibilitychange", visible);
    window.removeEventListener("star:system-config-changed", local);
    channel?.close();
  };
}
