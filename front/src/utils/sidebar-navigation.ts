/**
 * 保留后台授权菜单的层级，并合并原型中尚未接入的待开放模块。
 * 关联模块：session.ts 提供授权菜单，catalog.ts 提供原型分组；Shell.vue 与 SidebarItem.vue 使用生成的导航。
 */
import type { Menu } from "../api/session";
import { navigation as prototype } from "../data/catalog";

export interface SidebarEntry {
  id: string;
  label: string;
  icon: string;
  path?: string;
  pending?: boolean;
  children?: SidebarEntry[];
}

const connectedPaths = new Set([
  "/dashboard", "/system/users", "/system/roles", "/system/menus",
  "/system/departments", "/system/posts", "/system/config",
  "/audit/operations", "/audit/logins",
  "/images", "/videos", "/attachments",
  "/monitor/overview", "/monitor/sessions", "/monitor/cache",
  "/business/orders", "/business/products",
]);

export function authorizedNavigation(menus: Menu[]): SidebarEntry[] {
  return menus.flatMap(menu => {
    const children = authorizedNavigation(menu.children || []);
    const path = connectedPaths.has(menu.path) ? menu.path : undefined;
    if (!children.length && !path) return [];
    return [{ id: `menu-${menu.id}`, label: menu.name,
      icon: menu.icon || "list", path, ...(children.length ? { children } : {}) }];
  });
}

type PrototypeEntry = { label: string; icon: string; path?: string; children?: PrototypeEntry[] };
function fromPrototype(item: PrototypeEntry, pending: boolean): SidebarEntry {
  return { id: `prototype-${item.path || item.label}`, label: item.label, icon: item.icon,
    path: item.path, pending,
    ...(item.children?.length ? { children: item.children.map(child => fromPrototype(child, pending)) } : {}) };
}

/** 已接入模块完全遵循后台授权；未接入模块保留分组但标记待开放，不能跳转。 */
export function sidebarNavigation(menus: Menu[], mock: boolean): SidebarEntry[] {
  if (mock) return prototype.map(item => fromPrototype(item, false));
  const authorized = authorizedNavigation(menus);
  const result: SidebarEntry[] = [];
  // 已接入模块的访问权限只取自后台；未完成模块仅作为待开放占位。
  for (const item of prototype) {
    if (["首页", "系统管理", "业务管理", "日志管理", "文件管理", "系统监控"].includes(item.label)) {
      const matches = authorized.filter(entry => entry.label === item.label || (item.path && entry.path === item.path));
      result.push(...matches);
      matches.forEach(entry => authorized.splice(authorized.indexOf(entry), 1));
    } else result.push(fromPrototype(item, true));
  }
  result.splice(result[0]?.path === "/dashboard" ? 1 : 0, 0, ...authorized);
  return result;
}

export function flattenNavigation(entries: SidebarEntry[]): SidebarEntry[] {
  return entries.flatMap(entry => [entry, ...flattenNavigation(entry.children || [])]);
}

/** 递归查找当前路由的祖先链，使多级导航能够自动展开并高亮所属分组。 */
export function activeAncestors(entries: SidebarEntry[], path: string): string[] {
  for (const entry of entries) {
    if (entry.path === path) return [entry.id];
    const child = activeAncestors(entry.children || [], path);
    if (child.length) return [entry.id, ...child];
  }
  return [];
}
