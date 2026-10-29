/**
 * 声明系统页面路由，区分真实后台与原型页面，并在进入页面前检查登录及菜单授权。
 * 关联模块：auth.ts 恢复身份，session.ts 提供可见菜单；Shell.vue 承载页面，系统管理与日志路由使用真实接口组件。
 */
import { createRouter, createWebHistory } from "vue-router";
import Shell from "./layouts/Shell.vue";
import { modules } from "./data/catalog";
import { useMock } from "./api/request";
import { authApi } from "./api/auth";
import { visiblePaths } from "./api/session";
import { liveResources } from "./api/system-live";
import { reportPageView } from "./api/dashboard";
import { session } from "./api/session";
import { branding } from "./api/system-config";
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      component: () => import("./views/Login.vue"),
      meta: { title: "登录" },
    },
    {
      path: "/",
      component: Shell,
      redirect: "/dashboard",
      children: [
        {
          path: "dashboard",
          component: useMock
            ? () => import("./views/Dashboard.vue")
            : () => import("./views/SystemHome.vue"),
          meta: { title: "首页" },
        },
        ...Object.entries(modules)
          .filter(([key]) => key !== "projects")
          .map(([key, value]) => ({
            path: `${["users", "roles", "menus", "departments", "posts"].includes(key) ? "system" : key === "projects" ? "business" : key === "sessions" ? "monitor" : "audit"}/${key}`,
            component:
              !useMock && key === "sessions"
                ? () => import("./views/OnlineUsers.vue")
                : !useMock && ["operations", "logins"].includes(key)
                  ? () => import("./views/AuditLogs.vue")
                  : !useMock && liveResources[key]
                    ? () => import("./views/SystemManagement.vue")
                    : () => import("./views/Management.vue"),
            props: { moduleKey: key },
            meta: { title: value.title },
          })),
        {
          path: "system/config",
          component: () => import("./views/SystemConfig.vue"),
          meta: { title: "系统配置" },
        },
        {
          path: "business/orders",
          component: () => import("./views/BusinessOrders.vue"),
          meta: { title: "订单管理" },
        },
        {
          path: "business/products",
          component: () => import("./views/BusinessProducts.vue"),
          meta: { title: "产品管理" },
        },
        {
          path: "business/projects",
          redirect: "/business/orders",
        },
        {
          path: "files",
          redirect: (to) => ({ path: "/images", query: to.query }),
        },
        {
          path: "images",
          component: useMock
            ? () => import("./views/Files.vue")
            : () => import("./views/StorageFiles.vue"),
          props: useMock ? { images: true } : { category: "images" },
          meta: { title: "图片管理" },
        },
        {
          path: "videos",
          component: () => import("./views/StorageFiles.vue"),
          props: { category: "videos" },
          meta: { title: "视频管理" },
        },
        {
          path: "attachments",
          component: useMock
            ? () => import("./views/Files.vue")
            : () => import("./views/StorageFiles.vue"),
          props: useMock ? { attachments: true } : { category: "attachments" },
          meta: { title: "附件管理" },
        },
        {
          path: "monitor/:view(overview|cache)",
          component: useMock
            ? () => import("./views/Monitor.vue")
            : () => import("./views/ServiceMonitor.vue"),
          meta: { title: "系统监控" },
        },
        {
          path: "profile",
          component: () => import("./views/Profile.vue"),
          meta: { title: "个人中心" },
        },
        {
          path: "help",
          component: () => import("./views/Help.vue"),
          meta: { title: "帮助中心" },
        },
        {
          path: "forbidden",
          component: () => import("./views/Forbidden.vue"),
          meta: { title: "无法访问" },
        },
        {
          path: ":pathMatch(.*)*",
          component: () => import("./views/NotFound.vue"),
          meta: { title: "页面不存在" },
        },
      ],
    },
  ],
});
router.afterEach((to, from, failure) => {
  if (to.path === "/monitor/overview") to.meta.title = "服务监控";
  if (to.path === "/monitor/cache") to.meta.title = "缓存监控";
  document.title = `${to.meta.title || "工作台"} · ${branding.systemName}`;
  // 只统计真实登录后的业务页面；刷新计数，重复导航和自动轮询不产生 PV。
  if (
    !failure &&
    !useMock &&
    session.ready &&
    session.user &&
    to.fullPath !== from.fullPath &&
    !["/login", "/forbidden", "/404"].includes(to.path)
  )
    reportPageView();
});
router.beforeEach(async (to) => {
  if (!useMock) {
    if (to.path === "/login") return;
    if (!(await authApi.restore()))
      return { path: "/login", query: { redirect: to.fullPath } };
    if (
      !["/profile", "/help", "/forbidden", "/"].includes(to.path) &&
      !visiblePaths().includes(to.path)
    )
      return "/forbidden";
    return;
  }
  if (sessionStorage.getItem("star-signed-out") && to.path !== "/login")
    return "/login";
});
