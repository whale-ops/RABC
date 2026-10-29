<script setup lang="ts">
/** 视频预览：取得后台鉴权的短期地址，浏览器直接按 Range 加载 OSS / MinIO。
 * 关联模块：storageApi.playback 校验租户与下载权限；关闭弹窗即停止传输，不保存签名地址。
 */
import { onBeforeUnmount, onMounted, ref } from "vue";
import { storageApi } from "../api/storage";
import { errorMessage } from "../api/request";
import { Button } from "../ui";
const props = defineProps<{ id: string }>();
const player = ref<HTMLVideoElement>();
const url = ref("");
const loading = ref(true);
const buffering = ref(false);
const error = ref("");
let expiresAt = 0;
let resumeAt = 0;
let resumePlaying = false;
let disposed = false;
let controller: AbortController | undefined;
async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  buffering.value = false;
  try {
    const result = await storageApi.playback(props.id, controller.signal);
    if (disposed) return;
    url.value = result.url;
    expiresAt = result.expiresAt;
  } catch (failure) {
    if (!disposed && !controller.signal.aborted) error.value = errorMessage(failure);
  } finally { if (!disposed) loading.value = false; }
}
function ready() {
  buffering.value = false;
  if (player.value && resumeAt > 0) player.value.currentTime = resumeAt;
  if (resumePlaying) void player.value?.play().catch(() => {});
  resumeAt = 0;
  resumePlaying = false;
}
function failed() {
  if (disposed || loading.value) return;
  // 长时间打开后签名过期，重新授权并恢复播放位置；编码错误不循环重试。
  if (Date.now() >= expiresAt - 10000) {
    resumeAt = player.value?.currentTime || 0;
    resumePlaying = !!player.value && !player.value.paused;
    void load();
    return;
  }
  buffering.value = false;
  error.value = "视频加载失败，请检查网络或重试；若浏览器不支持该编码，可下载后查看。";
}
onMounted(load);
onBeforeUnmount(() => {
  disposed = true;
  controller?.abort();
  if (player.value) {
    player.value.pause();
    player.value.removeAttribute("src");
    player.value.load();
  }
});
</script>
<template>
  <section class="video-reader" :aria-busy="loading || buffering" aria-label="视频播放器">
    <div v-if="error" class="video-feedback" role="alert"><p>{{ error }}</p><Button :onClick="load">重新加载</Button></div>
    <template v-else>
      <video v-if="url" ref="player" :src="url" controls playsinline preload="metadata"
        @loadstart="buffering = true" @loadedmetadata="ready" @waiting="buffering = true" @canplay="buffering = false"
        @playing="buffering = false" @pause="buffering = false" @error="failed" />
      <p v-if="loading || buffering" class="video-status" role="status">{{ loading ? '正在连接视频…' : '正在缓冲…' }}</p>
    </template>
  </section>
</template>
<style scoped>
.video-reader { position: relative; min-height: 220px; border-radius: 6px; overflow: hidden; background: #111827; }
.video-reader video { display: block; width: 100%; max-height: 60vh; }
.video-feedback { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; min-height: 220px; padding: 24px; color: #dbe7f8; text-align: center; }
.video-status { position: absolute; top: 12px; left: 12px; margin: 0; padding: 6px 12px; border-radius: 4px; background: #111827cc; color: #fff; font-size: 13px; pointer-events: none; }
</style>
