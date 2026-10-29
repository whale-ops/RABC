<script setup lang="ts">
/**
 * 实现应用主布局、分级侧边栏、面包屑、页面搜索及账号菜单，并适配手机导航。
 * 关联模块：读取 session.ts 和 sidebar-navigation.ts；SidebarItem.vue 递归渲染，router.ts 展示子页面，auth.ts 处理退出。
 */
import {
  ref,
  computed,
  watch,
  onMounted,
  onBeforeUnmount,
  h,
  nextTick,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { Input, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import SidebarItem from "../components/SidebarItem.vue";
import { session } from "../api/session";
import {
  sidebarNavigation,
  flattenNavigation,
  activeAncestors,
} from "../utils/sidebar-navigation";
import { useMock } from "../api/request";
import { ui } from "../data/store";
import { authApi } from "../api/auth";
import {
  branding,
  systemConfigApi,
  applyBranding,
  subscribeConfigChanges,
} from "../api/system-config";
let brandRevision = 0;
const stopConfigSync = subscribeConfigChanges(async (changedTenant) => {
  const code = session.user?.tenant.code;
  if (!code || (changedTenant && changedTenant !== code)) return;
  const current = ++brandRevision;
  try {
    const value = await systemConfigApi.public(code);
    if (current === brandRevision && session.user?.tenant.code === code)
      applyBranding(value);
  } catch {
    /* 保留已加载品牌，接口恢复或再次激活窗口时重新读取。 */
  }
});
onBeforeUnmount(() => {
  ++brandRevision;
  stopConfigSync();
});
const route = useRoute();
const navigation = computed(() => sidebarNavigation(session.menus, useMock));
const allLinks = computed(() =>
  flattenNavigation(navigation.value).filter(
    (item) => item.path && !item.pending,
  ),
);
const router = useRouter();
function savedCollapsed() {
  try {
    return localStorage.getItem("star-sidebar-collapsed") === "true";
  } catch {
    return false;
  }
}
const collapsed = ref(savedCollapsed());
const mobileOpen = ref(false);
const sidebar = ref<HTMLElement | null>(null);
const mobileTrigger = ref<HTMLButtonElement | null>(null);
// 默认收起所有分组；当前页面所在的祖先分组由路由统一展开。
const opened = ref<string[]>([]);
watch(collapsed, (value) => {
  try {
    localStorage.setItem("star-sidebar-collapsed", String(value));
  } catch {
    /* 界面偏好保存失败不影响功能。 */
  }
});
const search = ref("");
const panel = ref("");
const matches = computed(() =>
  allLinks.value.filter((n) => n.label.includes(search.value)),
);
const group = computed(
  () =>
    navigation.value.find(
      (item) =>
        item.children?.length && activeAncestors([item], route.path).length,
    )?.label,
);
/** 折叠状态下先展开完整导航，再展示选中的分组，避免只留下难以辨认的子菜单图标。 */
function toggle(id: string) {
  if (collapsed.value && !mobileOpen.value) {
    collapsed.value = false;
    if (!opened.value.includes(id)) opened.value.push(id);
    return;
  }
  opened.value = opened.value.includes(id)
    ? opened.value.filter((value) => value !== id)
    : [...opened.value, id];
}
function closeMobile(restoreFocus = false) {
  mobileOpen.value = false;
  if (restoreFocus) void nextTick(() => mobileTrigger.value?.focus());
}
function keyboardNavigation(event: KeyboardEvent) {
  if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
  const nav = event.currentTarget as HTMLElement;
  const items = Array.from(
    nav.querySelectorAll<HTMLElement>(
      "a.nav-item, button.nav-item:not(:disabled)",
    ),
  ).filter((item) => item.getClientRects().length > 0);
  const current = items.indexOf(document.activeElement as HTMLElement);
  if (current < 0 || !items.length) return;
  event.preventDefault();
  const next =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? items.length - 1
        : (current + (event.key === "ArrowDown" ? 1 : -1) + items.length) %
          items.length;
  items[next]?.focus();
}
/** 在手机导航打开时处理退出和焦点循环，防止键盘进入遮罩后的页面。 */
function globalKeyboard(event: KeyboardEvent) {
  if (event.key === "Escape" && mobileOpen.value) {
    event.preventDefault();
    closeMobile(true);
  }
  if (event.key === "Tab" && mobileOpen.value && sidebar.value) {
    const controls = Array.from(
      sidebar.value.querySelectorAll<HTMLElement>(
        "a[href],button:not(:disabled)",
      ),
    ).filter((item) => item.getClientRects().length > 0);
    const first = controls[0],
      last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }
}
function windowResize() {
  if (window.innerWidth > 800) closeMobile();
}
function go(path: string) {
  router.push(path);
  panel.value = "";
  search.value = "";
  mobileOpen.value = false;
}
async function fullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
  } catch {
    Toast.info("当前浏览器不支持全屏");
  }
}
async function logout() {
  try {
    await authApi.logout();
  } catch {
    Toast.warning("远端注销未完成，已清除本地登录状态");
  } finally {
    sessionStorage.setItem("star-signed-out", "true");
    router.push("/login");
  }
}
function outside(e: MouseEvent) {
  if (!(e.target as HTMLElement).closest(".header-popover-anchor"))
    panel.value = "";
}
watch(
  [() => route.path, navigation],
  () => {
    const ancestors = activeAncestors(navigation.value, route.path).slice(
      0,
      -1,
    );
    // 刷新或切换模块只展开当前路径，不累积之前浏览过的分组。
    opened.value = ancestors;
  },
  { immediate: true },
);
watch(
  () => route.path,
  () => {
    closeMobile();
    panel.value = "";
  },
);
watch(mobileOpen, async (open) => {
  document.body.classList.toggle("sidebar-drawer-open", open);
  if (open) {
    await nextTick();
    if (mobileOpen.value)
      sidebar.value
        ?.querySelector<HTMLButtonElement>(".sidebar-mobile-close")
        ?.focus();
  }
});
onMounted(() => {
  document.addEventListener("click", outside);
  document.addEventListener("keydown", globalKeyboard);
  window.addEventListener("resize", windowResize);
});
onBeforeUnmount(() => {
  document.removeEventListener("click", outside);
  document.removeEventListener("keydown", globalKeyboard);
  window.removeEventListener("resize", windowResize);
  document.body.classList.remove("sidebar-drawer-open");
});
</script>
<template>
  <div class="app-shell" :class="{ collapsed, 'mobile-open': mobileOpen }">
    <div v-if="mobileOpen" class="mobile-backdrop" @click="closeMobile(true)" />
    <aside
      id="main-sidebar"
      ref="sidebar"
      class="sidebar"
      aria-label="侧边导航"
    >
      <RouterLink to="/dashboard" class="brand"
        ><img :src="branding.systemLogo" :alt="branding.systemName" />
        <div>
          <strong>{{ branding.systemName }}</strong
          ><small>{{ branding.systemDescription }}</small>
        </div></RouterLink
      >
      <button
        type="button"
        class="icon-button sidebar-mobile-close"
        aria-label="关闭导航"
        @click="closeMobile(true)"
      >
        <AppIcon name="close" />
      </button>
      <nav class="side-nav" aria-label="主导航" @keydown="keyboardNavigation">
        <div
          v-if="!useMock && !allLinks.length && !collapsed"
          class="sidebar-access-note"
        >
          <AppIcon name="lock" :size="16" />
          <p>
            暂未分配功能权限，请联系管理员。<RouterLink
              to="/profile"
              @click="closeMobile()"
              >前往个人中心</RouterLink
            >
          </p>
        </div>
        <ul class="sidebar-list">
          <SidebarItem
            v-for="item in navigation"
            :key="item.id"
            :item="item"
            :opened="opened"
            :active-path="route.path"
            :compact="collapsed && !mobileOpen"
            @toggle="toggle"
            @navigate="closeMobile()"
          />
        </ul>
      </nav>
      <div class="sidebar-bottom">
        <span class="online-dot" /><span>系统运行正常</span
        ><small>v1.0.0</small>
      </div>
    </aside>
    <div class="main-shell" :inert="mobileOpen">
      <header class="topbar">
        <button
          class="icon-button desktop-menu"
          :aria-label="collapsed ? '展开导航' : '折叠导航'"
          :title="collapsed ? '展开导航' : '折叠导航'"
          aria-controls="main-sidebar"
          :aria-expanded="!collapsed"
          @click="collapsed = !collapsed"
        >
          <AppIcon name="menu" />
        </button>
        <button
          class="icon-button mobile-menu"
          ref="mobileTrigger"
          :aria-label="mobileOpen ? '关闭导航' : '打开导航'"
          aria-controls="main-sidebar"
          :aria-expanded="mobileOpen"
          @click="mobileOpen = !mobileOpen"
        >
          <AppIcon name="menu" />
        </button>
        <div class="breadcrumb">
          <RouterLink to="/dashboard"
            ><AppIcon name="home" :size="19" /></RouterLink
          ><template v-if="route.path !== '/dashboard'"
            ><span class="crumb-separator">/</span
            ><span v-if="group" class="crumb-group">{{ group }}</span
            ><span v-if="group" class="crumb-separator">/</span></template
          ><span class="current-crumb">{{ route.meta.title }}</span>
        </div>
        <div class="header-actions">
          <div class="header-search header-popover-anchor">
            <Input
              :suffix="h(AppIcon, { name: 'search', size: 17 })"
              :value="search"
              placeholder="请输入关键字搜索..."
              :onChange="
                (v: string) => {
                  search = v;
                  panel = 'search';
                }
              "
              :onFocus="() => (panel = 'search')"
              ><template #suffix><AppIcon name="search" :size="17" /></template
            ></Input>
            <div
              v-if="panel === 'search' && search"
              class="header-dropdown search-results"
            >
              <div class="dropdown-caption">搜索页面</div>
              <button
                v-for="link in matches"
                :key="link.path"
                @click="go(link.path!)"
              >
                <AppIcon :name="link.icon" :size="17" />{{ link.label
                }}<AppIcon name="right" :size="14" />
              </button>
              <div v-if="!matches.length" class="dropdown-empty">
                没有找到相关页面
              </div>
            </div>
          </div>
          <div v-if="useMock" class="header-popover-anchor">
            <button
              class="icon-button notification-button"
              aria-label="通知消息"
              @click="panel = panel === 'notifications' ? '' : 'notifications'"
            >
              <AppIcon name="bell" /><b v-if="ui.unread">{{ ui.unread }}</b>
            </button>
            <div
              v-if="panel === 'notifications'"
              class="header-dropdown notification-dropdown"
            >
              <div class="dropdown-heading">
                <strong
                  >消息通知 <small>{{ ui.unread }}</small></strong
                ><button class="text-button" @click="ui.unread = 0">
                  全部已读
                </button>
              </div>
              <button class="notice-item" @click="go('/business/projects')">
                <span class="notice-icon"><AppIcon name="folder" /></span
                ><span
                  ><strong>项目进度已更新</strong
                  ><small>企业数字化管理平台已完成 75%</small
                  ><em>10 分钟前</em></span
                ><i v-if="ui.unread" /></button
              ><button class="notice-item" @click="go('/system/roles')">
                <span class="notice-icon mint"><AppIcon name="shield" /></span
                ><span
                  ><strong>角色权限更新</strong
                  ><small>运维人员角色已完成配置</small><em>30 分钟前</em></span
                ></button
              ><button class="notice-item" @click="go('/help')">
                <span class="notice-icon purple"
                  ><AppIcon name="success" /></span
                ><span
                  ><strong>欢迎使用 {{ branding.systemName }}</strong
                  ><small>开始探索您的企业工作空间</small
                  ><em>1 小时前</em></span
                >
              </button>
            </div>
          </div>
          <button
            class="icon-button fullscreen-button"
            aria-label="全屏"
            @click="fullscreen"
          >
            <AppIcon name="maximize" :size="18" />
          </button>
          <div class="header-popover-anchor">
            <button
              class="user-menu-button"
              @click="panel = panel === 'user' ? '' : 'user'"
            >
              <span class="avatar admin-avatar">{{
                ui.profile.name.slice(0, 1)
              }}</span
              ><span>{{ ui.profile.name }}</span
              ><AppIcon name="down" :size="13" />
            </button>
            <div v-if="panel === 'user'" class="header-dropdown user-dropdown">
              <div class="dropdown-caption">{{ ui.profile.email }}</div>
              <button @click="go('/profile')">
                <AppIcon name="user" :size="17" />个人中心</button
              ><button @click="go('/help')">
                <AppIcon name="help" :size="17" />帮助中心
              </button>
              <hr />
              <button class="danger" @click="logout">
                <AppIcon name="logout" :size="17" />退出登录
              </button>
            </div>
          </div>
        </div>
      </header>
      <main class="page-content">
        <div v-if="ui.storageError" class="storage-warning">
          浏览器存储空间不足，当前修改仅保留在本次会话中。
        </div>
        <RouterView :key="route.path" />
      </main>
      <footer class="app-footer">
        <span
          >© 2025 {{ branding.systemName }}
          <span class="footer-product">{{
            branding.systemDescription
          }}</span></span
        >
        <nav>
          <RouterLink to="/dashboard">首页</RouterLink
          ><RouterLink to="/help">文档</RouterLink
          ><RouterLink to="/help?tab=faq">帮助</RouterLink>
        </nav>
      </footer>
    </div>
  </div>
</template>
