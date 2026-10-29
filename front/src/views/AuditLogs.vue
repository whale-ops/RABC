<script setup lang="ts">
/**
 * 实现登录与操作日志的筛选、分页、详情、只读提示和 Excel 导出，并处理时间展示。
 * 关联模块：调用 api/audit.ts 对接 AOP 日志模块；session.ts 控制详情导出权限，StatusPill.vue 展示结果。
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import type { ObjectDirective } from "vue";
import { Button, Input, Select, Pagination, SideSheet, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import StatusPill from "../components/StatusPill.vue";
import { auditApi, type AuditKind, type AuditQuery, type AuditRecord } from "../api/audit";
import { can } from "../api/session";
import { errorMessage } from "../api/request";
import { downloadBlob } from "../data/store";

const props = defineProps<{ moduleKey: string }>();
const kind = computed(() => props.moduleKey as AuditKind);
const isLogin = computed(() => kind.value === "logins");
const title = computed(() => isLogin.value ? "登录日志" : "操作日志");
const permission = computed(() => isLogin.value ? "audit:login" : "audit:operation");
const form = reactive({ keyword: "", status: "", action: "", start: "", end: "" });
const applied = ref({...form});
const rows = ref<AuditRecord[]>([]), total = ref(0), page = ref(1), size = ref(10);
const loading = ref(false), exporting = ref(false), error = ref("");
// 快速响应不切换按钮图标或透明度；较慢请求在固定区域提示，避免布局闪动。
const slowLoading = ref(false);
let loadingTimer: ReturnType<typeof setTimeout> | undefined;
watch(loading, value => {
  clearTimeout(loadingTimer);
  if (!value) slowLoading.value = false;
  else loadingTimer = setTimeout(() => { slowLoading.value = true; }, 200);
});
const details = ref<AuditRecord | null>(null), detailLoading = ref(false);
const direction = ref("desc");
let sequence = 0;
let detailSequence = 0;
type FilterKey = keyof typeof form;
const filterLabels: Record<FilterKey, string> = {keyword: "关键字", status: "结果", action: "操作", start: "开始", end: "结束"};
const activeFilters = computed(() => (Object.keys(filterLabels) as FilterKey[])
  .filter(key => applied.value[key] && (key !== "action" || !isLogin.value))
  .map(key => ({key, label: filterLabels[key], text: `${filterLabels[key]}：${applied.value[key].replace('T', ' ')}${key === 'end' ? '（含整分钟）' : ''}`})));
const actions = ["新增", "修改", "删除", "移除文件记录", "修改配置", "导入", "导出", "切换状态", "分配权限", "重置密码", "修改密码", "更新资料", "注册申请", "找回申请", "审核申请", "强退会话", "取消订单", "确认收款", "订单发货", "完成订单"];
// 当前 Semi UI Vue 版本未透传带连字符的无障碍属性，因此给最终渲染控件补充标签。
const labelSelect = (element: HTMLElement, binding: {value: string}) => {
  element.querySelector('[role="combobox"]')?.setAttribute('aria-labelledby', binding.value);
};
const vSelectLabel: ObjectDirective<HTMLElement, string> = { mounted: labelSelect, updated: labelSelect };
/** 本地分钟转换为 UTC，结束时间包含整分钟及数据库微秒；分页、查询和导出使用相同边界。 */
function query(): AuditQuery {
  const end = applied.value.end ? new Date(applied.value.end) : null;
  if (end) end.setSeconds(59, 999);
  return {...applied.value, page: page.value, size: size.value, direction: direction.value,
    action: isLogin.value ? "" : applied.value.action,
    start: applied.value.start ? new Date(applied.value.start).toISOString() : "",
    end: end ? end.toISOString().replace(".999Z", ".999999Z") : ""};
}
/** 通过递增请求序号丢弃迟到响应，确保列表对应当前筛选和分页。 */
async function load() {
  const current = ++sequence;
  loading.value = true; error.value = "";
  try {
    const data = await auditApi.list(kind.value, query());
    if (current !== sequence) return;
    rows.value = data.records; total.value = data.total;
    if (!rows.value.length && page.value > 1 && data.total > 0) {
      page.value = Math.ceil(data.total / size.value); await load();
    }
  } catch (e) {
    if (current === sequence) { error.value = errorMessage(e); rows.value = []; total.value = 0; }
  } finally { if (current === sequence) loading.value = false; }
}
/** 先检查时间顺序，再固定本次筛选；输入框继续编辑不会影响尚未重新查询的导出条件。 */
function search() {
  if (form.start && form.end && new Date(form.start) > new Date(form.end)) {
    Toast.warning("开始时间不能晚于结束时间"); return;
  }
  applied.value = {...form, keyword: form.keyword.trim()}; page.value = 1; void load();
}
function reset() {
  Object.assign(form, {keyword: "", status: "", action: "", start: "", end: ""});
  direction.value = "desc"; search();
}
/** 单独清除一个已应用条件，其他筛选保持不变；任意单项或多项均可查询。 */
function removeFilter(key: FilterKey) {
  form[key] = "";
  applied.value = {...applied.value, [key]: ""};
  page.value = 1;
  void load();
}
async function showDetails(row: AuditRecord) {
  if (detailLoading.value) return;
  detailLoading.value = true;
  const current = ++detailSequence;
  try {
    const result = await auditApi.detail(kind.value, row.id);
    if (current === detailSequence) details.value = result;
  }
  catch (e) { if (current === detailSequence) Toast.error(errorMessage(e)); }
  finally { if (current === detailSequence) detailLoading.value = false; }
}
/** 按已应用的筛选下载后台生成的 XLSX，不导出本地演示记录。 */
async function exportLogs() {
  if (exporting.value) return;
  exporting.value = true;
  try { downloadBlob(await auditApi.export(kind.value, query()), `${title.value}.xlsx`); Toast.success("导出成功"); }
  catch (e) { Toast.error(errorMessage(e)); }
  finally { exporting.value = false; }
}
const date = (value: string) => value ? new Date(value).toLocaleString("zh-CN", {hour12: false}) : "—";
const detailFields = computed(() => details.value ? [
  ["日志编号", details.value.id], ["所属租户", details.value.tenantCode], ["用户账号", details.value.username || "匿名"],
  ["操作人", details.value.operator || "—"], ["所属模块", details.value.module], ["操作类型", details.value.action],
  ["结果", details.value.status], ["记录时间", date(details.value.createdAt)], ["IP 地址", details.value.ip || "—"],
  ["浏览器 / 系统", `${details.value.browser} / ${details.value.os}`], ["请求方法", details.value.requestMethod],
  ["请求路径", details.value.requestPath], ["HTTP 状态码", String(details.value.httpStatus)],
  ["耗时", `${details.value.durationMs} ms`], ["错误码", details.value.errorCode || "无"],
  ["请求编号", details.value.traceId || "—"], ["客户端标识", details.value.userAgent || "—"],
] : []);
// 路由复用同一组件时重新查询对应日志，清空旧操作类型，防止隐藏条件筛掉全部登录记录。
watch(kind, () => {
  ++sequence; ++detailSequence;
  rows.value = []; total.value = 0; details.value = null; detailLoading.value = false;
  reset();
}, { immediate: true });
onBeforeUnmount(() => { ++sequence; ++detailSequence; clearTimeout(loadingTimer); });
</script>

<template>
  <div class="audit-page">
    <div class="page-heading">
      <div><h1>{{ title }}</h1><p>{{ isLogin ? '查看账号登录活动，及时发现异常访问' : '追踪系统操作及执行结果，让每次变更都有据可查' }}</p></div>
      <span class="page-heading-note"><AppIcon :name="isLogin ? 'log-in' : 'file-text'" :size="16" />共 {{ total }} 条记录</span>
    </div>
    <section class="panel records-panel" :aria-busy="loading">
      <form class="audit-filters" :class="{ 'audit-login-filters': isLogin }" @submit.prevent="search">
        <label class="audit-keyword"><span>关键字</span><Input aria-label="日志关键字" :value="form.keyword" :maxLength="100" placeholder="账号 / 姓名 / 模块 / IP / 请求编号" :onChange="(value: string) => form.keyword = value" showClear /></label>
        <div class="audit-filter-field" v-select-label="'audit-result-label'"><span id="audit-result-label">结果</span><Select :value="form.status" :optionList="[{label:'全部结果',value:''},{label:'成功',value:'成功'},{label:'失败',value:'失败'}]" :onChange="(value: any) => form.status = value || ''" /></div>
        <div v-if="!isLogin" class="audit-filter-field" v-select-label="'audit-action-label'"><span id="audit-action-label">操作类型</span><Select :value="form.action" :optionList="[{label:'全部操作',value:''},...actions.map(value=>({label:value,value}))]" :onChange="(value: any) => form.action = value || ''" /></div>
        <label><span>开始时间</span><input v-model="form.start" aria-label="开始时间" type="datetime-local" /></label>
        <label><span>结束时间</span><input v-model="form.end" aria-label="结束时间" type="datetime-local" /></label>
        <div class="filter-actions"><Button htmlType="submit" theme="solid"><AppIcon name="search" :size="15" />查询</Button><Button theme="outline" type="tertiary" :onClick="reset"><AppIcon name="refresh" :size="15" />重置</Button></div>
      </form>
      <p class="audit-filter-help">所有条件均为选填，填写任意一项即可查询；填写多项时组合筛选。结束时间包含所选整分钟。</p>
      <div v-if="activeFilters.length" class="audit-active-filters" aria-label="已应用的筛选条件">
        <span>当前筛选：</span><span v-for="item in activeFilters" :key="item.key" class="audit-filter-tag">{{ item.text }}<button type="button" :aria-label="'清除' + item.label + '筛选'" @click="removeFilter(item.key)"><AppIcon name="close" :size="12" /></button></span>
        <button class="text-button" @click="reset">清空筛选</button>
      </div>
      <div class="table-toolbar">
        <div class="toolbar-left"><Button v-if="can(permission + ':export')" theme="outline" type="tertiary" :onClick="exportLogs"><AppIcon name="download" :size="16" />导出</Button><span class="audit-readonly"><AppIcon name="lock" :size="13" />日志只读</span></div>
        <div class="audit-loading-tools"><span class="audit-loading-status" role="status" aria-live="polite">{{ slowLoading ? '正在更新日志…' : exporting ? '正在导出…' : detailLoading ? '正在读取详情…' : '' }}</span><button class="icon-button table-icon" aria-label="刷新日志" :aria-disabled="loading" @click="!loading && load()"><AppIcon name="refresh" :size="17" /></button></div>
      </div>
      <div v-if="error" class="storage-warning" role="alert">{{ error }} <button class="text-button" @click="load">重新加载</button></div>
      <div class="table-scroll">
        <table class="data-table audit-table">
          <thead><tr>
            <th>用户账号</th><th v-if="!isLogin">操作人</th><th v-if="!isLogin">所属模块</th><th v-if="!isLogin">操作类型</th>
            <th>IP 地址</th><th v-if="isLogin">浏览器 / 系统</th><th>结果</th><th>耗时</th>
            <th><button class="audit-sort" @click="direction = direction === 'desc' ? 'asc' : 'desc'; page = 1; load()">{{ isLogin ? '登录时间' : '操作时间' }}<AppIcon :name="direction === 'desc' ? 'down' : 'up'" :size="13" /></button></th>
            <th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td><span class="audit-account"><span class="avatar small-avatar">{{ (row.operator || row.username || '访').slice(0,1) }}</span>{{ row.username || '匿名' }}</span></td>
              <td v-if="!isLogin">{{ row.operator || '—' }}</td><td v-if="!isLogin">{{ row.module }}</td><td v-if="!isLogin"><StatusPill :value="row.action" /></td>
              <td class="cell-code">{{ row.ip || '—' }}</td><td v-if="isLogin">{{ row.browser }} / {{ row.os }}</td>
              <td><StatusPill :value="row.status" :dot="true" /><small v-if="row.errorCode" class="audit-error-code">{{ row.errorCode }}</small></td>
              <td>{{ row.durationMs }} <span class="muted">ms</span></td><td class="cell-date">{{ date(row.createdAt) }}</td>
              <td><button v-if="can(permission + ':read')" class="text-button" :aria-disabled="detailLoading" @click="showDetails(row)">详情</button><span v-else class="muted">—</span></td>
            </tr>
            <tr v-if="!rows.length"><td :colspan="isLogin ? 7 : 9" class="audit-empty"><AppIcon :name="loading ? 'refresh' : 'file-text'" :size="30" /><p>{{ loading ? '正在加载日志…' : error ? '日志加载失败，请重试' : activeFilters.length ? '暂无符合条件的日志' : '暂无日志记录' }}</p>
              <template v-if="!loading && !error && activeFilters.length"><small>所有筛选条件需同时满足，可清空条件查看全部记录。</small><div><button class="text-button" @click="reset">查看全部日志</button></div></template>
            </td></tr>
          </tbody>
        </table>
      </div>
      <div class="table-pagination"><span>共 {{ total }} 条记录</span><Pagination :currentPage="page" :pageSize="size" :total="total" showSizeChanger :pageSizeOpts="[10,20,50,100]" :onPageChange="(value: number) => { page = value; load(); }" :onPageSizeChange="(value: number) => { size = value; page = 1; load(); }" /></div>
    </section>
    <SideSheet :visible="!!details" :title="title + '详情'" :width="620" :onCancel="() => details = null">
      <div class="audit-detail"><div class="audit-detail-result"><StatusPill v-if="details" :value="details.status" :dot="true" /><span>日志不保存密码或令牌</span></div>
        <dl><div v-for="[label,value] in detailFields" :key="label"><dt>{{ label }}</dt><dd>{{ value }}</dd></div></dl>
      </div>
    </SideSheet>
  </div>
</template>
