<script setup lang="ts">
/**
 * 提供用户模板下载、文件选择、数据预览和整批导入，隐藏预览中的密码。
 * 关联模块：使用 user-import.ts 与 import-csv.ts 校验，调用模板解析和用户导入接口，完成后刷新系统用户页面。
 */
import { ref, reactive } from "vue";
import { Button, SideSheet, Select, Toast } from "../ui";
import AppIcon from "./AppIcon.vue";
import { liveApi, type Options } from "../api/system-live";
import { request, errorMessage } from "../api/request";
import { downloadBlob } from "../data/store";
import { parseUserImportCsv, type ImportRow } from "../utils/user-import";
const emit = defineEmits<{changed: []}>();
const visible = ref(false), saving = ref(false), loading = ref(false), error = ref("");
const rows = ref<ImportRow[]>([]), filename = ref(""), dept = ref(""), roles = ref<string[]>([]), input = ref<HTMLInputElement|null>(null);
const options = reactive<Options>({departments: [], roles: [], posts: [], menus: []});
async function open() {
  visible.value = true; rows.value = []; filename.value = ""; error.value = ""; dept.value = ""; roles.value = []; loading.value = true;
  try {Object.assign(options, await liveApi.options());} catch(e) {error.value = errorMessage(e);} finally {loading.value = false;}
}
function close() {if (saving.value) return; visible.value = false; rows.value = []; filename.value = ""; error.value = ""; if (input.value) input.value.value = "";}
/** 读取 CSV 或请求后台解析 XLSX，再进行用户字段校验和预览。 */
async function choose(event: Event) {
  const element = event.target as HTMLInputElement, file = element.files?.[0];
  rows.value = []; error.value = ""; filename.value = file?.name || "";
  try {
    if (!file) return;
    if (!/\.(csv|xlsx)$/i.test(file.name)) throw Error("请选择 .xlsx 或 CSV UTF-8 文件");
    if (file.size > 1024 * 1024) throw Error("文件不能超过 1 MB");
    loading.value = true;
    let csv: string;
    if (file.name.toLowerCase().endsWith('.xlsx')) {
      const data = new FormData(); data.append('file', file);
      const result = await request<{matrix: string[][]}>({url: '/system/users/import/preview', method: 'POST', data});
      csv = result.matrix.map(row => row.map(cell => '"' + cell.replace(/"/g, '""') + '"').join(',')).join('\r\n');
    } else csv = new TextDecoder('utf-8', {fatal: true}).decode(await file.arrayBuffer());
    rows.value = parseUserImportCsv(csv);
  } catch(e) {error.value = e instanceof TypeError ? "请将 CSV 文件另存为 UTF-8 格式" : errorMessage(e);} finally {element.value = ""; loading.value = false;}
}
/** 提交整批用户记录；后台保证事务性，成功或关闭时清理内存中的待导入密码。 */
async function submit() {
  if (!rows.value.length || saving.value || loading.value) return;
  error.value = "";
  const payload = rows.value.map(row => ({...row, deptId: row.deptId || dept.value, roleIds: row.roleIds.length ? row.roleIds : roles.value}));
  const missing = payload.findIndex(row => !row.deptId || !row.roleIds.length);
  if (missing >= 0) {error.value = `第 ${missing + 1} 条数据缺少部门或角色，请选择默认值或在文件中填写ID`; return;}
  saving.value = true;
  try {
    const result = await request<{imported: number}>({url: "/system/users/import", method: "POST", data: {rows: payload}, timeout: 120000});
    Toast.success(`成功导入 ${result.imported} 个用户`); rows.value = []; emit("changed"); saving.value = false; close();
  } catch(e) {error.value = errorMessage(e);} finally {saving.value = false;}
}
async function template() {
  try {downloadBlob(await request<Blob>({url: '/system/users/import/template', responseType: 'blob'}), '用户导入模板.xlsx');}
  catch(e) {error.value = errorMessage(e);}
}
</script>
<template>
  <Button theme="outline" type="tertiary" :onClick="open"><AppIcon name="cloud" :size="15" />导入</Button>
  <SideSheet :visible="visible" title="导入用户" :width="820" :onCancel="close">
    <div class="user-import">
      <p class="muted">下载 Excel 模板填写后上传 .xlsx 文件，也支持 CSV UTF-8；Excel 仅读取首个工作表。每次最多 100 条、文件不超过 1 MB。仅新增用户，不覆盖现有账号；任意一条失败，整批回滚。</p>
      <div class="import-actions"><Button :disabled="saving" :onClick="template">下载模板</Button><Button theme="solid" :disabled="saving || loading" :onClick="() => input?.click()">{{ loading ? '加载中…' : '选择文件' }}</Button><span>{{ filename }}</span><input ref="input" type="file" accept=".xlsx,.csv" aria-label="选择用户导入文件" :disabled="saving || loading" @change="choose" hidden /></div>
      <div class="form-field"><label>默认部门（文件中未填时使用）</label><Select aria-label="导入默认部门" :disabled="saving || loading" :value="dept" :onChange="(v: any) => dept = v" placeholder="请选择部门" :optionList="options.departments.filter(d => d.status === '启用').map(d => ({value: d.id, label: `${d.name}（${d.id}）`}))" /></div>
      <div class="form-field"><label>默认角色（文件中未填时使用）</label><Select aria-label="导入默认角色" multiple :disabled="saving || loading" :value="roles" :onChange="(v: any) => roles = v" placeholder="请选择普通角色" :optionList="options.roles.filter(r => !r.builtin && r.status === '启用').map(r => ({value: r.id, label: `${r.name}（${r.id}）`}))" /></div>
      <p class="muted">必填列：用户账号、用户姓名、初始密码。密码至少 12 位并包含字母和数字。可逐行填写部门ID、角色ID、岗位ID；多个角色或岗位ID使用 | 分隔。模板含初始密码，请妥善保管。</p>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      <div v-if="rows.length" class="import-preview"><h3>预览 · 共 {{ rows.length }} 条</h3><div class="table-scroll"><table class="data-table"><thead><tr><th>用户账号</th><th>姓名</th><th>部门</th><th>角色</th><th>状态</th></tr></thead><tbody><tr v-for="row in rows.slice(0, 10)" :key="row.username"><td>{{ row.username }}</td><td>{{ row.name }}</td><td>{{ options.departments.find(d => d.id === (row.deptId || dept))?.name || row.deptId || '默认部门' }}</td><td>{{ (row.roleIds.length ? row.roleIds : roles).map(id => options.roles.find(r => r.id === id)?.name || id).join('、') || '默认角色' }}</td><td>{{ row.status }}</td></tr></tbody></table></div><p v-if="rows.length > 10" class="muted">仅预览前 10 条，确认后导入全部 {{ rows.length }} 条。</p></div>
      <div class="import-actions"><Button theme="solid" :loading="saving" :disabled="!rows.length || loading" :onClick="submit">确认导入</Button><Button :disabled="saving" :onClick="close">取消</Button></div>
    </div>
  </SideSheet>
</template>
<style scoped>
.user-import > p {line-height: 1.8; margin-bottom: 20px;}
.user-import .form-field {margin: 20px 0;}
.user-import .semi-select {width: 100%;}
.import-actions {display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 24px 0;}
.import-actions span {overflow-wrap: anywhere;}
.import-preview h3 {margin: 20px 0 12px;}
</style>
