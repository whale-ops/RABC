<script setup lang="ts">
/**
 * 实现原型文件和附件的本地选择、上传展示、预览、下载和删除。
 * 关联模块：store.ts 保存清单，data/files.ts 通过 IndexedDB 保存内容；尚未接入真实文件后台，导航显示待开放。
 */
import { ref, computed, watch, onBeforeUnmount } from "vue";
import { useRoute } from "vue-router";
import { Button, Input, Select, Modal, Toast, Pagination } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import { db, audit, downloadBlob, timestamp, type Row } from "../data/store";
import { putFile, getFile, deleteFile, formatSize } from "../data/files";
import { isStorageImage } from "../utils/storage-category";
const props = defineProps<{ attachments?: boolean; images?: boolean }>();
const route = useRoute();
const search = ref("");
const category = ref("全部文件");
const business = ref("");
const view = ref(props.images ? "grid" : "list");
const page = ref(1);
const uploadOpen = ref(route.query.upload === "1");
const dragOver = ref(false);
const uploading = ref(false);
const progress = ref(0);
const selectedFiles = ref<File[]>([]);
const input = ref<HTMLInputElement>();
const target = ref<Row | null>(null);
const pending = ref<Row | null>(null);
const imageUrl = ref("");
const previewText = ref("");
const previewError = ref("");
const categories = computed(() => props.images ? ["全部文件", "图片"] : ["全部文件", "文档", "压缩包", "其他"]);
const filtered = computed(() =>
  db.files.filter(
    (f) =>
      f.name.toLowerCase().includes(search.value.toLowerCase()) &&
      (category.value === "全部文件" || f.category === category.value) &&
      (props.images ? isStorageImage(f.name) : !isStorageImage(f.name)) &&
      (!business.value || f.business === business.value),
  ),
);
const rows = computed(() =>
  filtered.value.slice((page.value - 1) * 12, page.value * 12),
);
const totalSize = computed(() =>
  filtered.value.reduce((total, f) => total + f.size, 0),
);
watch([search, category, business], () => (page.value = 1));
watch(() => props.images, () => { view.value = props.images ? 'grid' : 'list'; page.value = 1; if (imageUrl.value) URL.revokeObjectURL(imageUrl.value); imageUrl.value = ''; search.value = ''; category.value = '全部文件'; business.value = ''; selectedFiles.value = []; target.value = null; pending.value = null; uploadOpen.value = false; });
function fileIcon(row: Row) {
  return row.category === "图片"
    ? "image"
    : row.category === "压缩包"
      ? "archive"
      : "file-text";
}
function choose(files: FileList | File[] | null) {
  if (!files) return;
  for (const file of Array.from(files)) {
    if (isStorageImage(file.name) !== !!props.images) {
      Toast.warning(props.images ? '其他文件请在附件管理上传' : '图片请在图片管理上传');
      continue;
    }
    if (file.size > 20 * 1024 * 1024) {
      Toast.warning(`${file.name} 超过 20 MB`);
      continue;
    }
    if (
      selectedFiles.value.some(
        (f) => f.name === file.name && f.size === file.size,
      )
    )
      continue;
    selectedFiles.value.push(file);
  }
}
function onDrop(event: DragEvent) {
  dragOver.value = false;
  choose(event.dataTransfer?.files || null);
}
/** 把文件内容保存到浏览器 IndexedDB，并在原型清单中登记元信息，不调用服务器上传。 */
async function upload() {
  if (!selectedFiles.value.length) return Toast.warning("请先选择文件");
  uploading.value = true;
  progress.value = 0;
  try {
    const files = [...selectedFiles.value];
    for (const [i, file] of files.entries()) {
      const id = crypto.randomUUID();
      await putFile(id, file);
      const extension = file.name.split(".").pop()?.toLowerCase();
      db.files.unshift({
        id,
        name: file.name,
        extension,
        size: file.size,
        owner: "管理员",
        createdAt: timestamp(),
        category: isStorageImage(file.name)
          ? "图片"
          : ["zip", "rar", "7z", "tar", "gz"].includes(extension || "")
            ? "压缩包"
            : ["pdf", "docx", "xlsx", "txt", "csv", "md", "pptx"].includes(
                  extension || "",
                )
              ? "文档"
              : "其他",
        business: business.value,
        demo: false,
      });
      selectedFiles.value = selectedFiles.value.filter((f) => f !== file);
      progress.value = Math.round(((i + 1) / files.length) * 100);
      audit("新增", `上传文件：${file.name}`);
    }
    Toast.success("文件上传成功，已保存在当前浏览器");
    uploadOpen.value = false;
  } catch {
    Toast.error("本地存储失败，请检查浏览器存储空间后重试");
  } finally {
    uploading.value = false;
    if (input.value) input.value.value = "";
  }
}
async function preview(row: Row) {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
  imageUrl.value = "";
  previewText.value = "";
  previewError.value = "";
  target.value = row;
  if (row.demo) return;
  try {
    const file = await getFile(row.id);
    if (!file) {
      previewError.value = "文件内容不在此浏览器中，请重新上传";
      return;
    }
    if (
      ["image/png", "image/jpeg", "image/webp", "image/gif"].includes(file.type)
    )
      imageUrl.value = URL.createObjectURL(file);
    else if (["txt", "md", "csv", "json"].includes(row.extension))
      previewText.value = (await file.text()).slice(0, 15000);
  } catch {
    previewError.value = "无法读取文件，请重新上传";
  }
}
async function download(row: Row) {
  if (row.demo) {
    await preview(row);
    return;
  }
  try {
    const file = await getFile(row.id);
    if (!file) return Toast.warning("未找到本地文件，请重新上传");
    downloadBlob(file, row.name);
  } catch {
    Toast.error("文件读取失败");
  }
}
async function remove() {
  if (!pending.value) return;
  try {
    if (!pending.value.demo) await deleteFile(pending.value.id);
    db.files = db.files.filter((f) => f.id !== pending.value!.id);
    audit("删除", `删除文件：${pending.value.name}`);
    pending.value = null;
    page.value = Math.min(
      page.value,
      Math.max(1, Math.ceil(filtered.value.length / 12)),
    );
    Toast.success("文件已删除");
  } catch {
    Toast.error("删除失败，请重试");
  }
}
onBeforeUnmount(() => {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
});
</script>
<template>
  <div class="files-page">
    <div class="page-heading">
      <div>
        <h1>{{ images ? "图片管理" : "附件管理" }}</h1>
        <p>
          {{
            attachments
              ? "统一管理项目附件，轻松查找关联资源"
              : "集中存储、便捷分享，让企业文件井井有条"
          }}
        </p>
      </div>
      <Button theme="solid" :onClick="() => (uploadOpen = true)"
        ><AppIcon name="upload" :size="17" />上传{{
          images ? "图片" : "附件"
        }}</Button
      >
    </div>
    <div class="file-overview">
      <div class="file-summary-card">
        <span class="summary-icon blue"
          ><AppIcon name="folder" :size="25"
        /></span>
        <div>
          <small>文件总数</small
          ><strong>{{ filtered.length }}<em>个</em></strong>
        </div>
      </div>
      <div class="file-summary-card">
        <span class="summary-icon purple"
          ><AppIcon name="disk" :size="25"
        /></span>
        <div>
          <small>已用空间</small><strong>{{ formatSize(totalSize) }}</strong>
        </div>
      </div>
      <div class="file-summary-card">
        <span class="summary-icon mint"
          ><AppIcon name="paperclip" :size="25"
        /></span>
        <div>
          <small>业务附件</small
          ><strong
            >{{ db.files.filter((f) => f.business).length }}<em>个</em></strong
          >
        </div>
      </div>
      <div class="file-summary-card storage-card">
        <div>
          <small>本地文件空间</small
          ><span>{{ formatSize(totalSize) }} <em>/ 浏览器存储</em></span>
          <div class="storage-progress">
            <i
              :style="{ width: Math.min(100, totalSize / 1024 / 1024) + '%' }"
            />
          </div>
        </div>
        <AppIcon name="cloud" :size="34" />
      </div>
    </div>
    <section class="panel file-panel">
      <div class="file-toolbar">
        <div class="file-tabs">
          <button
            v-for="c in categories"
            :key="c"
            :class="{ active: category === c }"
            @click="category = c"
          >
            {{ c }}<small v-if="c === '全部文件'">{{ db.files.length }}</small>
          </button>
        </div>
        <div class="file-tools">
          <Input
            :value="search"
            placeholder="搜索文件名称"
            showClear
            :onChange="(v: string) => (search = v)"
          /><button type="button" class="icon-button" :class="{ 'selected-icon': view === 'list' }"
            :aria-label="view === 'grid' ? '切换为列表视图' : '切换为卡片视图'"
            :title="view === 'grid' ? '切换为列表视图' : '切换为卡片视图'"
            @click="view = view === 'grid' ? 'list' : 'grid'">
            <AppIcon :name="view === 'grid' ? 'list' : 'grid'" :size="19" />
          </button>
          <button type="button" class="icon-button" aria-label="刷新文件" title="刷新" @click="page = 1"><AppIcon name="refresh" :size="19" /></button>
        </div>
      </div>
      <div v-if="attachments" class="attachment-filter">
        <span>关联项目</span
        ><Select
          :value="business"
          :optionList="[
            { label: '全部项目', value: '' },
            ...db.projects
              .slice(0, 8)
              .map((p) => ({ label: p.name, value: p.name })),
          ]"
          :onChange="(v: any) => (business = v)"
          style="width: 260px"
        />
      </div>
      <div v-if="view === 'grid'" class="file-grid">
        <article v-for="row in rows" :key="row.id" class="file-card">
          <button
            class="file-preview"
            :class="'file-' + row.extension"
            :aria-label="'预览' + row.name"
            @click="preview(row)"
          >
            <span class="file-symbol"
              ><AppIcon
                :name="fileIcon(row)"
                :size="43"
                :stroke-width="1.5"
              /><b>{{ row.extension?.toUpperCase() }}</b></span
            ><span v-if="row.demo" class="demo-label">示例</span>
          </button>
          <div class="file-card-details">
            <button class="file-name" @click="preview(row)" :title="row.name">
              {{ row.name }}
            </button>
            <p>
              {{ formatSize(row.size)
              }}<span>{{ row.createdAt.slice(0, 10) }}</span>
            </p>
            <div class="file-card-bottom">
              <span
                ><span class="tiny-avatar">{{ row.owner.slice(0, 1) }}</span
                >{{ row.owner }}</span
              >
              <div>
                <button
                  class="icon-button"
                  aria-label="下载文件"
                  @click="download(row)"
                >
                  <AppIcon name="download" :size="15" /></button
                ><button
                  class="icon-button"
                  aria-label="删除文件"
                  @click="pending = row"
                >
                  <AppIcon name="trash" :size="15" />
                </button>
              </div>
            </div>
          </div>
        </article>
      </div>
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>文件名称</th>
              <th>文件大小</th>
              <th>上传人</th>
              <th>关联项目</th>
              <th>上传时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>
                <span class="table-person"
                  ><AppIcon :name="fileIcon(row)" :size="20" /><button
                    class="text-button"
                    @click="preview(row)"
                  >
                    {{ row.name }}
                  </button></span
                >
              </td>
              <td>{{ formatSize(row.size) }}</td>
              <td>{{ row.owner }}</td>
              <td>{{ row.business || "—" }}</td>
              <td>{{ row.createdAt }}</td>
              <td>
                <div class="row-actions">
                  <button @click="download(row)">下载</button
                  ><button class="danger" @click="pending = row">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!rows.length" class="empty-state">
        <span><AppIcon name="folder-open" :size="40" /></span>
        <h3>这里还没有文件</h3>
        <p>上传文件，或试试其他筛选条件</p>
        <Button theme="solid" :onClick="() => (uploadOpen = true)"
          >上传文件</Button
        >
      </div>
      <div class="table-pagination">
        <span>共 {{ filtered.length }} 个文件</span
        ><Pagination
          :total="filtered.length"
          :pageSize="12"
          :currentPage="page"
          :onPageChange="(p: number) => (page = p)"
        />
      </div>
    </section>
    <Modal
      :visible="uploadOpen"
      title="上传文件"
      :width="580"
      :onCancel="
        () => {
          if (!uploading) uploadOpen = false;
        }
      "
      :onOk="upload"
      :confirmLoading="uploading"
      okText="开始上传"
      :okButtonProps="{ 'aria-label': '开始上传' }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      ><div
        class="upload-zone"
        :class="{ dragging: dragOver }"
        @dragover.prevent="dragOver = true"
        @dragleave.prevent="dragOver = false"
        @drop.prevent="onDrop"
      >
        <span><AppIcon name="cloud" :size="46" :stroke-width="1.5" /></span>
        <h3>
          将文件拖到此处，或<button class="text-button" @click="input?.click()">
            点击选择
          </button>
        </h3>
        <p>支持常用文档、图片与压缩包，单个文件不超过 20 MB</p>
        <input
          ref="input"
          type="file"
          multiple
          hidden
          @change="choose(($event.target as HTMLInputElement).files)"
        />
      </div>
      <div
        v-for="(file, i) in selectedFiles"
        :key="file.name + i"
        class="upload-file"
      >
        <AppIcon name="file-text" :size="18" /><span>{{ file.name }}</span
        ><small>{{ formatSize(file.size) }}</small
        ><button
          class="icon-button"
          aria-label="移除待上传文件"
          :disabled="uploading"
          @click="selectedFiles.splice(i, 1)"
        >
          <AppIcon name="close" :size="15" />
        </button>
      </div>
      <div class="form-field upload-project">
        <label>关联项目（可选）</label
        ><Select
          :value="business"
          :optionList="[
            { label: '不关联项目', value: '' },
            ...db.projects
              .slice(0, 8)
              .map((p) => ({ label: p.name, value: p.name })),
          ]"
          :onChange="(v: any) => (business = v)"
        />
      </div>
      <div v-if="uploading" class="storage-progress">
        <i :style="{ width: progress + '%' }" />
      </div>
      <p class="local-file-note">
        文件保存在当前浏览器，清除站点数据会一并移除。
      </p></Modal
    >
    <Modal
      :visible="!!target"
      :title="target?.name"
      :width="680"
      :onCancel="
        () => {
          target = null;
        }
      "
      :footer="''"
      ><div v-if="target" class="file-detail">
        <img
          v-if="imageUrl"
          class="image-preview"
          :src="imageUrl"
          :alt="target.name"
        />
        <pre v-else-if="previewText" class="text-preview">{{
          previewText
        }}</pre>
        <div v-else class="file-preview-large">
          <AppIcon :name="fileIcon(target)" :size="64" :stroke-width="1" />
          <p>
            {{
              target.demo
                ? "这是用于展示页面效果的示例文件"
                : previewError || "此格式可下载后使用本地应用查看"
            }}
          </p>
          <small v-if="target.demo"
            >上传自己的文件，即可使用实际预览和下载功能。</small
          >
        </div>
        <dl class="detail-list">
          <dt>文件大小</dt>
          <dd>{{ formatSize(target.size) }}</dd>
          <dt>上传人</dt>
          <dd>{{ target.owner }}</dd>
          <dt>上传时间</dt>
          <dd>{{ target.createdAt }}</dd>
          <dt>关联项目</dt>
          <dd>{{ target.business || "未关联" }}</dd>
        </dl>
        <Button
          v-if="!target.demo"
          theme="solid"
          :onClick="() => download(target!)"
          ><AppIcon name="download" :size="16" />下载文件</Button
        >
      </div></Modal
    >
    <Modal
      :visible="!!pending"
      title="删除文件"
      :onCancel="
        () => {
          pending = null;
        }
      "
      :onOk="remove"
      okText="确认删除"
      :okButtonProps="{ 'aria-label': '确认删除' }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      ><p>确认删除「{{ pending?.name }}」及其附件关联吗？</p>
      <p class="muted">本地文件内容将同时移除。</p></Modal
    >
  </div>
</template>
