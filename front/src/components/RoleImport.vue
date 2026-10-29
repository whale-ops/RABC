<script setup lang="ts">
/**
 * 提供角色模板下载、文件预览、字段校验和整批导入。
 * 关联模块：复用 role-import.ts 与 import-csv.ts；后台创建普通角色，权限与成员随后在角色管理中单独配置。
 */
import { ref } from 'vue';
import { Button, SideSheet, Toast } from '../ui';
import AppIcon from './AppIcon.vue';
import { request, errorMessage } from '../api/request';
import { downloadBlob } from '../data/store';
import { parseRoleImportCsv, type RoleImportRow } from '../utils/role-import';
import { matrixToCsv } from '../utils/import-csv';
const emit = defineEmits<{changed: []}>();
const visible = ref(false), loading = ref(false), saving = ref(false), error = ref('');
const rows = ref<RoleImportRow[]>([]), filename = ref(''), input = ref<HTMLInputElement|null>(null);
function open() {rows.value = []; filename.value = ''; error.value = ''; visible.value = true;}
function close() {if (loading.value || saving.value) return; visible.value = false; rows.value = []; filename.value = ''; error.value = ''; if (input.value) input.value.value = '';}
async function template() {
  try {downloadBlob(await request<Blob>({url: '/system/roles/import/template', responseType: 'blob'}), '角色导入模板.xlsx');}
  catch(e) {error.value = errorMessage(e);}
}
/** 读取表格并检查角色字段、默认值和批次内重复，不在预览阶段创建角色。 */
async function choose(event: Event) {
  const element = event.target as HTMLInputElement, file = element.files?.[0];
  rows.value = []; filename.value = file?.name || ''; error.value = '';
  try {
    if (!file) return;
    if (!/\.(xlsx|csv)$/i.test(file.name)) throw Error('请选择 .xlsx 或 CSV UTF-8 文件');
    if (file.size > 1024 * 1024) throw Error('文件不能超过 1 MB');
    loading.value = true;
    let csv: string;
    if (file.name.toLowerCase().endsWith('.xlsx')) {
      const data = new FormData(); data.append('file', file);
      const result = await request<{matrix: string[][]}>({url: '/system/roles/import/preview', method: 'POST', data});
      csv = matrixToCsv(result.matrix);
    } else csv = new TextDecoder('utf-8', {fatal: true}).decode(await file.arrayBuffer());
    rows.value = parseRoleImportCsv(csv);
  } catch(e) {error.value = e instanceof TypeError ? '请将 CSV 文件另存为 UTF-8 格式' : errorMessage(e);}
  finally {element.value = ''; loading.value = false;}
}
/** 提交已校验的角色批次；权限与成员仍在角色管理中单独维护。 */
async function submit() {
  if (!rows.value.length || loading.value || saving.value) return;
  saving.value = true; error.value = '';
  try {
    const result = await request<{imported: number}>({url: '/system/roles/import', method: 'POST', data: {rows: rows.value}});
    Toast.success(`成功导入 ${result.imported} 个角色`); saving.value = false; close(); emit('changed');
  } catch(e) {error.value = errorMessage(e);} finally {saving.value = false;}
}
</script>
<template>
  <Button theme="outline" type="tertiary" :onClick="open"><AppIcon name="cloud" :size="15" />导入</Button>
  <SideSheet :visible="visible" title="导入角色" :width="820" :onCancel="close">
    <div class="role-import">
      <p class="muted">下载 Excel 模板填写后上传 .xlsx 文件，也支持 CSV UTF-8。仅读取首个工作表，每批最多 100 条、文件最多 1 MB。仅新增普通角色；任意一条失败，整批回滚。</p>
      <div class="import-actions"><Button :disabled="loading || saving" :onClick="template">下载模板</Button><Button theme="solid" :disabled="loading || saving" :onClick="() => input?.click()">{{ loading ? '读取中…' : '选择文件' }}</Button><span>{{ filename }}</span><input ref="input" hidden type="file" accept=".xlsx,.csv" aria-label="选择角色导入文件" :disabled="loading || saving" @change="choose" /></div>
      <p class="muted">必填列：角色名称、角色编码。数据范围不填时为“仅本人”，状态不填时为“启用”。导入不分配菜单权限或成员；自定义部门范围请在导入后通过权限设置配置。</p>
      <p v-if="error" role="alert" class="field-error">{{ error }}</p>
      <div v-if="rows.length" class="import-preview"><h3>预览 · 共 {{ rows.length }} 条</h3><div class="table-scroll"><table class="data-table"><thead><tr><th>角色名称</th><th>角色编码</th><th>数据范围</th><th>状态</th><th>备注</th></tr></thead><tbody><tr v-for="row in rows.slice(0, 10)" :key="row.code"><td>{{ row.name }}</td><td>{{ row.code }}</td><td>{{ row.scope }}</td><td>{{ row.status }}</td><td>{{ row.remark }}</td></tr></tbody></table></div><p v-if="rows.length > 10" class="muted">仅预览前 10 条，确认后将导入全部 {{ rows.length }} 条。</p></div>
      <div class="import-actions"><Button theme="solid" :loading="saving" :disabled="!rows.length || loading" :onClick="submit">确认导入</Button><Button :disabled="loading || saving" :onClick="close">取消</Button></div>
    </div>
  </SideSheet>
</template>
<style scoped>
.role-import > p {line-height: 1.8; margin-bottom: 20px;}
.import-actions {display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 24px 0;}
.import-actions span {overflow-wrap: anywhere;}
.import-preview h3 {margin-bottom: 12px;}
</style>
