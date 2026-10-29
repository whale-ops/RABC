/**
 * 封装登录、申请、会话恢复、退出、资料更新和改密接口，并同步当前用户与菜单。
 * 关联模块：调用 request.ts，更新 session.ts 及 store.ts 的展示资料；Login.vue、Profile.vue 和路由守卫使用。
 */
import {
  request,
  setAccessToken,
  registerRefreshHandler,
  useMock,
  hasAccessToken,
  ApiError,
} from "./request";
import { session, clearSession, type Me, type Menu } from "./session";
import { ui } from "../data/store";
import { systemConfigApi, applyBranding } from "./system-config";
export interface LoginForm {
  tenantCode: string;
  username: string;
  password: string;
  captchaId?: string;
  captchaCode?: string;
}
export interface LoginResult {
  accessToken: string;
  expiresIn: number;
  tokenType: string;
  mustChangePassword?: boolean;
}
async function csrf() {
  return (
    await request<{ token: string }>({ url: "/auth/csrf", method: "GET" })
  ).token;
}
/** 读取真实身份、展示资料和菜单；不把数据库实体直接交给页面。 */
async function loadMe() {
  if (useMock) return;
  session.user = await request<Me>({ url: "/auth/me" });
  Object.assign(ui.profile, {
    name: session.user.name,
    email: session.user.email,
    phone: session.user.phone,
    department: session.user.department,
    bio: session.user.bio || "",
  });
  session.menus = await request<Menu[]>({ url: "/auth/menus" });
  try {
    applyBranding(await systemConfigApi.public(session.user.tenant.code));
  } catch {
    /* 品牌配置暂不可用不撤销有效会话。 */
  }
  session.ready = true;
}
let refreshing: Promise<void> | null = null;
/** 合并并发刷新任务，并在支持时使用浏览器锁避免多个页面重复消费刷新凭据。 */
async function refresh() {
  if (refreshing) return refreshing;
  const run = async () => {
    const token = await csrf();
    const result = await request<LoginResult>({
      url: "/auth/refresh",
      method: "POST",
      headers: { "X-CSRF-Token": token },
    });
    setAccessToken(result.accessToken);
  };
  const pending: Promise<void> = (async () => {
    if (navigator.locks)
      await navigator.locks.request("star-auth-refresh", run);
    else await run();
  })();
  refreshing = pending.finally(() => {
    refreshing = null;
  });
  return refreshing;
}
registerRefreshHandler(refresh);
export const authApi = {
  async captcha(tenantCode: string, purpose: "login" | "register") {
    const token = await csrf();
    return request<{
      enabled: boolean;
      captchaId?: string;
      image?: string;
      expiresIn?: number;
    }>({
      url: "/auth/captcha",
      method: "POST",
      data: { tenantCode, purpose },
      headers: { "X-CSRF-Token": token },
    });
  },
  async register(data: {
    tenantCode: string;
    username: string;
    name: string;
    email: string;
    phone: string;
    password: string;
    captchaId?: string;
    captchaCode?: string;
  }) {
    const token = await csrf();
    return request<{ requestId: string; status: string; message: string }>({
      url: "/auth/register",
      method: "POST",
      data,
      headers: { "X-CSRF-Token": token },
    });
  },
  async forgotPassword(data: {
    tenantCode: string;
    username: string;
    email: string;
    phone: string;
  }) {
    const token = await csrf();
    return request<string>({
      url: "/auth/forgot-password",
      method: "POST",
      data,
      headers: { "X-CSRF-Token": token },
    });
  },
  async login(form: LoginForm) {
    const token = await csrf();
    const result = await request<LoginResult>({
      url: "/auth/login",
      method: "POST",
      data: form,
      headers: { "X-CSRF-Token": token },
    });
    setAccessToken(result.accessToken);
    await loadMe();
    return result;
  },
  async restore() {
    if (useMock) return !sessionStorage.getItem("star-signed-out");
    if (sessionStorage.getItem("star-signed-out")) return false;
    try {
      if (!hasAccessToken()) await refresh();
      try {
        await loadMe();
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 401) throw e;
        await refresh();
        await loadMe();
      }
      return true;
    } catch {
      setAccessToken("");
      clearSession();
      return false;
    }
  },
  loadMe,
  async logout() {
    try {
      const token = await csrf();
      await request<void>({
        url: "/auth/logout",
        method: "POST",
        headers: { "X-CSRF-Token": token },
      });
    } finally {
      setAccessToken("");
      clearSession();
      sessionStorage.setItem("star-signed-out", "true");
    }
  },
  async profile(data: {
    name: string;
    email: string;
    phone: string;
    bio: string;
  }) {
    await request<void>({ url: "/auth/profile", method: "PATCH", data });
    await loadMe();
  },
  async password(oldPassword: string, newPassword: string) {
    await request<void>({
      url: "/auth/password",
      method: "PATCH",
      data: { oldPassword, newPassword },
    });
    setAccessToken("");
    clearSession();
    sessionStorage.setItem("star-signed-out", "true");
  },
};
