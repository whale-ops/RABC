<script setup lang="ts">
/**
 * 递归展示导航分组、选中页面和待开放入口，支持折叠及移动端的父子事件传递。
 * 关联模块：Shell.vue 管理展开状态和路由；sidebar-navigation.ts 提供菜单树，AppIcon.vue 渲染图标。
 */
import { computed } from "vue";
import AppIcon from "./AppIcon.vue";
import { activeAncestors, type SidebarEntry } from "../utils/sidebar-navigation";

const props = withDefaults(defineProps<{
  item: SidebarEntry;
  opened: string[];
  activePath: string;
  compact?: boolean;
  depth?: number;
}>(), { compact: false, depth: 0 });
const emit = defineEmits<{ toggle: [id: string]; navigate: [] }>();
const isOpen = computed(() => !props.compact && props.opened.includes(props.item.id));
const selected = computed(() => activeAncestors([props.item], props.activePath).length > 0);
const childrenId = computed(() => `sidebar-children-${encodeURIComponent(props.item.id)}`);
</script>

<template>
  <li class="sidebar-node" :style="{ '--nav-depth': depth }">
    <button v-if="item.children?.length" type="button"
      class="nav-item nav-group" :class="{ 'group-selected': selected }"
      :title="item.pending ? `${item.label} · 待开放` : item.label"
      :aria-label="item.label" :aria-expanded="isOpen" :aria-controls="childrenId"
      @click="emit('toggle', item.id)">
      <AppIcon :name="item.icon" /><span class="nav-label">{{ item.label }}</span>
      <span v-if="item.pending" class="nav-pending">待开放</span>
      <AppIcon name="down" :size="14" class="nav-chevron" :class="{ rotated: isOpen }" />
    </button>
    <button v-else-if="item.pending" type="button" class="nav-item nav-unavailable"
      :aria-label="`${item.label}，待开放`" disabled :title="`${item.label}功能暂未开放`">
      <AppIcon :name="item.icon" /><span class="nav-label">{{ item.label }}</span>
      <span v-if="depth === 0" class="nav-pending">待开放</span>
      <AppIcon v-else name="lock" :size="12" class="nav-lock" />
    </button>
    <RouterLink v-else-if="item.path" :to="item.path" class="nav-item" :title="item.label"
      :class="{ active: activePath === item.path }" :aria-current="activePath === item.path ? 'page' : undefined"
      @click="emit('navigate')">
      <AppIcon :name="item.icon" /><span class="nav-label">{{ item.label }}</span>
    </RouterLink>
    <ul v-if="isOpen && item.children?.length" :id="childrenId" class="nav-children">
      <SidebarItem v-for="child in item.children" :key="child.id" :item="child"
        :opened="opened" :active-path="activePath" :depth="depth + 1"
        @toggle="emit('toggle', $event)" @navigate="emit('navigate')" />
    </ul>
  </li>
</template>
