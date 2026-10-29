<script setup lang="ts">
/**
 * 展示账号申请数量和审核列表，支持注册审批及管理员协助重置密码。
 * 关联模块：在 SystemManagement.vue 中使用；查询 AccountRequestController，读取真实部门角色选项并在处理后通知父页面刷新。
 */
import { ref, reactive, computed, onMounted, onUnmounted } from "vue";
import { Button, SideSheet, Select, Input, TextArea, Pagination, Toast } from "../ui";
import { request, errorMessage } from "../api/request";
import { can } from "../api/session";
import { liveApi, type Options } from "../api/system-live";
type Entry = { id: string; kind: string; username: string; name: string; email: string; phone: string; status: string; note: string; createdAt: string };
const emit = defineEmits<{changed: []}>();
const visible = ref(false), loading = ref(false), saving = ref(false), error = ref("");
const rows = ref<Entry[]>([]), total = ref(0), page = ref(1), status = ref("pending"), selected = ref<Entry|null>(null);
const pendingCount = ref<number | null>(null), countError = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;
/** 定期及页面重新可见时刷新待处理数量，不把重复申请当作已创建用户。 */
async function refreshCount() {
  if (document.hidden) return;
  try {
    const result = await request<{total: number}>({url: "/system/account-requests", params: {page: 1, size: 1, status: "pending"}});
    if (pendingCount.value !== null && pendingCount.value !== result.total && visible.value && !selected.value && status.value === 'pending') await load();
    pendingCount.value = result.total; countError.value = false;
  } catch {countError.value = true;}
}
onMounted(() => {refreshCount(); timer = setInterval(refreshCount, 15000); window.addEventListener("focus", refreshCount); document.addEventListener("visibilitychange", refreshCount);});
onUnmounted(() => {clearInterval(timer); window.removeEventListener("focus", refreshCount); document.removeEventListener("visibilitychange", refreshCount);});
const options = reactive<Options>({departments: [], roles: [], posts: [], menus: []});
const form = reactive({decision: "approve", deptId: "", roleIds: [] as string[], newPassword: "", note: ""});
const statuses = {pending: "待处理", approved: "已通过", rejected: "已拒绝"} as Record<string,string>;
const allowed = computed(() => selected.value?.kind === "register" ? can("system:user:create") && can("system:user:assign-role") : can("system:user:reset-password"));
async function load() {
  loading.value = true; error.value = "";
  try {const result = await request<{records: Entry[]; total: number}>({url: "/system/account-requests", params: {page: page.value, size: 10, status: status.value}}); rows.value = result.records; total.value = result.total;}
  catch(e) {error.value = errorMessage(e);} finally {loading.value = false;}
}
async function open() {
  visible.value = true; selected.value = null; status.value = "pending"; page.value = 1;
  refreshCount();
  await load();
  try {Object.assign(options, await liveApi.options());} catch(e) {error.value = errorMessage(e);}
}
function select(row: Entry) {
  selected.value = row; error.value = "";
  Object.assign(form, {decision: "approve", deptId: "", roleIds: [], newPassword: "", note: ""});
}
/** 提交审核结果后刷新队列，并通知用户管理页面重新读取真实数据。 */
async function review() {
  if (!selected.value || saving.value) return;
  if (form.decision === "approve" && !allowed.value) {error.value = "您没有处理此申请的权限"; return;}
  if (!form.note.trim()) {error.value = "请填写身份核实与处理说明"; return;}
  if (form.decision === "approve" && selected.value.kind === "register" && (!form.deptId || !form.roleIds.length)) {error.value = "请选择部门和角色"; return;}
  if (form.decision === "approve" && selected.value.kind === "reset" && (form.newPassword.length < 12 || new TextEncoder().encode(form.newPassword).length > 72 || !/[a-zA-Z]/.test(form.newPassword) || !/\d/.test(form.newPassword))) {error.value = "新密码需至少 12 位、不超过 72 字节，包含字母和数字"; return;}
  saving.value = true; error.value = "";
  try {
    await request<void>({url: `/system/account-requests/${selected.value.id}/review`, method: "POST", data: {
      decision: form.decision, note: form.note.trim(),
      ...(form.decision === "approve" ? selected.value.kind === "register" ? {deptId: form.deptId, roleIds: form.roleIds} : {newPassword: form.newPassword} : {}),
    }});
    Toast.success("申请已处理"); selected.value = null; form.newPassword = ""; emit("changed"); await Promise.all([load(), refreshCount()]);
  } catch(e) {error.value = errorMessage(e);} finally {saving.value = false;}
}
</script>
<template>
  <Button aria-label="账号申请" theme="outline" :onClick="open" :title="countError ? '待处理数量加载失败，点击查看并重试' : '每 15 秒刷新待处理数量'">账号申请<span v-if="pendingCount !== null" class="request-count" :class="{empty: pendingCount === 0}">{{ pendingCount }}</span><span v-if="countError" role="status">!</span></Button>
  <SideSheet :visible="visible" title="账号申请" :width="960" :onCancel="() => {if (!saving) {visible = false; selected = null; form.newPassword = '';}}">
    <div class="account-requests">
      <p class="muted">注册与找回密码均需通过企业内部渠道核实身份。联系邮箱和手机号由申请人提供，不能单独作为身份凭据。</p>
      <p v-if="error" role="alert" class="field-error">{{ error }}</p>
      <form v-if="selected" @submit.prevent="review" class="request-review panel">
        <h3>{{ selected.kind === 'register' ? '审核注册' : '协助重置密码' }} · {{ selected.username }}</h3>
        <p>{{ selected.name }} · {{ selected.email }} · {{ selected.phone }}</p>
        <div class="form-field"><label for="request-decision">处理结果</label><Select id="request-decision" aria-label="处理结果" :value="form.decision" :onChange="(v: any) => form.decision = v" :optionList="[{value: 'approve', label: '通过申请'}, {value: 'reject', label: '拒绝申请'}]" /></div>
        <template v-if="form.decision === 'approve' && selected.kind === 'register'">
          <div class="form-field"><label for="request-dept">所属部门</label><Select id="request-dept" aria-label="所属部门" placeholder="请选择部门" :value="form.deptId" :onChange="(v: any) => form.deptId = v" :optionList="options.departments.filter(d => d.status === '启用').map(d => ({value: d.id, label: d.name}))" /></div>
          <div class="form-field"><label for="request-roles">分配角色</label><Select id="request-roles" aria-label="分配角色" multiple placeholder="请选择角色" :value="form.roleIds" :onChange="(v: any) => form.roleIds = v" :optionList="options.roles.filter(r => !r.builtin && r.status === '启用').map(r => ({value: r.id, label: r.name}))" /></div>
          <p v-if="!options.roles.some(r => !r.builtin && r.status === '启用')" class="muted">请先在角色管理中创建可分配的普通角色。</p>
        </template>
        <div v-if="form.decision === 'approve' && selected.kind === 'reset'" class="form-field">
          <label for="request-password">重置后的密码</label><Input id="request-password" aria-label="重置后的密码" mode="password" :value="form.newPassword" :onChange="(v: string) => form.newPassword = v" placeholder="至少 12 位，包含字母和数字" />
          <p class="muted">请通过已核实的企业内部渠道告知本人。原有登录会话将失效；内置管理员需联系系统维护人员处理。</p>
        </div>
        <div class="form-field"><label for="request-note">身份核实与处理说明</label><TextArea id="request-note" aria-label="身份核实与处理说明" :value="form.note" :maxCount="500" :onChange="(v: string) => form.note = v" placeholder="例如：已通过企业通讯录回拨核实身份" /></div>
        <div class="request-review-actions"><Button theme="solid" htmlType="submit" :loading="saving">确认处理</Button><Button :disabled="saving" :onClick="() => {selected = null; form.newPassword = ''; error = '';}">返回列表</Button></div>
      </form>
      <template v-else>
        <div class="request-filters"><Select aria-label="申请状态" :value="status" :onChange="(v: any) => {status = v; page = 1; load();}" :optionList="[{value: 'pending', label: '待处理'}, {value: 'approved', label: '已通过'}, {value: 'rejected', label: '已拒绝'}, {value: '', label: '全部状态'}]" /><Button :loading="loading" :onClick="load">刷新</Button></div>
        <div class="request-table-wrap"><table class="request-table"><thead><tr><th>申请类型 / 账号</th><th>申请人</th><th>联系方式</th><th>状态 / 说明</th><th>操作</th></tr></thead><tbody>
          <tr v-for="row in rows" :key="row.id"><td>{{ row.kind === 'register' ? '注册' : '找回密码' }}<br /><strong>{{ row.username }}</strong></td><td>{{ row.name }}</td><td>{{ row.email }}<br />{{ row.phone }}</td><td>{{ statuses[row.status] }}<br /><small>{{ row.note }}</small></td><td><Button v-if="row.status === 'pending'" theme="borderless" :onClick="() => select(row)">处理</Button><span v-else>已完成</span></td></tr>
          <tr v-if="!rows.length"><td colspan="5">{{ loading ? '加载中…' : '暂无账号申请' }}</td></tr>
        </tbody></table></div>
        <Pagination :currentPage="page" :pageSize="10" :total="total" :onPageChange="(v: number) => {page = v; load();}" />
      </template>
    </div>
  </SideSheet>
</template>
<style scoped>
.account-requests {padding: 0 4px 24px;}
.request-count {display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 20px; padding: 0 5px; margin-left: 6px; border-radius: 10px; background: #f04452; color: white; font-size: 12px;}
.request-count.empty {background: #e8effa; color: #7589a8;}
.account-requests > p {margin-bottom: 20px; line-height: 1.7;}
.request-filters,.request-review-actions {display: flex; gap: 12px; margin: 20px 0;}
.request-filters .semi-select {min-width: 140px;}
.request-table-wrap {overflow-x: auto; margin: 20px 0;}
.request-table {width: 100%; border-collapse: collapse; text-align: left; min-width: 620px;}
.request-table th,.request-table td {padding: 14px 10px; border-bottom: 1px solid #e8edf5; line-height: 1.8; overflow-wrap: anywhere;}
.request-table th {background: #f7f9fd; color: #7b8da8;}
.request-review {padding: 24px;}
.request-review > p {margin: 16px 0; line-height: 1.6;}
.request-review .semi-select {width: 100%;}
.request-review .form-field {margin: 20px 0;}
</style>
