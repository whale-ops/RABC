<script setup lang="ts">
/**
 * Office 在线预览：Excel 工作表切换、合并单元格与 Word 正文、表格、内嵌图片。
 * 关联模块：storageApi.preview 鉴权读取后台 POI 结果；StorageFiles.vue 控制弹窗生命周期。
 */
import { computed, ref, watch } from "vue";
import { Button } from "../ui";
import { storageApi, type StorageDocumentPreview } from "../api/storage";
import { errorMessage } from "../api/request";

const props = defineProps<{ id: string }>();
const result = ref<StorageDocumentPreview | null>(null);
const loading = ref(false), error = ref("");
const selectedSheet = ref(0), retry = ref(0);
const sheet = computed(() => result.value?.sheets[selectedSheet.value]);
const cells = computed(() => {
  const merged = new Map<string, {rowSpan: number; columnSpan: number}>(), hidden = new Set<string>();
  for (const range of sheet.value?.merges || []) {
    merged.set(`${range.row}:${range.column}`, range);
    for (let r = range.row; r < range.row + range.rowSpan; r++)
      for (let c = range.column; c < range.column + range.columnSpan; c++)
        if (r !== range.row || c !== range.column) hidden.add(`${r}:${c}`);
  }
  return (sheet.value?.rows || []).map((row, r) => row.map((text, c) => ({text, hidden: hidden.has(`${r}:${c}`), ...merged.get(`${r}:${c}`)})));
});
const columns = computed(() => Math.max(0, ...(sheet.value?.rows || []).map(row => row.length)));
function columnName(index: number): string {
  let name = "";
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + (n - 1) % 26) + name;
  return name;
}
watch([() => props.id, retry], async ([id], _, onCleanup) => {
  const controller = new AbortController();
  let active = true;
  onCleanup(() => { active = false; controller.abort(); });
  result.value = null; error.value = ""; loading.value = true; selectedSheet.value = 0;
  try {
    const value = await storageApi.preview(String(id), controller.signal);
    if (active) result.value = value;
  } catch (failure) { if (active) error.value = errorMessage(failure); }
  finally { if (active) loading.value = false; }
}, { immediate: true });
</script>

<template>
  <div class="office-preview" :aria-busy="loading">
    <p v-if="loading" class="office-placeholder" role="status">正在解析文档，请稍候…</p>
    <div v-else-if="error" class="office-placeholder" role="alert"><p>{{ error }}</p><Button :onClick="() => retry++">重新加载</Button></div>
    <template v-else-if="result">
      <p v-if="result.truncated" class="office-limit" role="status">{{ result.kind === 'spreadsheet' ? '当前显示部分内容：每张表最多 300 行、50 列、共 10 张表，过长的单元格会截断。' : '文档较长或内嵌图片较大，当前仅展示部分内容。' }}完整内容请下载查看。</p>
      <template v-if="result.kind === 'spreadsheet'">
        <div class="office-sheet-tabs" role="tablist" aria-label="Excel 工作表">
          <button v-for="(item, index) in result.sheets" :key="index" type="button" role="tab" :aria-selected="selectedSheet === index"
            :class="{ active: selectedSheet === index }" @click="selectedSheet = index">{{ item.name }}</button>
        </div>
        <div v-if="sheet?.rows.length" class="office-sheet-scroll" role="tabpanel" :aria-label="sheet.name" tabindex="0">
          <table class="office-sheet"><thead><tr><th class="row-number"></th><th v-for="(_, col) in columns" :key="col">{{ columnName(col) }}</th></tr></thead>
            <tbody><tr v-for="(row, r) in cells" :key="r"><th class="row-number">{{ r + 1 }}</th>
              <template v-for="(cell, c) in row" :key="c"><td v-if="!cell.hidden" :rowspan="cell.rowSpan" :colspan="cell.columnSpan">{{ cell.text }}</td></template>
            </tr></tbody>
          </table>
        </div>
        <p v-else class="office-placeholder">该工作表没有可预览的数据</p>
      </template>
      <div v-else class="office-document-scroll">
        <article class="office-paper" aria-label="Word 正文">
          <template v-for="(block, index) in result.blocks" :key="index">
            <div v-if="block.kind === 'table'" class="office-word-table-scroll"><table class="office-word-table"><tbody>
              <tr v-for="(row, r) in block.rows" :key="r"><td v-for="(text, c) in row" :key="c">{{ text }}</td></tr>
            </tbody></table></div>
            <img v-else-if="block.kind === 'image'" :src="block.text" alt="文档内嵌图片" />
            <component v-else :is="block.heading ? 'h' + block.heading : 'p'" :style="{textAlign: block.alignment}">{{ block.text || '\u00a0' }}</component>
          </template>
          <p v-if="!result.blocks.length">该文档没有可预览的正文</p>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.office-preview { min-width: 0; color: #243b5b; }
.office-placeholder { padding: 60px 16px; text-align: center; color: #7b8da8; }
.office-limit { padding: 10px 14px; border-radius: 6px; background: #fff8e8; color: #98651c; font-size: 12px; }
.office-sheet-tabs { display: flex; overflow-x: auto; gap: 6px; margin-bottom: 12px; border-bottom: 1px solid #e5ebf3; }
.office-sheet-tabs button { padding: 10px 16px; white-space: nowrap; color: #6d819e; border-bottom: 2px solid transparent; }
.office-sheet-tabs button.active { color: #1677ff; border-bottom-color: #1677ff; background: #f0f6ff; }
.office-sheet-scroll { max-height: 58vh; overflow: auto; border: 1px solid #dce5ef; }
.office-sheet { border-collapse: separate; border-spacing: 0; font-size: 13px; min-width: 100%; }
.office-sheet th, .office-sheet td { min-width: 110px; max-width: 420px; padding: 9px 12px; border-right: 1px solid #e3e9f1; border-bottom: 1px solid #e3e9f1; white-space: pre-wrap; overflow-wrap: anywhere; vertical-align: top; }
.office-sheet thead th { position: sticky; top: 0; background: #eef3f9; text-align: center; z-index: 2; font-weight: 500; }
.office-sheet .row-number { position: sticky; left: 0; min-width: 42px; width: 42px; text-align: center; background: #f3f6fa; z-index: 1; }
.office-sheet thead .row-number { z-index: 3; }
.office-document-scroll { max-height: 62vh; overflow: auto; padding: 18px; background: #edf1f6; }
.office-paper { max-width: 780px; margin: 0 auto; padding: 36px; background: white; box-shadow: 0 2px 12px #21345412; font-size: 15px; line-height: 1.8; overflow-wrap: anywhere; }
.office-paper p { white-space: pre-wrap; margin: 0 0 12px; color: inherit; }
.office-paper h1, .office-paper h2, .office-paper h3 { margin: 16px 0; }
.office-paper img { display: block; max-width: 100%; margin: 12px auto; }
.office-word-table-scroll { overflow-x: auto; margin: 14px 0; }
.office-word-table { border-collapse: collapse; width: 100%; }
.office-word-table td { border: 1px solid #cfd9e7; padding: 8px 12px; min-width: 70px; white-space: pre-wrap; }
@media (max-width: 600px) { .office-document-scroll { padding: 8px; } .office-paper { padding: 18px; } }
</style>
