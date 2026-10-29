<script setup lang="ts">
/**
 * 实现真实用户、角色、菜单、部门和岗位管理，包括表单、权限、导入导出与批量操作。
 * 关联模块：通过 system-live.ts 调用 SystemController；复用账号审核和导入组件，session.ts 控制操作权限，catalog.ts 提供展示配置。
 */
import { ref, reactive, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Button,
  Input,
  Select,
  Switch,
  SideSheet,
  Modal,
  Pagination,
  Toast,
  TextArea,
} from "../ui";
import AppIcon from "../components/AppIcon.vue";
import TableDisplayTools from "../components/TableDisplayTools.vue";
import RolePermissionEditor from "../components/RolePermissionEditor.vue";
import StatusPill from "../components/StatusPill.vue";
import AccountRequests from "../components/AccountRequests.vue";
import UserImport from "../components/UserImport.vue";
import RoleImport from "../components/RoleImport.vue";
import { modules, type Field } from "../data/catalog";
import { downloadBlob, type Row } from "../data/store";
import { liveApi, type Options, type ListQuery } from "../api/system-live";
import { errorMessage } from "../api/request";
import { session, can } from "../api/session";
import { authApi } from "../api/auth";
const props = defineProps<{ moduleKey: string }>();
const route = useRoute(),
  router = useRouter();
const config = computed(() => modules[props.moduleKey]!);
const prefix = computed(
  () =>
    "system:" +
    (
      {
        users: "user",
        roles: "role",
        menus: "menu",
        departments: "dept",
        posts: "post",
      } as Record<string, string>
    )[props.moduleKey],
);
const permitted = (action: string) => can(prefix.value + ":" + action);
const adminOnly = computed(() => ["roles", "menus"].includes(props.moduleKey));
const writable = (action: string) =>
  permitted(action) &&
  (!(adminOnly.value || (props.moduleKey === "users" && action === "create")) ||
    session.user?.admin);
const rows = ref<Row[]>([]),
  total = ref(0),
  query = ref(""),
  keyword = ref(""),
  status = ref(""),
  department = ref(""),
  deptQuery = ref("");
const page = ref(1),
  pageSize = ref(10),
  sortKey = ref("createdAt"),
  direction = ref("desc"),
  selected = ref<string[]>([]);
const loading = ref(false),
  loaded = ref(false),
  saving = ref(false),
  loadError = ref(""),
  hiddenColumns = ref<string[]>([]);
const options = reactive<Options>({
  departments: [],
  roles: [],
  posts: [],
  menus: [],
});
const drawer = ref(false),
  isNew = ref(true),
  form = reactive<Row>({ id: "" }),
  errors = ref<Record<string, string>>({});
const roleInput = ref("");
const roleSuggestionsOpen = ref(false);
const enteredRoles = () => [
  ...new Set(
    roleInput.value
      .split(/[,，、\n]/)
      .map((name) => name.trim())
      .filter(Boolean),
  ),
];
const existingRoleSuggestions = computed(() => {
  const last =
    roleInput.value
      .split(/[,，、\n]/)
      .at(-1)
      ?.trim() || "";
  const exact = options.roles.some(
    (role) => role.name.toLowerCase() === last.toLowerCase(),
  );
  const query = exact ? "" : last.toLowerCase();
  const selected = new Set(enteredRoles().map((name) => name.toLowerCase()));
  return options.roles.filter(
    (role) =>
      !role.builtin &&
      role.status === "启用" &&
      !selected.has(role.name.toLowerCase()) &&
      role.name.toLowerCase().includes(query),
  );
});
function chooseExistingRole(name: string) {
  const names = enteredRoles();
  const last =
    roleInput.value
      .split(/[,，、\n]/)
      .at(-1)
      ?.trim() || "";
  if (
    last &&
    !options.roles.some(
      (role) => role.name.toLowerCase() === last.toLowerCase(),
    )
  )
    names.pop();
  if (names.length >= 10) return Toast.warning("最多填写 10 个角色");
  roleInput.value = [...new Set([...names, name])].join("、");
  roleSuggestionsOpen.value = false;
}
function leaveRolePicker(event: FocusEvent) {
  const picker = event.currentTarget as HTMLElement;
  if (
    !(event.relatedTarget instanceof Node) ||
    !picker.contains(event.relatedTarget)
  )
    roleSuggestionsOpen.value = false;
}
function openRoleSuggestions() {
  if (session.user?.admin) roleSuggestionsOpen.value = true;
}
function updateRoleInput(value: string) {
  roleInput.value = value;
  openRoleSuggestions();
}
function toggleRoleSuggestions(event: MouseEvent) {
  const open = !roleSuggestionsOpen.value;
  (event.currentTarget as HTMLElement).parentElement
    ?.querySelector("input")
    ?.focus();
  roleSuggestionsOpen.value = open;
}
const pendingDelete = ref<Row[]>([]),
  details = ref<Row | null>(null),
  roleTarget = ref<Row | null>(null),
  menuIds = ref<string[]>([]),
  scope = ref("仅本人"),
  deptIds = ref<string[]>([]);
const resetTarget = ref<Row | null>(null),
  newPassword = ref("");
let requestNumber = 0;
const columns = computed(() =>
  config.value.columns.filter((c) => !hiddenColumns.value.includes(c.key)),
);
const departments = computed(() =>
  options.departments.filter((d) => d.name.includes(deptQuery.value)),
);
const allSelected = computed(
  () =>
    rows.value.length > 0 &&
    rows.value.every((r) => selected.value.includes(r.id)),
);
const fields = computed(() => {
  const list: (Field & { multiple?: boolean; password?: boolean })[] =
    config.value.fields.map((f) => {
      if (props.moduleKey === "users") {
        if (f.key === "department") return { ...f, key: "deptId" };
        if (f.key === "role") return { ...f, key: "roleIds", multiple: true };
        if (f.key === "post") return { ...f, key: "postIds", multiple: true };
      }
      if (f.key === "parent") return { ...f, key: "parentId" };
      return { ...f };
    });
  if (props.moduleKey === "users" && isNew.value)
    list.splice(2, 0, {
      key: "initialPassword",
      label: "初始密码",
      required: true,
      password: true,
    });
  return list;
});
const params = (): ListQuery => ({
  page: page.value,
  size: pageSize.value,
  keyword: keyword.value,
  status: status.value,
  deptId: department.value,
  sort: sortKey.value,
  direction: direction.value,
});
/** 使用后台分页、排序与数据范围，忽略过期请求结果，避免旧响应覆盖新筛选。 */
async function loadRecords() {
  const requestId = ++requestNumber;
  loading.value = true;
  loadError.value = "";
  try {
    const result = await liveApi.list(props.moduleKey, params());
    if (requestId !== requestNumber) return;
    rows.value = result.records;
    total.value = result.total;
    selected.value = [];
    if (page.value > 1 && !rows.value.length && result.total > 0) {
      page.value = Math.ceil(result.total / pageSize.value);
      await loadRecords();
    }
  } catch (e) {
    if (requestId === requestNumber) {
      rows.value = [];
      total.value = 0;
      loadError.value = errorMessage(e);
    }
  } finally {
    if (requestId === requestNumber) {
      loading.value = false;
      loaded.value = true;
    }
  }
}
async function loadOptions() {
  Object.assign(options, await liveApi.options());
}
async function reload() {
  await loadRecords();
  try {
    await loadOptions();
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
onMounted(async () => {
  await reload();
  if (route.query.create === "1" && writable("create")) await edit();
});
async function search() {
  if (loading.value) return;
  keyword.value = query.value.trim();
  page.value = 1;
  await loadRecords();
}
async function reset() {
  if (loading.value) return;
  query.value = "";
  keyword.value = "";
  status.value = "";
  department.value = "";
  page.value = 1;
  await loadRecords();
}
async function chooseDepartment(id: string) {
  department.value = id;
  page.value = 1;
  await loadRecords();
}
async function turnPage(p: number) {
  page.value = p;
  await loadRecords();
}
async function resize(size: number) {
  pageSize.value = size;
  page.value = 1;
  await loadRecords();
}
function sortable(key: string) {
  return [
    "name",
    "username",
    "phone",
    "code",
    "scope",
    "type",
    "path",
    "permission",
    "sort",
    "status",
    "createdAt",
  ].includes(key);
}
async function sort(key: string) {
  if (!sortable(key)) return;
  direction.value =
    sortKey.value === key && direction.value === "asc" ? "desc" : "asc";
  sortKey.value = key;
  await loadRecords();
}
function toggleSelection(id: string) {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((i) => i !== id)
    : [...selected.value, id];
}
function toggleAll() {
  selected.value = allSelected.value ? [] : rows.value.map((r) => r.id);
}
const date = (value: unknown) =>
  value
    ? new Date(String(value)).toLocaleString("sv-SE", {
        timeZone: "Asia/Shanghai",
      })
    : "—";
function optionList(key: string) {
  let records: Row[] = [];
  if (key === "deptId") records = options.departments;
  if (key === "roleIds")
    records = options.roles.filter(
      (r) => !r.builtin || form.roleIds?.includes(r.id),
    );
  if (key === "postIds") records = options.posts;
  if (key === "parentId") {
    records =
      props.moduleKey === "menus"
        ? options.menus.filter((r) => r.type !== "按钮")
        : options.departments;
    const excluded = new Set<string>(form.id ? [form.id] : []);
    let changed = true;
    while (changed) {
      changed = false;
      for (const r of records)
        if (excluded.has(r.parentId) && !excluded.has(r.id)) {
          excluded.add(r.id);
          changed = true;
        }
    }
    return [
      {
        label:
          props.moduleKey === "menus"
            ? "顶级菜单"
            : session.user?.tenant.name || "根部门",
        value: "",
      },
      ...records
        .filter((r) => !excluded.has(r.id))
        .map((r) => ({
          label: r.name,
          value: r.id,
          disabled: r.status !== "启用",
        })),
    ];
  }
  if (records.length || ["deptId", "roleIds", "postIds"].includes(key))
    return records.map((r) => ({
      label: r.name,
      value: r.id,
      disabled: r.status !== "启用",
    }));
  return (fields.value.find((f) => f.key === key)?.options || []).map((v) => ({
    label: v,
    value: v,
    disabled: false,
  }));
}
async function edit(row?: Row) {
  try {
    await loadOptions();
    isNew.value = !row;
    errors.value = {};
    Object.keys(form).forEach((k) => delete form[k]);
    Object.assign(
      form,
      row
        ? await liveApi.detail(props.moduleKey, row.id)
        : {
            id: "",
            name: "",
            status: "启用",
            sort: 0,
            scope: "仅本人",
            type: "菜单",
            parentId: "",
            deptId: "",
            roleIds: [],
            postIds: [],
            initialPassword: "",
          },
    );
    for (const f of fields.value)
      if (form[f.key] == null) form[f.key] = f.type === "number" ? 0 : "";
    roleInput.value =
      form.role ||
      (form.roleIds || [])
        .map(
          (id: string) =>
            options.roles.find((role) => role.id === id)?.name || "",
        )
        .filter(Boolean)
        .join("、");
    roleSuggestionsOpen.value = false;
    drawer.value = true;
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
function validate() {
  const e: Record<string, string> = {};
  for (const f of fields.value)
    if (
      f.required &&
      (f.key === "roleIds" && session.user?.admin
        ? !enteredRoles().length
        : !form[f.key] || (Array.isArray(form[f.key]) && !form[f.key].length))
    )
      e[f.key] = "请填写" + f.label;
  if (props.moduleKey === "users" && session.user?.admin) {
    const names = enteredRoles();
    if (names.length > 10) e.roleIds = "最多填写 10 个角色";
    else if (names.some((name) => name.length > 64))
      e.roleIds = "角色名称不能超过 64 个字符";
  }
  if (
    form.initialPassword &&
    (!/[A-Za-z]/.test(form.initialPassword) ||
      !/[0-9]/.test(form.initialPassword) ||
      form.initialPassword.length < 12)
  )
    e.initialPassword = "至少 12 位，包含字母和数字";
  if (
    form.phone &&
    props.moduleKey === "users" &&
    !/^1\d{10}$/.test(form.phone)
  )
    e.phone = "请输入正确的手机号";
  if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email))
    e.email = "请输入正确的邮箱";
  errors.value = e;
  return !Object.keys(e).length;
}
/** 校验表单并提交真实接口；创建、更新和角色名称解析继续由后台规则兜底。 */
async function submit() {
  if (saving.value || !validate()) return;
  saving.value = true;
  try {
    const data: Record<string, unknown> = {};
    for (const f of fields.value) data[f.key] = form[f.key];
    if (props.moduleKey === "users" && session.user?.admin) {
      data.roleIds = [];
      data.roleNames = enteredRoles();
    }
    if (!isNew.value) data.version = form.version;
    await liveApi.save(
      props.moduleKey,
      isNew.value ? undefined : form.id,
      data,
    );
    drawer.value = false;
    Toast.success(isNew.value ? "创建成功" : "修改成功");
    await reload();
    await authApi.loadMe();
    if (route.query.create) router.replace({ path: route.path });
  } catch (e) {
    Toast.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
/** 向后台提交选中记录及版本；仅在成功后刷新，不能只删除前端表格中的一行。 */
async function confirmDelete() {
  if (saving.value) return;
  saving.value = true;
  try {
    await liveApi.remove(
      props.moduleKey,
      pendingDelete.value.map((r) => ({ id: r.id, version: r.version })),
    );
    pendingDelete.value = [];
    await reload();
    await authApi.loadMe();
    Toast.success("删除成功");
  } catch (e) {
    Toast.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
async function changeStatus(row: Row, checked: boolean) {
  try {
    await liveApi.status(props.moduleKey, row, checked ? "启用" : "停用");
    await reload();
    await authApi.loadMe();
    Toast.success("状态已更新");
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
async function showDetails(row: Row) {
  try {
    details.value = await liveApi.detail(props.moduleKey, row.id);
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
async function openPermissions(row: Row) {
  try {
    await loadOptions();
    roleTarget.value = await liveApi.detail("roles", row.id);
    menuIds.value = [...(roleTarget.value.menuIds || [])];
    scope.value = roleTarget.value.scope;
    deptIds.value = [...(roleTarget.value.deptIds || [])];
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
/** 把菜单权限和数据范围一起提交，后台负责检查管理员身份及引用是否合法。 */
async function savePermissions() {
  if (!roleTarget.value || saving.value) return;
  if (scope.value === "自定义部门" && !deptIds.value.length)
    return Toast.warning("请选择至少一个部门");
  saving.value = true;
  try {
    await liveApi.grant(
      roleTarget.value,
      menuIds.value,
      scope.value,
      deptIds.value,
    );
    roleTarget.value = null;
    await reload();
    await authApi.loadMe();
    Toast.success("角色权限已保存");
  } catch (e) {
    Toast.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
/** 调用后台密码重置接口，使被重置账号的旧会话失效。 */
async function resetPassword() {
  if (!resetTarget.value || saving.value) return;
  saving.value = true;
  try {
    await liveApi.reset(resetTarget.value, newPassword.value);
    resetTarget.value = null;
    newPassword.value = "";
    await loadRecords();
    Toast.success("密码已重置，原会话已失效，用户下次登录必须改密");
  } catch (e) {
    Toast.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
async function exportRecords() {
  try {
    downloadBlob(
      await liveApi.export(props.moduleKey, params()),
      config.value.title + ".xlsx",
    );
    Toast.success("已导出当前筛选结果");
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
const selectedRows = () =>
  rows.value.filter((r) => selected.value.includes(r.id));
const editable = (r: Row) => !r.builtin && r.id !== session.user?.id;
const detailLabels: Record<string, string> = {
  id: "ID",
  name: "名称",
  username: "账号",
  department: "部门",
  role: "角色",
  post: "岗位",
  phone: "手机",
  email: "邮箱",
  status: "状态",
  createdAt: "创建时间",
  version: "数据版本",
  code: "编码",
  scope: "数据范围",
  parent: "上级",
  leader: "负责人",
  remark: "备注",
  type: "类型",
  path: "路由",
  permission: "权限标识",
  sort: "排序",
};
const detailEntries = computed(() =>
  Object.entries(details.value || {}).filter(([key]) => detailLabels[key]),
);
</script>
<template>
  <div class="management-page">
    <div class="page-heading">
      <div>
        <h1>{{ config.title }}</h1>
        <p>{{ config.description }}</p>
      </div>
      <span class="page-heading-note"
        ><AppIcon :name="config.icon" :size="15" />共 {{ total }} 条记录</span
      >
      <AccountRequests
        v-if="
          moduleKey === 'users' &&
          session.user?.admin &&
          can('system:user:list')
        "
        @changed="reload"
      />
    </div>
    <div v-if="loadError" class="storage-warning" role="alert">
      {{ loadError }}
      <button class="text-button" @click="reload">重新加载</button>
    </div>
    <div
      class="management-layout"
      :class="{ 'with-tree': moduleKey === 'users' }"
    >
      <aside v-if="moduleKey === 'users'" class="panel department-panel">
        <div class="panel-heading">
          <h2>组织架构</h2>
          <AppIcon name="network" :size="17" />
        </div>
        <Input
          :value="deptQuery"
          placeholder="搜索部门"
          :onChange="(v: string) => (deptQuery = v)"
          showClear
        />
        <button
          class="department-root"
          :class="{ chosen: !department }"
          @click="chooseDepartment('')"
        >
          <AppIcon name="down" :size="13" /><AppIcon
            name="network"
            :size="17"
          />{{ session.user?.tenant.name }}
        </button>
        <button
          v-for="dept in departments"
          :key="dept.id"
          class="department-leaf"
          :class="{ chosen: department === dept.id }"
          @click="chooseDepartment(dept.id)"
        >
          <AppIcon name="folder" :size="16" /><span>{{ dept.name }}</span
          ><small>{{ dept.members }}</small>
        </button>
        <div class="tree-tip">
          <AppIcon name="help" :size="15" />仅显示当前账号可查看的部门
        </div>
      </aside>
      <section class="panel records-panel" :aria-busy="loading">
        <div :class="{ 'posts-filter-header': moduleKey === 'posts' }">
          <form class="filter-form" @submit.prevent="search">
            <label
              ><span>{{ config.singular }}名称</span
              ><Input
                :value="query"
                :placeholder="
                  moduleKey === 'users'
                    ? '账号 / 姓名 / 手机号'
                    : '请输入' + config.singular + '关键字'
                "
                :onChange="(v: string) => (query = v)"
                showClear
            /></label>
            <label
              ><span>状态</span
              ><Select
                :value="status"
                :optionList="[
                  { label: '全部状态', value: '' },
                  { label: '启用', value: '启用' },
                  { label: '停用', value: '停用' },
                ]"
                :onChange="(v: any) => (status = v)"
            /></label>
            <div class="filter-actions">
              <Button theme="solid" htmlType="submit" :aria-disabled="loading"
                ><AppIcon name="search" :size="15" />查询</Button
              ><Button
                theme="outline"
                type="tertiary"
                :onClick="reset"
                :aria-disabled="loading"
                ><AppIcon name="refresh" :size="15" />重置</Button
              >
            </div>
          </form>
          <TableDisplayTools
            v-if="moduleKey === 'posts'"
            v-model:hiddenColumns="hiddenColumns"
            :columns="config.columns"
            :refreshing="loading"
            @refresh="reload"
          />
        </div>
        <div
          v-if="
            moduleKey !== 'posts' ||
            writable('create') ||
            writable('delete') ||
            permitted('export') ||
            selected.length
          "
          class="table-toolbar"
        >
          <div class="toolbar-left">
            <Button
              v-if="writable('create')"
              theme="solid"
              :onClick="() => edit()"
              ><AppIcon name="plus" :size="17" />新增{{
                config.singular
              }}</Button
            >
            <Button
              v-if="writable('delete')"
              type="danger"
              theme="light"
              :disabled="!selected.length"
              :onClick="() => (pendingDelete = selectedRows())"
              ><AppIcon name="trash" :size="15" />批量删除</Button
            >
            <UserImport
              v-if="moduleKey === 'users' && writable('create')"
              @changed="reload"
            />
            <RoleImport
              v-if="moduleKey === 'roles' && writable('create')"
              @changed="reload"
            />
            <Button
              v-if="permitted('export')"
              theme="outline"
              type="tertiary"
              :onClick="exportRecords"
              ><AppIcon name="download" :size="15" />导出</Button
            >
            <span v-if="selected.length" class="selection-hint"
              >已选择 {{ selected.length }} 项</span
            >
          </div>
          <TableDisplayTools
            v-if="moduleKey !== 'posts'"
            v-model:hiddenColumns="hiddenColumns"
            :columns="config.columns"
            :refreshing="loading"
            @refresh="reload"
          />
        </div>
        <div class="table-scroll">
          <table class="data-table management-table">
            <thead>
              <tr>
                <th v-if="writable('delete')" class="checkbox-cell">
                  <input
                    type="checkbox"
                    :checked="allSelected"
                    aria-label="选择本页全部"
                    @change="toggleAll"
                  />
                </th>
                <th v-for="col in columns" :key="col.key">
                  <button
                    class="table-sort"
                    :disabled="!sortable(col.key)"
                    @click="sort(col.key)"
                  >
                    {{ col.label
                    }}<span
                      v-if="sortable(col.key)"
                      class="sort-indicator"
                      :class="{ sorted: sortKey === col.key }"
                      >{{
                        sortKey === col.key && direction === "desc" ? "↓" : "↑"
                      }}</span
                    >
                  </button>
                </th>
                <th class="action-column">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, index) in rows"
                :key="row.id"
                :class="{ 'row-selected': selected.includes(row.id) }"
              >
                <td v-if="writable('delete')" class="checkbox-cell">
                  <input
                    type="checkbox"
                    :checked="selected.includes(row.id)"
                    :aria-label="'选择' + row.name"
                    @change="toggleSelection(row.id)"
                  />
                </td>
                <td v-for="col in columns" :key="col.key">
                  <div v-if="col.kind === 'avatar'" class="table-person">
                    <span
                      class="avatar"
                      :class="'avatar-tone-' + (index % 5)"
                      >{{ row.name.slice(0, 1) }}</span
                    ><span>{{ row[col.key] }}</span
                    ><span v-if="row.builtin" class="mini-crown">管理</span>
                  </div>
                  <div v-else-if="col.kind === 'status'" class="status-cell">
                    <Switch
                      size="small"
                      :checked="row.status === '启用'"
                      :disabled="
                        !writable('update') ||
                        row.builtin ||
                        (moduleKey === 'users' && row.id === session.user?.id)
                      "
                      :aria-label="'切换' + row.name + '状态'"
                      :onChange="(v: boolean) => changeStatus(row, v)"
                    /><span
                      :class="row.status === '启用' ? 'text-green' : 'muted'"
                      >{{ row.status }}</span
                    >
                  </div>
                  <StatusPill
                    v-else-if="col.kind === 'tag'"
                    :value="String(row[col.key] || '—')"
                  />
                  <span v-else-if="col.key === 'createdAt'" class="cell-date">{{
                    date(row.createdAt)
                  }}</span>
                  <span
                    v-else-if="col.key === 'name' && moduleKey === 'menus'"
                    class="tree-table-name"
                    :class="{ nested: !!row.parentId }"
                    ><AppIcon
                      :name="
                        row.type === '目录'
                          ? 'folder'
                          : row.type === '按钮'
                            ? 'shield'
                            : 'file-text'
                      "
                      :size="16"
                    />{{ row.name }}</span
                  >
                  <span
                    v-else
                    :class="{
                      'cell-code': ['code', 'permission'].includes(col.key),
                    }"
                    >{{ row[col.key] ?? "—" }}</span
                  >
                </td>
                <td class="action-column">
                  <div class="row-actions">
                    <button v-if="permitted('read')" @click="showDetails(row)">
                      详情
                    </button>
                    <button
                      v-if="writable('update') && !row.builtin"
                      @click="edit(row)"
                    >
                      <AppIcon name="edit" :size="13" />编辑
                    </button>
                    <button
                      v-if="
                        moduleKey === 'roles' &&
                        writable('grant') &&
                        !row.builtin
                      "
                      @click="openPermissions(row)"
                    >
                      <AppIcon name="shield" :size="13" />权限
                    </button>
                    <button
                      v-if="
                        moduleKey === 'users' &&
                        permitted('reset-password') &&
                        editable(row)
                      "
                      @click="
                        resetTarget = row;
                        newPassword = '';
                      "
                    >
                      重置密码
                    </button>
                    <button
                      v-if="writable('delete') && !row.builtin"
                      class="danger"
                      @click="pendingDelete = [row]"
                    >
                      <AppIcon name="trash" :size="13" />删除
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="loading && !loaded && !rows.length" class="empty-state">
            <h3>正在加载数据…</h3>
          </div>
          <div v-else-if="!rows.length && !loadError" class="empty-state">
            <span><AppIcon name="search" :size="36" /></span>
            <h3>没有找到相关记录</h3>
            <p>试试调整筛选条件，或添加一条新记录</p>
            <Button theme="light" :onClick="reset">清空筛选</Button>
          </div>
        </div>
        <div class="table-pagination">
          <span>共 {{ total }} 条记录</span
          ><Pagination
            :total="total"
            :currentPage="page"
            :pageSize="pageSize"
            :pageSizeOpts="[10, 20, 50]"
            showSizeChanger
            :onPageChange="turnPage"
            :onPageSizeChange="resize"
          />
        </div>
      </section>
    </div>
    <SideSheet
      :visible="drawer"
      :title="(isNew ? '新增' : '编辑') + config.singular"
      :width="540"
      :maskClosable="false"
      :onCancel="() => (drawer = false)"
    >
      <div class="drawer-intro">
        <span class="notice-icon"
          ><AppIcon :name="config.icon" :size="24"
        /></span>
        <div>
          <strong
            >{{ isNew ? "添加新的" : "编辑" }}{{ config.singular }}</strong
          >
          <p>标有 * 的为必填项，保存后立即生效</p>
        </div>
      </div>
      <form class="editor-form" @submit.prevent="submit">
        <div
          v-for="field in fields"
          :key="field.key"
          class="form-field"
          :class="{ 'field-full': field.type === 'textarea' || field.multiple }"
        >
          <label :id="'label-' + field.key" :for="'field-' + field.key"
            ><em v-if="field.required">*</em>{{ field.label }}</label
          >
          <div
            v-if="field.key === 'roleIds'"
            class="role-picker"
            @focusout="leaveRolePicker"
            @keydown.esc="roleSuggestionsOpen = false"
          >
            <Input
              :id="'field-' + field.key"
              :aria-label="field.label"
              :value="roleInput"
              :disabled="!session.user?.admin"
              :inputStyle="{ paddingRight: '36px' }"
              placeholder="输入新角色，或选择已有角色；多个用逗号分隔"
              :onFocus="openRoleSuggestions"
              :onChange="updateRoleInput"
            />
            <button
              v-if="session.user?.admin"
              type="button"
              class="role-picker-toggle"
              aria-label="选择已有角色"
              aria-controls="existing-role-options"
              :aria-expanded="roleSuggestionsOpen"
              @mousedown.prevent
              @click="toggleRoleSuggestions"
            >
              <AppIcon name="down" :size="16" />
            </button>
            <div
              v-if="roleSuggestionsOpen && session.user?.admin"
              id="existing-role-options"
              class="role-picker-options"
              role="listbox"
              aria-label="已有角色"
            >
              <button
                v-for="role in existingRoleSuggestions"
                :key="role.id"
                type="button"
                role="option"
                :aria-selected="false"
                @mousedown.prevent
                @click="chooseExistingRole(role.name)"
              >
                {{ role.name }}
              </button>
              <p v-if="!existingRoleSuggestions.length">
                暂无匹配角色，可直接输入新名称
              </p>
            </div>
          </div>
          <Select
            v-else-if="field.type === 'select'"
            :id="'field-' + field.key"
            :aria-labelledby="'label-' + field.key"
            :aria-label="field.label"
            :multiple="field.multiple"
            :value="form[field.key]"
            :optionList="optionList(field.key)"
            :disabled="field.key === 'roleIds' && !session.user?.admin"
            :onChange="(v: any) => (form[field.key] = v)"
          />
          <TextArea
            v-else-if="field.type === 'textarea'"
            :id="'field-' + field.key"
            :value="form[field.key]"
            :rows="3"
            :onChange="(v: string) => (form[field.key] = v)"
          />
          <Input
            v-else
            :id="'field-' + field.key"
            :aria-label="field.label"
            :mode="field.password ? 'password' : undefined"
            :value="form[field.key]"
            :type="field.type === 'number' ? 'number' : 'text'"
            :disabled="field.key === 'username' && !isNew"
            :placeholder="'请输入' + field.label"
            :onChange="
              (v: string) =>
                (form[field.key] = field.type === 'number' ? Number(v) : v)
            "
          />
          <small v-if="errors[field.key]" class="field-error">{{
            errors[field.key]
          }}</small>
          <small
            v-if="field.key === 'roleIds' && session.user?.admin"
            class="muted"
          >
            已有角色会自动匹配；新角色随用户一起创建，权限请在角色管理中配置。
          </small>
          <small v-if="field.password" class="muted"
            >至少 12 位，包含字母和数字；可在个人中心修改。</small
          >
        </div>
        <div class="drawer-footer">
          <Button
            theme="outline"
            type="tertiary"
            :onClick="() => (drawer = false)"
            >取消</Button
          ><Button theme="solid" htmlType="submit" :loading="saving">{{
            isNew ? "确认创建" : "保存修改"
          }}</Button>
        </div>
      </form>
    </SideSheet>
    <Modal
      :visible="pendingDelete.length > 0"
      :title="moduleKey === 'users' ? '确认删除用户' : '确认删除'"
      :onCancel="
        () => {
          pendingDelete = [];
        }
      "
      :onOk="confirmDelete"
      okText="确认"
      :okButtonProps="{ 'aria-label': '确认', loading: saving }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消' }"
    >
      <div class="confirm-content">
        <AppIcon name="alert" :size="32" />
        <div>
          <strong>确定删除选中的 {{ pendingDelete.length }} 条记录吗？</strong>
          <p v-if="moduleKey === 'users'">
            将从数据库永久删除选中用户的个人资料、注册与找回密码申请，以及该用户的角色和岗位绑定。其他用户及角色保留，删除后该账号将无法登录，此操作无法恢复。
          </p>
          <p v-else-if="moduleKey === 'roles'">
            将删除选中角色并解除该角色的用户绑定，保留所有用户账号和其他角色。
          </p>
          <p v-else>此操作将删除数据库中的记录；有关联引用时会拒绝删除。</p>
        </div>
      </div>
    </Modal>
    <Modal
      :visible="!!details"
      title="记录详情"
      :onCancel="
        () => {
          details = null;
        }
      "
      :footer="''"
      ><dl class="detail-list">
        <template v-for="[key, value] in detailEntries" :key="key"
          ><dt>{{ detailLabels[key] }}</dt>
          <dd>
            {{ key === "createdAt" ? date(value) : (value ?? "—") }}
          </dd></template
        >
      </dl></Modal
    >
    <Modal
      :visible="!!roleTarget"
      :title="'分配权限 · ' + (roleTarget?.name || '')"
      className="role-permission-modal"
      :width="960"
      :onCancel="
        () => {
          if (!saving) roleTarget = null;
        }
      "
      :onOk="savePermissions"
      okText="保存权限"
      :okButtonProps="{ 'aria-label': '保存权限', loading: saving }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消', disabled: saving }"
      :maskClosable="false"
    >
      <RolePermissionEditor
        v-if="roleTarget"
        :key="roleTarget.id"
        v-model:menuIds="menuIds"
        v-model:scope="scope"
        v-model:deptIds="deptIds"
        :menus="options.menus"
        :departments="options.departments"
        :disabled="saving"
      />
    </Modal>
    <Modal
      :visible="!!resetTarget"
      :title="'重置密码 · ' + (resetTarget?.name || '')"
      :onCancel="
        () => {
          resetTarget = null;
          newPassword = '';
        }
      "
      :onOk="resetPassword"
      okText="重置密码"
      :okButtonProps="{ 'aria-label': '确认重置密码', loading: saving }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消' }"
    >
      <p class="modal-description">
        重置后该用户的全部会话失效，可登录后在个人中心修改密码。
      </p>
      <div class="form-field">
        <label>新临时密码</label
        ><Input
          mode="password"
          aria-label="新临时密码"
          :value="newPassword"
          :onChange="(v: string) => (newPassword = v)"
          placeholder="至少 12 位，包含字母和数字"
        />
      </div>
    </Modal>
  </div>
</template>
<style scoped>
.role-picker {
  position: relative;
}
.role-picker-toggle {
  position: absolute;
  right: 1px;
  top: 1px;
  height: 30px;
  width: 34px;
  border: 0;
  background: transparent;
  color: #7c8798;
  display: grid;
  place-items: center;
  cursor: pointer;
}
.role-picker-options {
  position: absolute;
  z-index: 10;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  max-height: 190px;
  overflow: auto;
  padding: 4px;
  border: 1px solid #e5eaf2;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 6px 20px #213b5c1a;
}
.role-picker-options button {
  display: block;
  width: 100%;
  padding: 8px 10px;
  text-align: left;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #263c5e;
  cursor: pointer;
}
.role-picker-options button:hover,
.role-picker-options button:focus-visible {
  background: #edf5ff;
  color: #1677ff;
}
.role-picker-options p {
  margin: 8px 10px;
  font-size: 12px;
  color: #8493aa;
}
.table-sort:disabled {
  cursor: default;
  color: inherit;
}
</style>
