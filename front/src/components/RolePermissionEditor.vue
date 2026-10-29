<script setup lang="ts">
/**
 * 按模块组织角色的页面、操作及数据范围，提供搜索和明确的批量勾选入口。
 * 关联模块：SystemManagement 负责读取和提交角色版本，本组件只编辑尚未保存的授权草稿。
 */
import { computed, ref, watch } from "vue";
import AppIcon from "./AppIcon.vue";
import { Input } from "../ui";
import type { Row } from "../data/store";

const props = defineProps<{
  menus: Row[];
  departments: Row[];
  menuIds: string[];
  scope: string;
  deptIds: string[];
  disabled?: boolean;
}>();
const emit = defineEmits<{
  "update:menuIds": [value: string[]];
  "update:scope": [value: string];
  "update:deptIds": [value: string[]];
}>();
const tab = ref("functions");
const keyword = ref("");
const activeModule = ref("");
const collapsed = ref<string[]>([]);
const selected = computed(() => new Set(props.menuIds));
const byId = computed(() => new Map(props.menus.map((m) => [m.id, m])));
const children = (id: string) => props.menus.filter((m) => m.parentId === id);
function descendants(id: string): Row[] {
  const result: Row[] = [];
  const visited = new Set([id]);
  function visit(parent: string) {
    for (const m of children(parent)) {
      if (visited.has(m.id)) continue;
      visited.add(m.id);
      result.push(m);
      visit(m.id);
    }
  }
  visit(id);
  return result;
}
function available(menu: Row) {
  const visited = new Set<string>();
  let current: Row | undefined = menu;
  while (current && !visited.has(current.id)) {
    if (current.status !== "启用") return false;
    visited.add(current.id);
    current = byId.value.get(current.parentId);
  }
  return true;
}
const modules = computed<(Row & { nodes: Row[]; pages: Row[] })[]>(() =>
  props.menus
    .filter((m) => m.type !== "按钮" && !byId.value.has(m.parentId))
    .map((root) => ({
      ...root,
      nodes: [root, ...descendants(root.id)],
      pages: [root, ...descendants(root.id)].filter((m) => m.type === "菜单"),
    })),
);
const search = computed(() => keyword.value.trim().toLowerCase());
const matches = (node: Row) =>
  [node.name, node.permission, node.path].some((value) =>
    String(value || "")
      .toLowerCase()
      .includes(search.value),
  );
const visibleModules = computed(() =>
  modules.value.filter((m) => !search.value || m.nodes.some(matches)),
);
watch(
  visibleModules,
  (items) => {
    if (!items.some((m) => m.id === activeModule.value))
      activeModule.value =
        items.find((m) => m.type === "目录" && m.pages.length)?.id ||
        items[0]?.id ||
        "";
  },
  { immediate: true },
);
const current = computed(() =>
  visibleModules.value.find((m) => m.id === activeModule.value),
);
const visiblePages = computed(() => {
  if (!current.value) return [];
  return current.value.pages.filter(
    (p) =>
      !search.value ||
      matches(current.value!) ||
      matches(p) ||
      descendants(p.id).some(matches),
  );
});
const actions = (page: Row) =>
  children(page.id).filter((m) => m.type === "按钮");
const selectedCount = (nodes: Row[]) =>
  nodes.filter((m) => selected.value.has(m.id)).length;
const pageCount = computed(() =>
  selectedCount(props.menus.filter((m) => m.type === "菜单")),
);
const actionCount = computed(() =>
  selectedCount(props.menus.filter((m) => m.type === "按钮")),
);
const selectable = (nodes: Row[]) => nodes.filter(available);
// 目录随页面保存，但不计入勾选状态，避免取消全部页面后模块仍显示半选。
const grantable = (nodes: Row[]) => nodes.filter((m) => m.type !== "目录");
const allChecked = (nodes: Row[]) =>
  selectable(grantable(nodes)).length > 0 &&
  selectable(grantable(nodes)).every((m) => selected.value.has(m.id));
const partiallyChecked = (nodes: Row[]) =>
  !allChecked(nodes) && grantable(nodes).some((m) => selected.value.has(m.id));

/** 选择操作补齐祖先；取消页面同时移除其下操作，避免保留不可到达的授权。 */
function updateNodes(nodes: Row[], checked: boolean) {
  if (props.disabled) return;
  const next = new Set(props.menuIds);
  for (const node of nodes) {
    if (checked) {
      if (!available(node)) continue;
      let current: Row | undefined = node;
      const visited = new Set<string>();
      while (current && !visited.has(current.id)) {
        visited.add(current.id);
        next.add(current.id);
        current = byId.value.get(current.parentId);
      }
    } else {
      next.delete(node.id);
      descendants(node.id).forEach((m) => next.delete(m.id));
    }
  }
  emit("update:menuIds", [...next]);
}
function togglePage(id: string) {
  collapsed.value = collapsed.value.includes(id)
    ? collapsed.value.filter((v) => v !== id)
    : [...collapsed.value, id];
}
const scopeOptions = [
  { value: "仅本人", text: "仅可访问自己的业务数据", icon: "user" },
  { value: "本部门", text: "可访问所属部门的业务数据", icon: "users" },
  { value: "本部门及下级", text: "包含所属部门及其下级部门", icon: "network" },
  {
    value: "自定义部门",
    text: "指定可访问的部门，可选择多个",
    icon: "list-tree",
  },
  { value: "全部数据", text: "可访问当前租户的全部业务数据", icon: "database" },
];
const departmentRows = computed(() => {
  const result: { node: Row; depth: number }[] = [];
  const visited = new Set<string>();
  function visit(node: Row, depth: number) {
    if (visited.has(node.id)) return;
    visited.add(node.id);
    result.push({ node, depth });
    props.departments
      .filter((d) => d.parentId === node.id)
      .forEach((d) => visit(d, depth + 1));
  }
  props.departments
    .filter((d) => !props.departments.some((p) => p.id === d.parentId))
    .forEach((d) => visit(d, 0));
  props.departments.forEach((d) => visit(d, 0));
  return result;
});
function toggleDepartment(id: string, checked: boolean) {
  if (props.disabled) return;
  emit(
    "update:deptIds",
    checked
      ? [...new Set([...props.deptIds, id])]
      : props.deptIds.filter((v) => v !== id),
  );
}
</script>

<template>
  <div class="role-permission-editor">
    <div class="permission-overview">
      <span class="permission-overview-icon"
        ><AppIcon name="shield" :size="24"
      /></span>
      <div>
        <strong>按职责配置，按范围授权</strong>
        <p>选择操作会自动关联所属页面；仅勾选页面不会授予操作权限。</p>
      </div>
      <span class="permission-total"
        >已选 <b>{{ pageCount }}</b> 个页面 ·
        <b>{{ actionCount }}</b> 项操作</span
      >
    </div>
    <div class="permission-tabs" role="tablist" aria-label="权限设置">
      <button
        id="role-functions-tab"
        type="button"
        role="tab"
        :aria-selected="tab === 'functions'"
        aria-controls="role-functions-panel"
        :tabindex="tab === 'functions' ? 0 : -1"
        @click="tab = 'functions'"
        @keydown.right.prevent="
          tab = 'scope';
          ($event.currentTarget as HTMLElement).parentElement
            ?.querySelectorAll('button')[1]
            ?.focus();
        "
      >
        <AppIcon name="list-tree" :size="17" />功能权限
      </button>
      <button
        id="role-scope-tab"
        type="button"
        role="tab"
        :aria-selected="tab === 'scope'"
        aria-controls="role-scope-panel"
        :tabindex="tab === 'scope' ? 0 : -1"
        @click="tab = 'scope'"
        @keydown.left.prevent="
          tab = 'functions';
          ($event.currentTarget as HTMLElement).parentElement
            ?.querySelector('button')
            ?.focus();
        "
      >
        <AppIcon name="database" :size="17" />数据范围<span>{{ scope }}</span>
      </button>
    </div>
    <div
      v-show="tab === 'functions'"
      id="role-functions-panel"
      class="permission-function-panel"
      role="tabpanel"
      aria-labelledby="role-functions-tab"
    >
      <div class="permission-search">
        <Input
          aria-label="搜索权限"
          placeholder="搜索模块、页面或操作"
          :value="keyword"
          :onChange="(v: string) => (keyword = v)"
          showClear
          ><template #prefix
            ><AppIcon name="search" :size="16" /></template></Input
        ><span>{{
          search ? "搜索不会改变已选权限" : "先选择模块，再配置页面和操作"
        }}</span>
      </div>
      <div class="permission-workspace">
        <nav class="permission-modules" aria-label="权限模块">
          <button
            v-for="m in visibleModules"
            :key="m.id"
            type="button"
            :class="{ active: activeModule === m.id }"
            :aria-pressed="activeModule === m.id"
            @click="activeModule = m.id"
          >
            <AppIcon :name="m.icon || 'folder'" :size="18" /><span>{{
              m.name
            }}</span
            ><small
              >{{
                selectedCount(m.nodes.filter((n: Row) => n.type !== "目录"))
              }}/{{
                m.nodes.filter((n: Row) => n.type !== "目录").length
              }}</small
            >
          </button>
          <p v-if="!visibleModules.length" class="permission-empty">
            无匹配模块
          </p>
        </nav>
        <div class="permission-content">
          <template v-if="current">
            <div class="permission-module-header">
              <div>
                <h3>{{ current.name }}</h3>
                <span
                  >{{ current.pages.length }} 个页面 ·
                  {{
                    current.nodes.filter((m: Row) => m.type === "按钮").length
                  }}
                  项操作</span
                >
              </div>
              <div class="permission-module-tools">
                <label
                  ><input
                    type="checkbox"
                    :aria-label="'全选' + current.name + '模块'"
                    :checked="allChecked(current.nodes)"
                    :indeterminate="partiallyChecked(current.nodes)"
                    :disabled="disabled || !selectable(current.nodes).length"
                    @change="
                      updateNodes(
                        current!.nodes,
                        ($event.target as HTMLInputElement).checked,
                      )
                    "
                  />全选本模块</label
                ><button
                  type="button"
                  @click="
                    collapsed = collapsed.length ? [] : menus.map((m) => m.id)
                  "
                >
                  {{ collapsed.length ? "展开操作" : "收起操作" }}
                </button>
              </div>
            </div>
            <div
              class="permission-page-list"
              role="region"
              :aria-label="current.name + '权限列表'"
              tabindex="0"
            >
              <section
                v-for="p in visiblePages"
                :key="p.id"
                class="permission-page"
                :class="{ selected: selected.has(p.id) }"
              >
                <div class="permission-page-header">
                  <label
                    ><input
                      type="checkbox"
                      :aria-label="p.name + '页面访问'"
                      :checked="selected.has(p.id)"
                      :disabled="disabled || !available(p)"
                      @change="
                        updateNodes(
                          [p],
                          ($event.target as HTMLInputElement).checked,
                        )
                      "
                    /><strong>{{ p.name }}</strong
                    ><small>{{
                      available(p) ? "页面访问" : "已停用"
                    }}</small></label
                  ><button
                    v-if="actions(p).length"
                    type="button"
                    :aria-label="
                      (collapsed.includes(p.id) ? '展开' : '收起') +
                      p.name +
                      '操作'
                    "
                    :aria-expanded="!collapsed.includes(p.id)"
                    @click="togglePage(p.id)"
                  >
                    <span
                      >{{ selectedCount(actions(p)) }}/{{
                        actions(p).length
                      }}
                      项操作</span
                    ><AppIcon
                      :name="collapsed.includes(p.id) ? 'right' : 'down'"
                      :size="15"
                    /></button
                  ><span v-else class="permission-no-actions">无独立操作</span>
                </div>
                <div
                  v-if="actions(p).length && !collapsed.includes(p.id)"
                  class="permission-page-actions"
                >
                  <div class="permission-action-grid">
                    <label
                      v-for="a in actions(p)"
                      :key="a.id"
                      :class="{
                        checked: selected.has(a.id),
                        unavailable: !available(a),
                      }"
                      ><input
                        type="checkbox"
                        :aria-label="p.name + '：' + a.name"
                        :checked="selected.has(a.id)"
                        :disabled="disabled || !available(a)"
                        @change="
                          updateNodes(
                            [a],
                            ($event.target as HTMLInputElement).checked,
                          )
                        "
                      />{{ a.name
                      }}<small v-if="!available(a)">停用</small></label
                    >
                  </div>
                  <label class="permission-action-all"
                    ><input
                      type="checkbox"
                      :aria-label="'全选' + p.name + '操作'"
                      :checked="allChecked(actions(p))"
                      :indeterminate="partiallyChecked(actions(p))"
                      :disabled="disabled || !selectable(actions(p)).length"
                      @change="
                        updateNodes(
                          actions(p),
                          ($event.target as HTMLInputElement).checked,
                        )
                      "
                    />全选操作</label
                  >
                </div>
              </section>
              <p v-if="!visiblePages.length" class="permission-empty">
                该模块没有匹配的页面
              </p>
            </div>
          </template>
          <div v-else class="permission-empty permission-no-results">
            <AppIcon name="search" :size="30" /><strong>没有找到相关权限</strong
            ><span>试试其他页面或操作名称</span>
          </div>
        </div>
      </div>
    </div>
    <div
      v-show="tab === 'scope'"
      id="role-scope-panel"
      class="permission-scope-panel"
      role="tabpanel"
      aria-labelledby="role-scope-tab"
    >
      <div class="permission-scope-intro">
        <h3>设置可访问的数据范围</h3>
        <p>数据范围仅对已授权的功能生效，不会额外授予页面或操作权限。</p>
      </div>
      <div
        class="permission-scope-options"
        role="radiogroup"
        aria-label="数据权限范围"
      >
        <label
          v-for="s in scopeOptions"
          :key="s.value"
          :class="{ active: scope === s.value }"
          ><AppIcon :name="s.icon" :size="21" /><span
            ><strong>{{ s.value }}</strong
            ><small>{{ s.text }}</small></span
          ><input
            type="radio"
            name="role-data-scope"
            :value="s.value"
            :checked="scope === s.value"
            :disabled="disabled"
            @change="emit('update:scope', s.value)"
        /></label>
      </div>
      <section v-if="scope === '自定义部门'" class="permission-departments">
        <div>
          <h4>选择可访问部门</h4>
          <span>已选 {{ deptIds.length }} 个 · 仅包含勾选部门</span>
        </div>
        <div class="permission-department-list">
          <label
            v-for="{ node: d, depth } in departmentRows"
            :key="d.id"
            :style="{ paddingLeft: 12 + Math.min(depth, 6) * 18 + 'px' }"
            ><input
              type="checkbox"
              :aria-label="'数据部门：' + d.name"
              :checked="deptIds.includes(d.id)"
              :disabled="disabled || d.status !== '启用'"
              @change="
                toggleDepartment(
                  d.id,
                  ($event.target as HTMLInputElement).checked,
                )
              "
            /><AppIcon name="network" :size="15" />{{ d.name
            }}<small v-if="d.status !== '启用'">已停用</small></label
          >
          <p v-if="!departments.length" class="permission-empty">
            暂无可选择的部门，请先在部门管理中添加。
          </p>
        </div>
        <p v-if="!deptIds.length" class="permission-department-hint">
          请选择至少一个部门后保存。
        </p>
      </section>
    </div>
    <div class="permission-save-note">
      <AppIcon name="help" :size="15" />调整后点击“保存权限”统一生效。
    </div>
  </div>
</template>

<style scoped>
:global(.role-permission-modal) {
  top: 5vh;
  max-width: calc(100vw - 28px);
}
:global(.role-permission-modal .semi-modal-content) {
  display: flex;
  flex-direction: column;
  max-height: 90dvh;
}
:global(.role-permission-modal .semi-modal-body) {
  min-height: 0;
  overflow-y: auto;
}
:global(.role-permission-modal .semi-modal-header),
:global(.role-permission-modal .semi-modal-footer) {
  flex-shrink: 0;
}
.role-permission-editor {
  color: #243b60;
  font-size: 13px;
}
.permission-overview {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #f3f8ff;
  padding: 16px;
  border: 1px solid #e7effb;
  border-radius: 10px;
}
.permission-overview-icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: #e3efff;
  color: #1677ff;
  flex-shrink: 0;
}
.permission-overview strong {
  font-size: 14px;
}
.permission-overview p {
  color: #7b8fac;
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
.permission-total {
  margin-left: auto;
  white-space: nowrap;
  font-size: 12px;
  color: #7286a5;
}
.permission-total b {
  color: #1677ff;
  font-size: 17px;
  margin: 0 3px;
}
.permission-tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid #e8eef7;
  margin: 14px 0 16px;
}
.permission-tabs button {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 12px 2px;
  color: #7488a6;
  border-bottom: 2px solid transparent;
  font-size: 14px;
}
.permission-tabs button[aria-selected="true"] {
  color: #1677ff;
  border-bottom-color: #1677ff;
  font-weight: 600;
}
.permission-tabs button span {
  background: #f0f4fa;
  border-radius: 5px;
  padding: 2px 6px;
  font-size: 11px;
  color: #7285a1;
  font-weight: 400;
}
.permission-search {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 14px;
}
.permission-function-panel {
  display: flex;
  flex-direction: column;
  height: 422px;
}
.permission-function-panel > .permission-search {
  flex-shrink: 0;
}
.permission-search :deep(.semi-input-wrapper) {
  max-width: 340px;
}
.permission-search > span {
  font-size: 12px;
  color: #8b9bb2;
}
.permission-workspace {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  height: 370px;
  border: 1px solid #e5ecf6;
  border-radius: 10px;
  overflow: hidden;
  flex: 1;
  min-height: 0;
}
.permission-modules {
  background: #f8faff;
  padding: 10px;
  border-right: 1px solid #e5ecf6;
  overflow: auto;
}
.permission-modules button {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 8px;
  text-align: left;
  padding: 12px 10px;
  border-radius: 7px;
  color: #647e9f;
  margin-bottom: 4px;
}
.permission-modules button span {
  flex: 1;
}
.permission-modules button small {
  color: #91a1b8;
  font-size: 11px;
}
.permission-modules button.active {
  color: #1677ff;
  background: #e8f2ff;
  font-weight: 600;
}
.permission-modules button.active small {
  color: #1677ff;
  font-weight: 400;
}
.permission-content {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.permission-module-header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 15px 18px;
  border-bottom: 1px solid #edf1f7;
}
.permission-module-header h3,
.permission-scope-intro h3 {
  font-size: 14px;
  margin: 0 0 5px;
}
.permission-module-header span {
  color: #8b9bb2;
  font-size: 11px;
}
.permission-module-tools {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
  font-size: 12px;
}
.permission-module-tools label,
.permission-action-all {
  display: flex;
  align-items: center;
  gap: 6px;
}
.permission-module-tools button {
  color: #1677ff;
  font-size: 12px;
}
.permission-page-list {
  /* 允许列表在网格和弹性布局内收缩，内容超出时只滚动权限列表。 */
  min-height: 0;
  overflow-x: hidden;
  overflow-y: scroll;
  overscroll-behavior-y: contain;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: #b5c8e1 #f5f8fd;
  padding: 14px 16px;
  flex: 1 1 0;
}
.permission-page-list::-webkit-scrollbar {
  width: 8px;
}
.permission-page-list::-webkit-scrollbar-track {
  background: #f5f8fd;
}
.permission-page-list::-webkit-scrollbar-thumb {
  background: #b5c8e1;
  border: 2px solid #f5f8fd;
  border-radius: 8px;
}
.permission-page-list::-webkit-scrollbar-thumb:hover {
  background: #8daacd;
}
.permission-page-list:focus-visible {
  outline: 2px solid #a3caff;
  outline-offset: -2px;
}
.permission-page {
  border: 1px solid #e8eef6;
  border-radius: 8px;
  margin-bottom: 12px;
  overflow: hidden;
}
.permission-page.selected {
  border-color: #cbdffa;
}
.permission-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px;
  background: #f8faff;
}
.permission-page-header label {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.permission-page-header strong {
  font-size: 13px;
}
.permission-page-header small {
  color: #98a7bd;
  font-size: 11px;
  font-weight: 400;
}
.permission-page-header button {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #8195b0;
  white-space: nowrap;
  font-size: 11px;
}
.permission-no-actions {
  font-size: 11px;
  color: #98a7bd;
}
.permission-page-actions {
  padding: 12px;
}
.permission-action-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.permission-action-grid label {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px;
  border-radius: 5px;
  background: #f8fafd;
  color: #607797;
  line-height: 1.4;
}
.permission-action-grid label.checked {
  background: #eff6ff;
  color: #1677ff;
}
.permission-action-grid small {
  font-size: 10px;
}
.permission-action-grid .unavailable {
  opacity: 0.55;
}
.permission-action-all {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #e8eef6;
  color: #8598b3;
  font-size: 11px;
}
.role-permission-editor input[type="checkbox"],
.role-permission-editor input[type="radio"] {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  margin: 0;
  accent-color: #1677ff;
  cursor: pointer;
}
.role-permission-editor input:disabled {
  cursor: not-allowed;
}
.role-permission-editor button:focus-visible,
.role-permission-editor input:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: 3px;
}
.permission-empty {
  padding: 28px 12px;
  text-align: center;
  color: #93a4bc;
  font-size: 12px;
}
.permission-no-results {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.permission-scope-panel {
  height: 422px;
  overflow: auto;
  padding: 2px;
}
.permission-scope-intro p {
  color: #8b9bb2;
  margin: 6px 0 16px;
  font-size: 12px;
}
.permission-scope-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.permission-scope-options label {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid #e5ecf6;
  border-radius: 8px;
  cursor: pointer;
}
.permission-scope-options label > svg {
  color: #809abd;
}
.permission-scope-options label span {
  flex: 1;
}
.permission-scope-options strong {
  display: block;
  font-size: 13px;
  font-weight: 500;
}
.permission-scope-options small {
  display: block;
  color: #8b9bb2;
  font-size: 11px;
  margin-top: 5px;
  line-height: 1.5;
}
.permission-scope-options label.active {
  border-color: #9dc7ff;
  background: #f3f8ff;
}
.permission-scope-options label.active > svg,
.permission-scope-options label.active strong {
  color: #1677ff;
}
.permission-departments {
  margin-top: 18px;
}
.permission-departments > div:first-child {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  gap: 12px;
}
.permission-departments h4 {
  margin: 0;
  font-size: 13px;
}
.permission-departments span {
  font-size: 11px;
  color: #8b9bb2;
}
.permission-department-list {
  border: 1px solid #e5ecf6;
  border-radius: 8px;
}
.permission-department-list label {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
}
.permission-department-list label > svg {
  color: #8ba3c4;
}
.permission-department-list small {
  color: #98a7bd;
}
.permission-department-hint {
  color: #b37c2c;
  font-size: 12px;
}
.permission-save-note {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #8b9bb2;
  margin-top: 14px;
  font-size: 12px;
}
@media (max-width: 700px) {
  .permission-function-panel {
    height: 426px;
  }
  .permission-overview {
    flex-wrap: wrap;
    padding: 12px;
  }
  .permission-overview > div {
    flex: 1;
  }
  .permission-total {
    margin-left: 54px;
  }
  .permission-tabs {
    gap: 18px;
  }
  .permission-tabs button {
    font-size: 13px;
  }
  .permission-search {
    margin-bottom: 10px;
  }
  .permission-search > span {
    display: none;
  }
  .permission-search :deep(.semi-input-wrapper) {
    max-width: none;
  }
  .permission-workspace {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    height: 380px;
  }
  .permission-modules {
    display: flex;
    gap: 6px;
    padding: 8px;
    border-right: 0;
    border-bottom: 1px solid #e5ecf6;
  }
  .permission-modules button {
    width: auto;
    flex-shrink: 0;
    white-space: nowrap;
    padding: 9px;
    margin: 0;
  }
  .permission-modules button > svg {
    display: none;
  }
  .permission-module-header {
    padding: 12px;
    flex-wrap: wrap;
  }
  .permission-module-tools {
    margin-left: auto;
    gap: 10px;
  }
  .permission-page-list {
    padding: 10px;
  }
  .permission-action-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .permission-page-header small {
    display: none;
  }
  .permission-scope-panel {
    height: 426px;
  }
  .permission-scope-options {
    grid-template-columns: minmax(0, 1fr);
  }
  .permission-departments > div:first-child {
    flex-wrap: wrap;
  }
}
</style>
