<script setup lang="ts">
/**
 * 真实文件管理：查询、上传、预览、下载、删除均通过 Axios 调用后台，文件保存在 OSS / MinIO。
 * 关联模块：storage.ts 对接接口，session.ts 控制按钮权限，后台自动记录上传和删除操作日志。
 */
import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  ref,
  watch,
} from "vue";
import { useRoute } from "vue-router";
import { Button, Input, Modal, Pagination, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import StorageThumbnail from "../components/StorageThumbnail.vue";
import OfficeDocumentPreview from "../components/OfficeDocumentPreview.vue";
import VideoDocumentPreview from "../components/VideoDocumentPreview.vue";
import { storageApi, type StorageFile } from "../api/storage";
import { ApiError, errorMessage } from "../api/request";
import { can } from "../api/session";
import { formatSize } from "../data/files";
import { storageProviderLabel } from "../utils/storage-provider";
import { downloadBlob } from "../data/store";
import {
  imageAccept,
  videoAccept,
  isStorageImage,
  isStorageVideo,
  type StorageCategory,
} from "../utils/storage-category";

const props = defineProps<{ category: StorageCategory }>();
const PdfDocumentPreview = defineAsyncComponent(
  () => import("../components/PdfDocumentPreview.vue"),
);
const route = useRoute();
const isImages = computed(() => props.category === "images");
const isVideos = computed(() => props.category === "videos");
const noun = computed(() =>
  isImages.value ? "图片" : isVideos.value ? "视频" : "附件",
);
const categoryIcon = computed(() =>
  isImages.value ? "image" : isVideos.value ? "video" : "paperclip",
);
const title = computed(() => `${noun.value}管理`);
const uploadHint = computed(() =>
  isImages.value
    ? "支持 PNG、JPG、JPEG、GIF、WEBP、BMP、AVIF，单张图片不超过 20 MB"
    : isVideos.value
      ? "支持 MP4、WebM、OGV、MOV、M4V，单个视频不超过 20 MB；推荐 MP4（H.264/AAC）"
      : "支持文档、表格、压缩包等非图片、非视频文件，单个附件不超过 20 MB",
);

const files = ref<StorageFile[]>([]);
// 两种展示共用同一页数据；图片默认横向卡片，附件默认列表，查询和刷新保留当前选择。
const viewMode = ref<"grid" | "list">("grid");
const switchViewLabel = computed(() =>
  viewMode.value === "grid" ? "切换为列表视图" : "切换为卡片视图",
);
const search = ref("");
const appliedKeyword = ref("");
const page = ref(1);
const total = ref(0);
const totalSize = ref(0);
const loading = ref(false);
const error = ref("");
const uploadOpen = ref(false);
const uploading = ref(false);
const deleting = ref(false);
const pending = ref<StorageFile | null>(null);
const deleteMode = ref<"all" | "record">("all");
const deleteError = ref("");
const storageUnavailable = ref(false);
const deleteConfirmText = computed(() =>
  deleteMode.value === "record" ? "确认移除记录" : "确认删除",
);
function confirmDelete(file: StorageFile) {
  if (deleting.value) return;
  deleteMode.value = "all";
  deleteError.value = "";
  storageUnavailable.value = false;
  pending.value = file;
}
watch(deleteMode, () => {
  deleteError.value = "";
});
const selected = ref<File[]>([]);
const input = ref<HTMLInputElement>();
let sequence = 0;
const previewFile = ref<StorageFile | null>(null);
const previewLoading = ref(false);
const previewError = ref("");
const previewUrl = ref("");
const previewText = ref("");
const previewTruncated = ref(false);
const previewKind = ref("");
let previewSequence = 0;

/** 仅允许浏览器可展示的格式；HTML、SVG 和脚本不作为网页执行，文本通过 Vue 插值转义。 */
const previewTypes: Record<string, [string, string]> = {
  png: ["image", "image/png"],
  jpg: ["image", "image/jpeg"],
  jpeg: ["image", "image/jpeg"],
  gif: ["image", "image/gif"],
  webp: ["image", "image/webp"],
  bmp: ["image", "image/bmp"],
  avif: ["image", "image/avif"],
  pdf: ["pdf", "application/pdf"],
  mp4: ["video", "video/mp4"],
  webm: ["video", "video/webm"],
  ogv: ["video", "video/ogg"],
  mov: ["video", "video/quicktime"],
  m4v: ["video", "video/mp4"],
  mp3: ["audio", "audio/mpeg"],
  wav: ["audio", "audio/wav"],
  ogg: ["audio", "audio/ogg"],
};
function releasePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = "";
}
function closePreview() {
  ++previewSequence;
  previewFile.value = null;
  previewLoading.value = false;
  releasePreview();
}
/** 复用下载接口的令牌、租户和所有者校验；关闭或切换预览后忽略旧响应并释放 Blob 地址。 */
async function preview(file: StorageFile) {
  const current = ++previewSequence;
  releasePreview();
  previewFile.value = file;
  previewError.value = "";
  previewText.value = "";
  previewTruncated.value = false;
  previewLoading.value = false;
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  const type = previewTypes[extension];
  previewKind.value = /^(xlsx|xls|docx|doc)$/.test(extension)
    ? "office"
    : type?.[0] ||
      (/^(txt|md|csv|json|log|xml|yaml|yml)$/.test(extension) ? "text" : "");
  if (!previewKind.value) return;
  // Office 组件独立读取结构化预览，避免再次下载整份文件或将文档发到第三方站点。
  if (["office", "pdf", "video"].includes(previewKind.value)) return;
  previewLoading.value = true;
  try {
    const blob = await storageApi.download(file.id);
    if (current !== previewSequence) return;
    if (previewKind.value === "text") {
      // 大文本仅显示前 64 KiB，避免一次渲染过多内容影响页面交互。
      const bytes = new Uint8Array(
        await blob.slice(0, 64 * 1024).arrayBuffer(),
      );
      let content: string;
      if (bytes[0] === 0xff && bytes[1] === 0xfe)
        content = new TextDecoder("utf-16le").decode(bytes);
      else if (bytes[0] === 0xfe && bytes[1] === 0xff)
        content = new TextDecoder("utf-16be").decode(bytes);
      else {
        try {
          content = new TextDecoder("utf-8", { fatal: true }).decode(bytes, {
            stream: blob.size > bytes.length,
          });
        } catch {
          content = new TextDecoder("gb18030").decode(bytes, {
            stream: blob.size > bytes.length,
          });
        }
      }
      if (current !== previewSequence) return;
      previewText.value = content;
      previewTruncated.value = blob.size > 64 * 1024;
    } else {
      // 后台统一返回 octet-stream，这里按白名单指定 MIME，且只生成本页面临时地址。
      previewUrl.value = URL.createObjectURL(
        new Blob([blob], { type: type![1] }),
      );
    }
  } catch (failure) {
    if (current === previewSequence) previewError.value = errorMessage(failure);
  } finally {
    if (current === previewSequence) previewLoading.value = false;
  }
}

/** 忽略快速查询产生的过期响应，防止旧列表覆盖当前筛选结果。 */
async function load() {
  const current = ++sequence;
  loading.value = true;
  error.value = "";
  try {
    const result = await storageApi.list(
      appliedKeyword.value,
      page.value,
      props.category,
    );
    if (current !== sequence) return;
    files.value = result.items;
    total.value = result.total;
    totalSize.value = result.totalSize;
    if (page.value > 1 && !files.value.length) {
      page.value = Math.max(1, Math.ceil(total.value / 12));
      await load();
    }
  } catch (failure) {
    if (current === sequence) error.value = errorMessage(failure);
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function query() {
  appliedKeyword.value = search.value;
  page.value = 1;
  void load();
}
function choose(list: FileList | null) {
  if (uploading.value || !list) return;
  for (const file of Array.from(list)) {
    const category = isStorageImage(file.name)
      ? "images"
      : isStorageVideo(file.name)
        ? "videos"
        : "attachments";
    if (category !== props.category) {
      const destination =
        category === "images"
          ? "图片"
          : category === "videos"
            ? "视频"
            : "附件";
      Toast.warning(`${file.name} 请到${destination}管理上传`);
      continue;
    }
    if (!file.size || file.size > 20 * 1024 * 1024) {
      Toast.warning(`${file.name} 需要为非空文件，且不能超过 20 MB`);
      continue;
    }
    if (
      !selected.value.some((f) => f.name === file.name && f.size === file.size)
    )
      selected.value.push(file);
  }
  if (input.value) input.value.value = "";
}
async function upload() {
  if (!selected.value.length || uploading.value) return;
  uploading.value = true;
  const category = props.category;
  let completed = 0;
  try {
    for (const file of [...selected.value]) {
      await storageApi.upload(file, category);
      selected.value = selected.value.filter((item) => item !== file);
      completed++;
    }
    uploadOpen.value = false;
    Toast.success(`已上传 ${completed} 个文件`);
  } catch (failure) {
    Toast.error(
      `${completed ? `已上传 ${completed} 个；` : ""}${errorMessage(failure)}`,
    );
  } finally {
    uploading.value = false;
    page.value = 1;
    await load();
  }
}
async function download(file: StorageFile) {
  try {
    downloadBlob(await storageApi.download(file.id), file.name);
  } catch (failure) {
    Toast.error(errorMessage(failure));
  }
}
async function remove() {
  if (!pending.value || deleting.value) return;
  deleting.value = true;
  try {
    const file = pending.value;
    const recordOnly = deleteMode.value === "record";
    deleteError.value = "";
    if (recordOnly) await storageApi.removeRecord(file.id);
    else await storageApi.remove(file.id);
    pending.value = null;
    Toast.success(recordOnly ? "文件记录已移除，未删除远端文件" : "文件已删除");
    await load();
  } catch (failure) {
    deleteError.value = errorMessage(failure);
    storageUnavailable.value =
      failure instanceof ApiError &&
      (failure.code === "STORAGE_UNAVAILABLE" ||
        failure.status === 0 ||
        failure.status >= 500);
  } finally {
    deleting.value = false;
  }
}
function icon(file: StorageFile) {
  return isImage(file)
    ? "image"
    : isStorageVideo(file.name)
      ? "video"
      : /\.(zip|rar|7z|gz|tar)$/i.test(file.name)
        ? "archive"
        : "file-text";
}
function isImage(file: StorageFile) {
  return isStorageImage(file.name);
}
function time(value: string) {
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
}
// 两个路由复用页面组件，切换时清空原分类状态，序号机制阻止旧请求覆盖新分类。
watch(
  () => props.category,
  () => {
    closePreview();
    viewMode.value = props.category === "attachments" ? "list" : "grid";
    files.value = [];
    search.value = "";
    appliedKeyword.value = "";
    page.value = 1;
    total.value = 0;
    totalSize.value = 0;
    pending.value = null;
    selected.value = [];
    uploadOpen.value = route.query.upload === "1";
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++sequence;
  closePreview();
});
</script>

<template>
  <div class="files-page">
    <div class="page-heading">
      <div>
        <h1>{{ title }}</h1>
        <p>
          {{
            isImages
              ? "集中管理企业图片，支持缩略图与在线预览"
              : isVideos
                ? "集中管理企业视频，支持上传、在线播放与下载"
                : "集中管理文档、表格和压缩包等附件，安全预览与下载"
          }}
        </p>
      </div>
      <Button
        v-if="can('storage:file:upload')"
        theme="solid"
        :onClick="() => (uploadOpen = true)"
      >
        <AppIcon name="upload" :size="17" />上传{{ noun }}
      </Button>
    </div>
    <div class="file-overview">
      <div class="file-summary-card">
        <span class="summary-icon blue"
          ><AppIcon :name="categoryIcon" :size="25"
        /></span>
        <div>
          <small>符合条件的{{ noun }}</small
          ><strong>{{ total }}<em>个</em></strong>
        </div>
      </div>
      <div class="file-summary-card">
        <span class="summary-icon purple"
          ><AppIcon name="disk" :size="25"
        /></span>
        <div>
          <small>{{ noun }}总大小</small
          ><strong>{{ formatSize(totalSize) }}</strong>
        </div>
      </div>
    </div>
    <section class="panel file-panel" :aria-busy="loading">
      <form class="file-toolbar" @submit.prevent="query">
        <div class="file-tools">
          <Input
            :value="search"
            :placeholder="'搜索' + noun + '名称'"
            :maxLength="100"
            :onChange="(v: string) => (search = v)"
          />
          <Button htmlType="submit" theme="solid" aria-label="查询"
            >查询</Button
          >
          <Button
            aria-label="重置"
            :onClick="
              () => {
                search = '';
                query();
              }
            "
            >重置</Button
          >
        </div>
        <div class="storage-toolbar-actions" role="group" aria-label="展示设置">
          <button
            type="button"
            class="icon-button storage-view-toggle"
            :class="{ 'selected-icon': viewMode === 'list' }"
            :aria-label="switchViewLabel"
            :title="switchViewLabel"
            @click="viewMode = viewMode === 'grid' ? 'list' : 'grid'"
          >
            <AppIcon :name="viewMode === 'grid' ? 'list' : 'grid'" :size="19" />
          </button>
          <button
            type="button"
            class="icon-button"
            aria-label="刷新文件"
            title="刷新"
            @click="load"
          >
            <AppIcon name="refresh" :size="19" />
          </button>
        </div>
      </form>
      <div v-if="error" class="empty-state" role="alert">
        <h3>文件加载失败</h3>
        <p>{{ error }}</p>
        <Button :onClick="load">重试</Button>
      </div>
      <template v-else>
        <div v-if="viewMode === 'grid'" class="file-grid">
          <article v-for="file in files" :key="file.id" class="file-card">
            <button
              v-if="isImage(file) && can('storage:file:download')"
              type="button"
              class="file-preview storage-thumbnail-button"
              :aria-label="'预览图片' + file.name"
              @click="preview(file)"
            >
              <StorageThumbnail :file="file" />
            </button>
            <button
              v-else-if="
                isStorageVideo(file.name) && can('storage:file:download')
              "
              type="button"
              class="file-preview storage-thumbnail-button"
              :aria-label="'播放视频' + file.name"
              @click="preview(file)"
            >
              <span class="file-symbol"
                ><AppIcon name="video" :size="43"
              /></span>
            </button>
            <div v-else class="file-preview">
              <span class="file-symbol"
                ><AppIcon :name="icon(file)" :size="43"
              /></span>
            </div>
            <div class="file-card-details">
              <strong class="file-name" :title="file.name">{{
                file.name
              }}</strong>
              <p>
                {{ formatSize(file.size)
                }}<span>{{ storageProviderLabel(file.provider) }}</span>
              </p>
              <p :title="time(file.createdAt)">{{ time(file.createdAt) }}</p>
              <div class="file-card-bottom">
                <span>{{ file.owner }}</span>
                <div>
                  <button
                    v-if="can('storage:file:download')"
                    class="icon-button"
                    title="预览"
                    :aria-label="'预览' + file.name"
                    @click="preview(file)"
                  >
                    <AppIcon name="eye" :size="16" />
                  </button>
                  <button
                    v-if="can('storage:file:download')"
                    class="icon-button"
                    :aria-label="'下载' + file.name"
                    @click="download(file)"
                  >
                    <AppIcon name="download" :size="16" />
                  </button>
                  <button
                    v-if="can('storage:file:delete')"
                    class="icon-button"
                    :aria-label="'删除' + file.name"
                    @click="confirmDelete(file)"
                  >
                    <AppIcon name="trash" :size="16" />
                  </button>
                </div>
              </div>
            </div>
          </article>
        </div>
        <div
          v-else
          class="table-scroll storage-list"
          role="region"
          :aria-label="noun + '列表区域'"
          tabindex="0"
        >
          <table
            class="data-table storage-list-table"
            :aria-label="noun + '列表'"
          >
            <colgroup>
              <col />
              <col style="width: 100px" />
              <col style="width: 120px" />
              <col style="width: 110px" />
              <col style="width: 180px" />
              <col style="width: 130px" />
            </colgroup>
            <thead>
              <tr>
                <th>{{ noun }}名称</th>
                <th>文件大小</th>
                <th>存储位置</th>
                <th>上传人</th>
                <th>上传时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="file in files" :key="file.id">
                <td>
                  <div class="storage-row-file">
                    <button
                      v-if="isImage(file) && can('storage:file:download')"
                      type="button"
                      class="storage-row-thumb storage-thumbnail-button"
                      :aria-label="'预览图片' + file.name"
                      @click="preview(file)"
                    >
                      <StorageThumbnail :file="file" />
                    </button>
                    <span v-else class="storage-row-icon"
                      ><AppIcon :name="icon(file)" :size="24"
                    /></span>
                    <button
                      v-if="can('storage:file:download')"
                      type="button"
                      class="text-button storage-row-name"
                      :title="file.name"
                      @click="preview(file)"
                    >
                      {{ file.name }}
                    </button>
                    <span v-else class="storage-row-name" :title="file.name">{{
                      file.name
                    }}</span>
                  </div>
                </td>
                <td>{{ formatSize(file.size) }}</td>
                <td>
                  {{ storageProviderLabel(file.provider) }}
                </td>
                <td>{{ file.owner }}</td>
                <td>{{ time(file.createdAt) }}</td>
                <td>
                  <div class="storage-row-actions">
                    <button
                      v-if="can('storage:file:download')"
                      type="button"
                      class="icon-button"
                      title="预览"
                      :aria-label="'预览' + file.name"
                      @click="preview(file)"
                    >
                      <AppIcon name="eye" :size="16" />
                    </button>
                    <button
                      v-if="can('storage:file:download')"
                      type="button"
                      class="icon-button"
                      title="下载"
                      :aria-label="'下载' + file.name"
                      @click="download(file)"
                    >
                      <AppIcon name="download" :size="16" />
                    </button>
                    <button
                      v-if="can('storage:file:delete')"
                      type="button"
                      class="icon-button"
                      title="删除"
                      :aria-label="'删除' + file.name"
                      @click="confirmDelete(file)"
                    >
                      <AppIcon name="trash" :size="16" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!loading && !files.length" class="empty-state">
          <AppIcon :name="categoryIcon" :size="40" />
          <h3>暂无符合条件的{{ noun }}</h3>
          <p>上传{{ noun }}，或试试其他搜索条件</p>
        </div>
        <div class="table-pagination">
          <span>共 {{ total }} 个{{ noun }}</span
          ><Pagination
            :total="total"
            :pageSize="12"
            :currentPage="page"
            :onPageChange="
              (v: number) => {
                page = v;
                load();
              }
            "
          />
        </div>
      </template>
    </section>
    <Modal
      :visible="!!previewFile"
      :title="'文件预览 · ' + (previewFile?.name || '')"
      :style="{
        width: previewKind === 'office' ? '1100px' : '860px',
        maxWidth: 'calc(100vw - 32px)',
      }"
      :footer="''"
      :onCancel="closePreview"
    >
      <div
        v-if="previewFile"
        class="storage-preview"
        :aria-busy="previewLoading"
      >
        <p v-if="previewLoading" class="file-preview-large" role="status">
          正在加载预览…
        </p>
        <div v-else-if="previewError" class="file-preview-large" role="alert">
          <p>{{ previewError }}</p>
          <Button :onClick="() => previewFile && preview(previewFile)"
            >重新加载</Button
          >
        </div>
        <template v-else-if="previewKind">
          <OfficeDocumentPreview
            v-if="previewKind === 'office'"
            :key="previewFile.id"
            :id="previewFile.id"
          />
          <img
            v-else-if="previewKind === 'image'"
            class="image-preview"
            :src="previewUrl"
            :alt="previewFile.name"
            @error="previewError = '图片无法预览，请下载后查看'"
          />
          <PdfDocumentPreview
            v-else-if="previewKind === 'pdf'"
            :key="previewFile.id"
            :id="previewFile.id"
          />
          <VideoDocumentPreview
            v-else-if="previewKind === 'video'"
            :key="previewFile.id"
            :id="previewFile.id"
          />
          <audio
            v-else-if="previewKind === 'audio'"
            controls
            preload="metadata"
            :src="previewUrl"
            @error="previewError = '当前浏览器无法播放此音频，请下载后查看'"
          />
          <template v-else>
            <pre class="text-preview">{{ previewText || "（空文本）" }}</pre>
            <p v-if="previewTruncated" class="muted">
              仅显示前 64 KiB，完整内容请下载查看。
            </p></template
          >
        </template>
        <div v-else class="file-preview-large">
          <AppIcon name="file-text" :size="48" />
          <p>此格式暂不支持在线预览，请下载后查看。</p>
        </div>
        <div class="storage-preview-footer">
          <span
            >{{ formatSize(previewFile.size) }} · {{ previewFile.owner }}</span
          >
          <Button
            v-if="can('storage:file:download')"
            theme="solid"
            :onClick="() => previewFile && download(previewFile)"
            ><AppIcon name="download" :size="16" />下载文件</Button
          >
        </div>
      </div>
    </Modal>
    <Modal
      :visible="uploadOpen"
      :title="'上传' + noun"
      :width="580"
      okText="开始上传"
      cancelText="取消"
      :okButtonProps="{ 'aria-label': '开始上传' }"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      :confirmLoading="uploading"
      :onOk="upload"
      :onCancel="
        () => {
          if (!uploading) uploadOpen = false;
        }
      "
    >
      <div
        class="upload-zone"
        @dragover.prevent
        @drop.prevent="choose($event.dataTransfer?.files || null)"
      >
        <AppIcon name="cloud" :size="46" />
        <h3>
          将文件拖到此处，或<button
            class="text-button"
            :disabled="uploading"
            @click="input?.click()"
          >
            点击选择
          </button>
        </h3>
        <p>{{ uploadHint }}</p>
        <input
          ref="input"
          type="file"
          :accept="isImages ? imageAccept : isVideos ? videoAccept : undefined"
          multiple
          hidden
          :disabled="uploading"
          @change="choose(($event.target as HTMLInputElement).files)"
        />
      </div>
      <div
        v-for="(file, i) in selected"
        :key="file.name + file.size"
        class="upload-file"
      >
        <AppIcon name="file-text" :size="18" /><span>{{ file.name }}</span
        ><small>{{ formatSize(file.size) }}</small
        ><button
          class="icon-button"
          aria-label="移除待上传文件"
          :disabled="uploading"
          @click="selected.splice(i, 1)"
        >
          <AppIcon name="close" :size="15" />
        </button>
      </div>
      <p class="local-file-note">
        上传成功后保存到企业文件存储，可跨浏览器访问。
      </p>
    </Modal>
    <Modal
      :visible="!!pending"
      title="删除文件"
      :width="540"
      :okText="deleteConfirmText"
      cancelText="取消"
      :confirmLoading="deleting"
      :okButtonProps="{ 'aria-label': deleteConfirmText }"
      :cancelButtonProps="{ 'aria-label': '取消', disabled: deleting }"
      :onOk="remove"
      :onCancel="
        () => {
          if (!deleting) pending = null;
        }
      "
    >
      <p class="storage-delete-name">确认删除「{{ pending?.name }}」吗？</p>
      <fieldset class="storage-delete-modes" :disabled="deleting">
        <legend>删除方式</legend>
        <label :class="{ selected: deleteMode === 'all' }"
          ><input
            v-model="deleteMode"
            type="radio"
            name="storage-delete-mode"
            value="all"
            aria-label="删除文件和记录"
          /><span
            ><strong>删除文件和记录</strong
            ><small>删除存储中的文件，并从本系统列表移除。</small></span
          ></label
        >
        <label :class="{ selected: deleteMode === 'record' }"
          ><input
            v-model="deleteMode"
            type="radio"
            name="storage-delete-mode"
            value="record"
            aria-label="仅移除记录"
          /><span
            ><strong>仅移除记录</strong
            ><small>适用于存储已停用、无法连接或配置失效的文件。</small></span
          ></label
        >
      </fieldset>
      <p v-if="deleteMode === 'record'" class="storage-delete-notice">
        <AppIcon name="alert" :size="17" /><span
          >仅从本系统移除记录，不连接存储服务器。远端文件可能仍存在，系统不会自动补删。</span
        >
      </p>
      <div v-if="deleteError" class="storage-delete-error" role="alert">
        <p>{{ deleteError }}</p>
        <p v-if="storageUnavailable && deleteMode === 'all'">
          如果该存储已失效，可选择“仅移除记录”后确认，从列表中移除此文件。
        </p>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
/* 两种删除方式均需显式确认；仅移除记录时清楚说明远端文件不会被删除。 */
.storage-delete-name {
  overflow-wrap: anywhere;
  line-height: 1.7;
}
.storage-delete-modes {
  padding: 0;
  margin: 20px 0;
  border: 0;
  min-width: 0;
}
.storage-delete-modes legend {
  font-size: 12px;
  color: #8394af;
  margin-bottom: 10px;
}
.storage-delete-modes label {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 12px;
  border: 1px solid #e3eaf5;
  border-radius: 8px;
  cursor: pointer;
}
.storage-delete-modes label.selected {
  border-color: #1677ff;
  background: #f5f9ff;
}
.storage-delete-modes input {
  flex-shrink: 0;
  margin: 3px 0 0;
  accent-color: #1677ff;
}
.storage-delete-modes strong {
  display: block;
  color: #304b72;
  font-size: 14px;
  font-weight: 600;
}
.storage-delete-modes small {
  display: block;
  color: #879bb9;
  font-size: 12px;
  line-height: 1.7;
  margin-top: 5px;
}
.storage-delete-modes:disabled label {
  cursor: wait;
  opacity: 0.7;
}
.storage-delete-notice {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 12px 14px;
  background: #fff8e9;
  color: #a77820;
  border-radius: 6px;
  line-height: 1.7;
  font-size: 12px;
}
.storage-delete-notice svg {
  flex-shrink: 0;
  margin-top: 2px;
}
.storage-delete-error {
  padding: 12px 14px;
  background: #fff2f2;
  color: #d94b57;
  font-size: 12px;
  border-radius: 6px;
  line-height: 1.7;
}
.storage-delete-error p {
  margin: 0;
}
.storage-delete-error p + p {
  margin-top: 6px;
}
/* 视图切换固定在刷新左侧；列表只改变排版，继续复用同一组文件和操作权限。 */
.storage-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex-shrink: 0;
}
.storage-view-toggle:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: 2px;
}
.storage-list {
  margin-top: 24px;
  max-width: 100%;
}
.storage-list-table {
  min-width: 900px;
  table-layout: fixed;
}
.storage-row-file {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.storage-row-thumb,
.storage-row-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  overflow: hidden;
  border-radius: 6px;
  background: #f1f6ff;
  color: #649bdf;
}
.storage-row-thumb :deep(.thumbnail-placeholder small) {
  display: none;
}
.storage-row-name {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}
.storage-row-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
@media (max-width: 800px) {
  .storage-toolbar-actions {
    padding: 8px 0;
  }
}
/* 预览弹窗复用文件卡片样式，限制媒体高度，手机端也可完整操作。 */
.storage-thumbnail-button {
  border: 0;
  padding: 0;
  cursor: pointer;
}
.storage-thumbnail-button:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: -2px;
}
.storage-preview {
  min-width: 0;
}
.storage-preview .image-preview {
  display: block;
  max-height: 60vh;
}
.storage-preview-pdf {
  display: block;
  width: 100%;
  height: 60vh;
  border: 0;
}
.storage-preview video {
  display: block;
  width: 100%;
  max-height: 60vh;
  background: #111827;
}
.storage-preview audio {
  display: block;
  width: 100%;
}
.storage-preview-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 20px;
  color: #7b8da8;
  font-size: 12px;
}
</style>
