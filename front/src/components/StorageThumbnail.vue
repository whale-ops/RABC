<script setup lang="ts">
/**
 * 文件卡片及列表缩略图：通过 storageApi 携带登录令牌读取 OSS / MinIO 中的真实图片。
 * 关联模块：StorageFiles.vue 先校验下载权限再渲染本组件；后台继续校验租户和所有者。
 */
import { ref, watch } from "vue";
import { storageApi, type StorageFile } from "../api/storage";
import AppIcon from "./AppIcon.vue";

const props = defineProps<{ file: StorageFile }>();
const url = ref("");
const loading = ref(true);
const failed = ref(false);
const imageTypes: Record<string, string> = {
  png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif",
  webp: "image/webp", bmp: "image/bmp", avif: "image/avif",
};

watch(() => props.file, async (file, _, onCleanup) => {
  const controller = new AbortController();
  let active = true;
  let objectUrl = "";
  // 列表刷新、删除或离开页面时取消旧请求，释放本组件持有的图片地址。
  onCleanup(() => {
    active = false;
    controller.abort();
    if (objectUrl) URL.revokeObjectURL(objectUrl);
  });
  url.value = "";
  failed.value = false;
  loading.value = true;
  try {
    const mime = imageTypes[file.name.split(".").pop()?.toLowerCase() || ""];
    if (!mime) throw new Error("不支持的图片类型");
    const blob = await storageApi.download(file.id, controller.signal);
    if (!active) return;
    // 后台下载以附件形式返回 octet-stream；使用明确的图片 MIME 生成临时预览地址。
    objectUrl = URL.createObjectURL(new Blob([blob], { type: mime }));
    url.value = objectUrl;
  } catch {
    if (active) failed.value = true;
  } finally {
    if (active) loading.value = false;
  }
}, { immediate: true });
</script>

<template>
  <img v-if="url && !failed" class="storage-thumbnail" :src="url" :alt="file.name" @error="failed = true" />
  <span v-else class="thumbnail-placeholder">
    <AppIcon name="image" :size="36" />
    <small v-if="loading" role="status">图片加载中…</small>
    <small v-else-if="failed">图片加载失败，点击重试</small>
  </span>
</template>

<style scoped>
.storage-thumbnail { display: block; width: 100%; height: 100%; object-fit: contain; }
.thumbnail-placeholder { display: flex; flex-direction: column; align-items: center; gap: 10px; color: #7a9bc8; }
.thumbnail-placeholder small { font-size: 11px; }
</style>
