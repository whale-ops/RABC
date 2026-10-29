<script setup lang="ts">
/**
 * 实现原型通用管理表格、编辑抽屉及授权交互，主要用于尚未接入后台的演示模块。
 * 关联模块：catalog.ts 提供字段配置，system.ts 和 mock.ts 操作 store.ts；真实五类系统管理使用 SystemManagement.vue。
 */
import { ref, reactive, computed, watch, nextTick, onMounted } from "vue";
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
import StatusPill from "../components/StatusPill.vue";
import { modules } from "../data/catalog";
import { db, exportCsv, type Row } from "../data/store";
import { systemApi } from "../api/system";
import { errorMessage } from "../api/request";
const props = defineProps<{ moduleKey: string }>();
const route = useRoute();
const router = useRouter();
const config = computed(() => modules[props.moduleKey]!);
const query = ref("");
const keyword = ref("");
const status = ref("");
const department = ref("");
const deptQuery = ref("");
const page = ref(1);
const pageSize = ref(10);
const selected = ref<string[]>([]);
const sortKey = ref("");
const sortDirection = ref(1);
const hiddenColumns = ref<string[]>([]);
const refreshing = ref(false);
const dateFrom = ref("");
const dateTo = ref("");
const drawer = ref(false);
const isNew = ref(true);
const form = reactive<Row>({ id: "" });
const errors = ref<Record<string, string>>({});
const pendingDelete = ref<string[]>([]);
const details = ref<Row | null>(null);
const roleTarget = ref<Row | null>(null);
const permissions = ref<string[]>([]);
const scope = ref("全部数据");
const customDepts = ref<string[]>([]);
const saving = ref(false);
const loadError = ref("");
const permissionGroups = [
  {
    name: "系统管理",
    items: ["用户管理", "角色管理", "菜单管理", "部门管理", "岗位管理"],
  },
  {
    name: "业务管理",
    items: ["订单管理", "产品管理"],
  },
  {
    name: "运维管理",
    items: ["图片管理", "附件管理", "操作日志", "登录日志", "系统监控"],
  },
];
const columns = computed(() =>
  config.value.columns.filter((c) => !hiddenColumns.value.includes(c.key)),
);
const filtered = computed(() => {
  let rows = db[props.moduleKey].filter(
    (r) =>
      (!keyword.value ||
        Object.values(r).some(
          (v) =>
            typeof v !== "object" &&
            String(v).toLowerCase().includes(keyword.value.toLowerCase()),
        )) &&
      (!status.value || r.status === status.value) &&
      (!department.value || r.department === department.value) &&
      (!dateFrom.value || r.createdAt?.slice(0, 10) >= dateFrom.value) &&
      (!dateTo.value || r.createdAt?.slice(0, 10) <= dateTo.value),
  );
  if (sortKey.value)
    rows = [...rows].sort(
      (a, b) =>
        String(a[sortKey.value] ?? "").localeCompare(
          String(b[sortKey.value] ?? ""),
          "zh-CN",
          { numeric: true },
        ) * sortDirection.value,
    );
  return rows;
});
const paged = computed(() =>
  filtered.value.slice(
    (page.value - 1) * pageSize.value,
    page.value * pageSize.value,
  ),
);
const allSelected = computed(
  () =>
    paged.value.length > 0 &&
    paged.value.every((r) => selected.value.includes(r.id)),
);
const departments = computed(() =>
  db.departments.filter((r) => r.name.includes(deptQuery.value)),
);
const statusOptions = computed(() =>
  [...new Set(db[props.moduleKey].map((r) => r.status).filter(Boolean))].map(
    (v) => ({ label: v, value: v }),
  ),
);
watch([keyword, status, department, dateFrom, dateTo, pageSize], () => {
  page.value = 1;
  selected.value = [];
});
watch(
  () => filtered.value.length,
  (n) => {
    page.value = Math.min(
      page.value,
      Math.max(1, Math.ceil(n / pageSize.value)),
    );
  },
);
async function loadRecords() {
  try {
    const result = await systemApi.list(props.moduleKey);
    db[props.moduleKey] = result.records;
    loadError.value = "";
  } catch (error) {
    loadError.value = errorMessage(error);
    Toast.error(loadError.value);
  }
}
onMounted(loadRecords);
async function search() {
  keyword.value = query.value.trim();
  page.value = 1;
  await loadRecords();
}
function reset() {
  query.value = "";
  keyword.value = "";
  status.value = "";
  department.value = "";
  dateFrom.value = "";
  dateTo.value = "";
  page.value = 1;
}
function exportRecords() {
  exportCsv(filtered.value, config.value.columns, config.value.title);
  Toast.success("已导出当前筛选结果");
}
function toggleSelection(id: string) {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((v) => v !== id)
    : [...selected.value, id];
}
function toggleAll() {
  selected.value = allSelected.value
    ? selected.value.filter((v) => !paged.value.some((r) => r.id === v))
    : [...new Set([...selected.value, ...paged.value.map((r) => r.id)])];
}
function sort(key: string) {
  sortDirection.value = sortKey.value === key ? -sortDirection.value : 1;
  sortKey.value = key;
}
function edit(row?: Row) {
  isNew.value = !row;
  errors.value = {};
  Object.keys(form).forEach((k) => delete form[k]);
  Object.assign(
    form,
    row
      ? JSON.parse(JSON.stringify(row))
      : {
          id: "",
          status: props.moduleKey === "projects" ? "待开始" : "启用",
          sort: db[props.moduleKey].length + 1,
          members: 0,
          progress: 0,
          count: 0,
        },
  );
  for (const f of config.value.fields)
    if (!form[f.key])
      form[f.key] =
        f.type === "select" ? f.options?.[0] : f.type === "number" ? 0 : "";
  if (!row && props.moduleKey === "users") form.role = "普通用户";
  drawer.value = true;
}
function validate() {
  const e: Record<string, string> = {};
  for (const field of config.value.fields)
    if (field.required && !String(form[field.key] ?? "").trim())
      e[field.key] = `请输入${field.label}`;
  for (const key of ["username", "code"])
    if (
      form[key] &&
      db[props.moduleKey].some((r) => r.id !== form.id && r[key] === form[key])
    )
      e[key] = "该标识已存在，请使用其他值";
  if (
    form.phone &&
    props.moduleKey === "users" &&
    !/^1\d{10}$/.test(form.phone)
  )
    e.phone = "请输入正确的 11 位手机号码";
  if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email))
    e.email = "请输入正确的邮箱地址";
  if (
    props.moduleKey === "projects" &&
    (Number(form.progress) < 0 || Number(form.progress) > 100)
  )
    e.progress = "进度应在 0–100 之间";
  if (form.deadline && !/^\d{4}-\d{2}-\d{2}$/.test(form.deadline))
    e.deadline = "日期格式为 YYYY-MM-DD";
  if (
    ["menus", "departments"].includes(props.moduleKey) &&
    form.parent === form.name
  )
    e.parent = "上级不能是自身";
  errors.value = e;
  return !Object.keys(e).length;
}
async function submit() {
  if (saving.value || !validate()) return;
  saving.value = true;
  try {
    await systemApi.save(props.moduleKey, { ...form }, isNew.value);
    await loadRecords();
    drawer.value = false;
    Toast.success(
      `${config.value.singular}${isNew.value ? "创建" : "更新"}成功`,
    );
    if (route.query.create) router.replace({ path: route.path });
  } catch (error) {
    Toast.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}
function requestDelete(ids: string[]) {
  if (
    (props.moduleKey === "users" && ids.includes("1001")) ||
    (props.moduleKey === "roles" && ids.includes("1"))
  ) {
    Toast.warning("系统内置管理员不能删除");
    return;
  }
  if (
    props.moduleKey === "departments" &&
    db.users.some((r) =>
      ids.some(
        (id) => db.departments.find((d) => d.id === id)?.name === r.department,
      ),
    )
  ) {
    Toast.warning("部门下仍有成员，请先调整成员所属部门");
    return;
  }
  if (
    props.moduleKey === "menus" &&
    db.menus.some((r) =>
      ids.some((id) => db.menus.find((d) => d.id === id)?.name === r.parent),
    )
  ) {
    Toast.warning("请先删除下级菜单");
    return;
  }
  pendingDelete.value = ids;
}
async function confirmDelete() {
  try {
    await systemApi.remove(props.moduleKey, [...pendingDelete.value]);
    await loadRecords();
    selected.value = [];
    pendingDelete.value = [];
    Toast.success(props.moduleKey === "sessions" ? "会话已下线" : "删除成功");
  } catch (error) {
    Toast.error(errorMessage(error));
  }
}
async function changeStatus(row: Row, checked: boolean) {
  if (props.moduleKey === "users" && row.id === "1001") {
    Toast.warning("当前管理员账号不能停用");
    return;
  }
  try {
    await systemApi.status(props.moduleKey, row.id, checked ? "启用" : "停用");
    await loadRecords();
    Toast.success("状态已更新");
  } catch (error) {
    Toast.error(errorMessage(error));
  }
}
function openPermissions(row: Row) {
  roleTarget.value = row;
  permissions.value = [...(row.permissions || [])];
  scope.value = row.scope;
  customDepts.value = [...(row.customDepts || [])];
}
async function savePermissions() {
  if (scope.value === "自定义部门" && !customDepts.value.length) {
    Toast.warning("请选择至少一个部门");
    return;
  }
  if (!roleTarget.value) return;
  try {
    await systemApi.permissions(roleTarget.value.id, {
      permissions: [...permissions.value],
      scope: scope.value,
      customDepts: [...customDepts.value],
    });
    await loadRecords();
    roleTarget.value = null;
    Toast.success("角色权限已保存");
  } catch (error) {
    Toast.error(errorMessage(error));
  }
}
async function refresh() {
  refreshing.value = true;
  await loadRecords();
  refreshing.value = false;
  if (!loadError.value) Toast.success("列表已刷新");
}
function detailLabel(key: string) {
  const labels: Record<string, string> = {
    duration: "耗时",
    method: "请求方法",
    url: "请求地址",
  };
  return (
    config.value.columns.find((c) => c.key === key)?.label || labels[key] || key
  );
}
watch(
  () => route.query.create,
  async (v) => {
    if (v === "1") {
      await nextTick();
      edit();
    }
  },
  { immediate: true },
);
</script>
<template>
  <div class="management-page">
    <div v-if="loadError" class="storage-warning" role="alert">
      {{ loadError }}。请使用刷新按钮重试。
    </div>
    <div class="page-heading">
      <div>
        <h1>{{ config.title }}</h1>
        <p>{{ config.description }}</p>
      </div>
      <span class="page-heading-note"
        ><AppIcon :name="config.icon" :size="15" />{{
          moduleKey === "sessions" ? "当前在线 " : "共 "
        }}{{ db[moduleKey].length.toLocaleString() }}
        {{ moduleKey === "users" ? "位成员" : "条记录" }}</span
      >
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
        /><button
          class="department-root"
          :class="{ chosen: !department }"
          @click="department = ''"
        >
          <AppIcon name="down" :size="13" /><AppIcon
            name="network"
            :size="17"
          />Star 科技有限公司</button
        ><button
          v-for="dept in departments"
          :key="dept.id"
          class="department-leaf"
          :class="{ chosen: department === dept.name }"
          @click="department = dept.name"
        >
          <AppIcon name="folder" :size="16" /><span>{{ dept.name }}</span
          ><small>{{
            db.users.filter((u) => u.department === dept.name).length
          }}</small>
        </button>
        <div class="tree-tip">
          <AppIcon name="help" :size="15" />点击部门查看对应成员
        </div>
      </aside>
      <section class="panel records-panel">
        <div :class="{ 'posts-filter-header': moduleKey === 'posts' }">
          <form class="filter-form" @submit.prevent="search">
            <label
              ><span>{{
                config.readonly ? "关键字" : config.singular + "名称"
              }}</span
              ><Input
                :value="query"
                :placeholder="
                  moduleKey === 'users'
                    ? '账号 / 姓名 / 手机号'
                    : '请输入' + config.singular + '关键字'
                "
                showClear
                :onChange="(v: string) => (query = v)"
                :onEnterPress="search" /></label
            ><label v-if="statusOptions.length"
              ><span>{{ config.readonly ? "操作结果" : "状态" }}</span
              ><Select
                :value="status"
                placeholder="全部状态"
                :optionList="[
                  { label: '全部状态', value: '' },
                  ...statusOptions,
                ]"
                :onChange="(v: any) => (status = v)" /></label
            ><template v-if="config.readonly"
              ><label class="date-filter"
                ><span>开始日期</span
                ><input v-model="dateFrom" type="date" /></label
              ><label class="date-filter"
                ><span>结束日期</span
                ><input v-model="dateTo" type="date" :min="dateFrom" /></label
            ></template>
            <div class="filter-actions">
              <Button theme="solid" type="primary" htmlType="submit"
                ><AppIcon name="search" :size="15" />查询</Button
              ><Button theme="outline" type="tertiary" :onClick="reset"
                ><AppIcon name="refresh" :size="15" />重置</Button
              >
            </div>
          </form>
          <TableDisplayTools
            v-if="moduleKey === 'posts'"
            :refreshing="refreshing"
            v-model:hiddenColumns="hiddenColumns"
            :columns="config.columns"
            @refresh="refresh"
          />
        </div>
        <div class="table-toolbar">
          <div class="toolbar-left">
            <Button
              v-if="!config.readonly"
              theme="solid"
              type="primary"
              :onClick="() => edit()"
              ><AppIcon name="plus" :size="17" />新增{{
                config.singular
              }}</Button
            ><Button
              v-if="!config.readonly"
              type="danger"
              theme="light"
              :disabled="!selected.length"
              :onClick="() => requestDelete(selected)"
              ><AppIcon name="trash" :size="15" />批量删除</Button
            ><Button theme="outline" type="tertiary" :onClick="exportRecords"
              ><AppIcon name="download" :size="15" />导出</Button
            ><span v-if="selected.length" class="selection-hint"
              >已选择 {{ selected.length }} 项</span
            >
          </div>
          <TableDisplayTools
            v-if="moduleKey !== 'posts'"
            :refreshing="refreshing"
            v-model:hiddenColumns="hiddenColumns"
            :columns="config.columns"
            @refresh="refresh"
          />
        </div>
        <div class="table-scroll">
          <table class="data-table management-table">
            <thead>
              <tr>
                <th v-if="!config.readonly" class="checkbox-cell">
                  <input
                    type="checkbox"
                    :checked="allSelected"
                    aria-label="选择本页全部"
                    @change="toggleAll"
                  />
                </th>
                <th
                  v-for="col in columns"
                  :key="col.key"
                  :aria-sort="
                    sortKey === col.key
                      ? sortDirection === 1
                        ? 'ascending'
                        : 'descending'
                      : 'none'
                  "
                >
                  <button class="table-sort" @click="sort(col.key)">
                    {{ col.label
                    }}<span
                      class="sort-indicator"
                      :class="{ sorted: sortKey === col.key }"
                      >{{
                        sortKey === col.key && sortDirection === -1 ? "↓" : "↑"
                      }}</span
                    >
                  </button>
                </th>
                <th class="action-column">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, index) in paged"
                :key="row.id"
                :class="{ 'row-selected': selected.includes(row.id) }"
              >
                <td v-if="!config.readonly" class="checkbox-cell">
                  <input
                    type="checkbox"
                    :checked="selected.includes(row.id)"
                    :aria-label="'选择' + (row.name || row.username)"
                    @change="toggleSelection(row.id)"
                  />
                </td>
                <td v-for="col in columns" :key="col.key">
                  <template v-if="col.kind === 'avatar'"
                    ><div class="table-person">
                      <span
                        class="avatar"
                        :class="'avatar-tone-' + (index % 5)"
                        >{{ (row.name || row.username).slice(0, 1) }}</span
                      ><span>{{ row[col.key] }}</span
                      ><span v-if="row.username === 'admin'" class="mini-crown"
                        >管理</span
                      >
                    </div></template
                  ><template v-else-if="col.kind === 'status'"
                    ><div
                      v-if="['启用', '停用'].includes(row[col.key])"
                      class="status-cell"
                    >
                      <Switch
                        size="small"
                        :checked="row.status === '启用'"
                        :onChange="
                          (checked: boolean) => changeStatus(row, checked)
                        "
                        :aria-label="'切换' + row.name + '状态'"
                      /><span
                        :class="row.status === '启用' ? 'text-green' : 'muted'"
                        >{{ row.status }}</span
                      >
                    </div>
                    <StatusPill v-else :value="row[col.key]" dot /></template
                  ><StatusPill
                    v-else-if="col.kind === 'tag'"
                    :value="String(row[col.key] ?? '—')"
                  />
                  <div
                    v-else-if="col.kind === 'progress'"
                    class="inline-progress"
                  >
                    <div>
                      <i
                        :style="{ width: row.progress + '%' }"
                        :class="{ complete: row.progress === 100 }"
                      />
                    </div>
                    <span>{{ row.progress }}%</span>
                  </div>
                  <template
                    v-else-if="col.key === 'name' && moduleKey === 'menus'"
                    ><span
                      class="tree-table-name"
                      :class="{ nested: row.parent !== '顶级菜单' }"
                      ><AppIcon
                        :name="row.type === '目录' ? 'folder' : 'file-text'"
                        :size="16"
                      />{{ row.name }}</span
                    ></template
                  ><template v-else
                    ><span
                      :class="{
                        'cell-code':
                          col.key === 'code' || col.key === 'permission',
                        'cell-date': col.key === 'createdAt',
                      }"
                      >{{ row[col.key] ?? "—" }}</span
                    ></template
                  >
                </td>
                <td class="action-column">
                  <div class="row-actions">
                    <template v-if="config.readonly"
                      ><button @click="details = row">详情</button
                      ><button
                        v-if="moduleKey === 'sessions'"
                        class="danger"
                        @click="requestDelete([row.id])"
                      >
                        强退
                      </button></template
                    ><template v-else
                      ><button @click="edit(row)">
                        <AppIcon name="edit" :size="13" />编辑</button
                      ><button
                        v-if="moduleKey === 'roles'"
                        @click="openPermissions(row)"
                      >
                        <AppIcon name="shield" :size="13" />权限</button
                      ><button class="danger" @click="requestDelete([row.id])">
                        <AppIcon name="trash" :size="13" />删除
                      </button></template
                    >
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="!paged.length" class="empty-state">
            <span><AppIcon name="search" :size="36" /></span>
            <h3>没有找到相关记录</h3>
            <p>试试调整筛选条件，或添加一条新记录</p>
            <Button theme="light" :onClick="reset">清空筛选</Button>
          </div>
        </div>
        <div class="table-pagination">
          <span>共 {{ filtered.length.toLocaleString() }} 条记录</span
          ><Pagination
            :total="filtered.length"
            :currentPage="page"
            :pageSize="pageSize"
            :pageSizeOpts="[10, 20, 50]"
            showSizeChanger
            :onPageChange="(p: number) => (page = p)"
            :onPageSizeChange="(size: number) => (pageSize = size)"
          />
        </div>
      </section>
    </div>
    <SideSheet
      :visible="drawer"
      :title="`${isNew ? '新增' : '编辑'}${config.singular}`"
      :width="520"
      :onCancel="
        () => {
          drawer = false;
        }
      "
      :maskClosable="false"
      ><div class="drawer-intro">
        <span class="notice-icon"
          ><AppIcon :name="config.icon" :size="24"
        /></span>
        <div>
          <strong
            >{{ isNew ? "添加新的" : "编辑" }}{{ config.singular }}</strong
          >
          <p>请完善以下信息，标有 * 的为必填项</p>
        </div>
      </div>
      <form class="editor-form" @submit.prevent="submit">
        <div
          v-for="field in config.fields"
          :key="field.key"
          class="form-field"
          :class="{ 'field-full': field.type === 'textarea' }"
        >
          <label :for="'field-' + field.key"
            ><em v-if="field.required">*</em>{{ field.label }}</label
          ><Select
            v-if="field.type === 'select'"
            :id="'field-' + field.key"
            :aria-label="field.label"
            :value="form[field.key]"
            :optionList="field.options?.map((v) => ({ label: v, value: v }))"
            :onChange="(v: any) => (form[field.key] = v)"
          /><TextArea
            v-else-if="field.type === 'textarea'"
            :id="'field-' + field.key"
            :value="form[field.key]"
            :rows="3"
            placeholder="请输入备注信息"
            :onChange="(v: string) => (form[field.key] = v)"
          /><Input
            v-else
            :id="'field-' + field.key"
            :aria-label="field.label"
            :value="form[field.key]"
            :type="field.type === 'number' ? 'number' : 'text'"
            :placeholder="
              field.key === 'deadline' ? 'YYYY-MM-DD' : '请输入' + field.label
            "
            :validateStatus="errors[field.key] ? 'error' : 'default'"
            :onChange="
              (v: string) =>
                (form[field.key] = field.type === 'number' ? Number(v) : v)
            "
          /><small v-if="errors[field.key]" class="field-error">{{
            errors[field.key]
          }}</small>
        </div>
        <div class="drawer-footer">
          <Button
            type="tertiary"
            theme="outline"
            :onClick="() => (drawer = false)"
            >取消</Button
          ><Button theme="solid" htmlType="submit" :loading="saving">{{
            isNew ? "确认创建" : "保存修改"
          }}</Button>
        </div>
      </form></SideSheet
    >
    <Modal
      :visible="pendingDelete.length > 0"
      :title="
        moduleKey === 'sessions'
          ? '确认强制下线'
          : moduleKey === 'users'
            ? '确认删除用户'
            : '确认删除'
      "
      :onCancel="
        () => {
          pendingDelete = [];
        }
      "
      :onOk="confirmDelete"
      okText="确认"
      :okButtonProps="{ 'aria-label': '确认' }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      ><div class="confirm-content">
        <AppIcon name="alert" :size="32" />
        <div>
          <strong
            >确定{{ moduleKey === "sessions" ? "下线" : "删除" }}选中的
            {{ pendingDelete.length }} 条记录吗？</strong
          >
          <p v-if="moduleKey === 'users'">
            将永久删除选中用户的个人资料及其角色和岗位绑定，其他用户及角色保留，此操作无法恢复。
          </p>
          <p v-else-if="moduleKey === 'roles'">
            将删除选中角色并解除该角色的用户绑定，保留所有用户账号和其他角色。
          </p>
          <p v-else>此操作将更新当前浏览器保存的数据。</p>
        </div>
      </div></Modal
    >
    <Modal
      :visible="!!details"
      title="记录详情"
      :onCancel="
        () => {
          details = null;
        }
      "
      :footer="''"
      ><dl v-if="details" class="detail-list">
        <template v-for="(value, key) in details" :key="key"
          ><dt>{{ detailLabel(String(key)) }}</dt>
          <dd>{{ value }}</dd></template
        >
      </dl></Modal
    >
    <Modal
      :visible="!!roleTarget"
      :title="'分配权限 · ' + (roleTarget?.name || '')"
      :width="640"
      :onCancel="
        () => {
          roleTarget = null;
        }
      "
      :onOk="savePermissions"
      okText="保存权限"
      :okButtonProps="{ 'aria-label': '保存权限' }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      ><p class="modal-description">
        勾选该角色可以访问的功能模块，并设置数据可见范围。
      </p>
      <div class="permission-tree">
        <section v-for="g in permissionGroups" :key="g.name">
          <strong><AppIcon name="folder" :size="16" />{{ g.name }}</strong>
          <div>
            <label v-for="p in g.items" :key="p"
              ><input v-model="permissions" type="checkbox" :value="p" />{{
                p
              }}</label
            >
          </div>
        </section>
      </div>
      <div class="form-field scope-field">
        <label>数据权限范围</label
        ><Select
          :value="scope"
          :optionList="
            ['全部数据', '本部门及下级', '本部门', '自定义部门', '仅本人'].map(
              (v) => ({ label: v, value: v }),
            )
          "
          :onChange="(v: any) => (scope = v)"
        />
      </div>
      <div v-if="scope === '自定义部门'" class="department-checks">
        <label v-for="d in db.departments" :key="d.id"
          ><input v-model="customDepts" type="checkbox" :value="d.name" />{{
            d.name
          }}</label
        >
      </div></Modal
    >
  </div>
</template>
