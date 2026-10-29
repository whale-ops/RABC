/**
 * 保存当前登录用户和授权菜单，提供权限判断与路由路径提取。
 * 关联模块：auth.ts 写入会话；router.ts、Shell.vue 及各管理页面据此控制访问和按钮展示。
 */
import { reactive } from "vue";
import { useMock } from "./request";
export interface Menu {
  id: string;
  name: string;
  path: string;
  icon: string;
  permission: string;
  children: Menu[];
}
export interface Me {
  id: string;
  username: string;
  name: string;
  email: string;
  phone: string;
  department: string;
  bio: string;
  role: string;
  permissions: string[];
  admin: boolean;
  mustChangePassword: boolean;
  tenant: { code: string; name: string };
}
export const session = reactive<{
  ready: boolean;
  user: Me | null;
  menus: Menu[];
}>({ ready: false, user: null, menus: [] });
export const can = (permission: string) =>
  useMock || !!session.user?.permissions.includes(permission);
export function clearSession() {
  session.user = null;
  session.menus = [];
  session.ready = false;
}
export function visiblePaths(menus: Menu[] = session.menus): string[] {
  return menus.flatMap((m) => [m.path, ...visiblePaths(m.children || [])]);
}
