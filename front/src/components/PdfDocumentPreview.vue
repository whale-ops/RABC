<script setup lang="ts">
/**
 * 页面内 PDF 阅读器：本地 Worker 渲染当前页，支持翻页、缩放和自适应宽度。
 * 关联模块：storageApi.download 读取鉴权文件；字体和 CMap 由本项目提供，不依赖浏览器 PDF 插件。
 */
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type RenderTask } from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { Button } from "../ui";
import { storageApi } from "../api/storage";
import { errorMessage } from "../api/request";

GlobalWorkerOptions.workerSrc = workerUrl;
const props = defineProps<{ id: string }>();
const canvas = ref<HTMLCanvasElement>(), pane = ref<HTMLElement>();
const loading = ref(true), rendering = ref(false), error = ref("");
const currentPage = ref(1), pages = ref(0), zoom = ref(1), retry = ref(0);
let document: PDFDocumentProxy | undefined;
let renderingTask: RenderTask | undefined;
let renderSequence = 0;
let resizeTimer: ReturnType<typeof setTimeout> | undefined;
let observer: ResizeObserver | undefined;

async function renderPage() {
  if (!document || !canvas.value || !pane.value) return;
  const sequence = ++renderSequence;
  renderingTask?.cancel();
  rendering.value = true;
  try {
    const page = await document.getPage(currentPage.value);
    if (sequence !== renderSequence || !canvas.value || !pane.value) return;
    const original = page.getViewport({ scale: 1 });
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    const fit = Math.max(0.1, (pane.value.clientWidth - 24) / original.width);
    // 限制单页像素，避免超大页面在高分屏上生成过量 Canvas 内存。
    const scale = Math.min(fit * zoom.value, Math.sqrt(16_000_000 / (original.width * original.height * ratio * ratio)));
    const viewport = page.getViewport({ scale });
    canvas.value.width = Math.ceil(viewport.width * ratio);
    canvas.value.height = Math.ceil(viewport.height * ratio);
    canvas.value.style.width = `${viewport.width}px`;
    canvas.value.style.height = `${viewport.height}px`;
    const context = canvas.value.getContext("2d");
    if (!context) throw new Error("当前浏览器无法绘制 PDF");
    renderingTask = page.render({canvas: canvas.value, canvasContext: context, viewport, transform: [ratio, 0, 0, ratio, 0, 0]});
    await renderingTask.promise;
  } catch (failure) {
    if (sequence === renderSequence && (failure as Error).name !== "RenderingCancelledException") error.value = "PDF 页面渲染失败，请重新加载或下载查看";
  } finally { if (sequence === renderSequence) rendering.value = false; }
}

watch([() => props.id, retry], async ([id], _, onCleanup) => {
  const controller = new AbortController();
  let active = true;
  let task: ReturnType<typeof getDocument> | undefined;
  onCleanup(() => {
    active = false; controller.abort(); ++renderSequence; renderingTask?.cancel();
    document = undefined;
    void task?.destroy().catch(() => {});
  });
  loading.value = true; error.value = ""; pages.value = 0; currentPage.value = 1; zoom.value = 1;
  try {
    const blob = await storageApi.download(String(id), controller.signal);
    const data = new Uint8Array(await blob.arrayBuffer());
    if (!active) return;
    const assets = new URL(`${import.meta.env.BASE_URL}pdfjs/`, window.location.origin).href;
    task = getDocument({data, cMapUrl: assets + "cmaps/", cMapPacked: true,
      standardFontDataUrl: assets + "standard_fonts/", wasmUrl: assets + "wasm/",
      enableXfa: false, useSystemFonts: true, maxImageSize: 16_000_000, canvasMaxAreaInBytes: 64_000_000});
    const value = await task.promise;
    if (!active) { await task.destroy(); return; }
    document = value; pages.value = value.numPages;
    await nextTick(); await renderPage();
  } catch (failure) {
    if (active) error.value = (failure as Error).name === "PasswordException" ? "该 PDF 已加密，请下载后输入密码查看" : errorMessage(failure);
  } finally { if (active) loading.value = false; }
}, { immediate: true });
watch([currentPage, zoom], () => { if (!loading.value) void renderPage(); });
watch(pane, value => { observer?.disconnect(); if (value) observer?.observe(value); });
onMounted(() => {
  let width = 0;
  observer = new ResizeObserver(entries => {
    const next = entries[0]?.contentRect.width || 0;
    if (Math.abs(next - width) < 1) return;
    width = next; clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { if (!loading.value) void renderPage(); }, 100);
  });
  if (pane.value) observer.observe(pane.value);
});
onBeforeUnmount(() => { observer?.disconnect(); clearTimeout(resizeTimer); });
</script>

<template>
  <div class="pdf-reader" :aria-busy="loading || rendering">
    <div v-if="error" class="pdf-error" role="alert"><p>{{ error }}</p><Button :onClick="() => retry++">重新加载</Button></div>
    <template v-else>
      <div class="pdf-toolbar" role="group" aria-label="PDF 阅读工具">
        <Button :disabled="currentPage <= 1 || loading || rendering" :onClick="() => currentPage--">上一页</Button>
        <span>第 {{ currentPage }} 页 / 共 {{ pages }} 页</span>
        <Button :disabled="currentPage >= pages || loading || rendering" :onClick="() => currentPage++">下一页</Button>
        <div class="pdf-zoom"><button type="button" aria-label="缩小 PDF" :disabled="zoom <= 0.5 || loading" @click="zoom = Math.max(0.5, zoom - 0.25)">−</button><span>{{ Math.round(zoom * 100) }}%</span><button type="button" aria-label="放大 PDF" :disabled="zoom >= 2 || loading" @click="zoom = Math.min(2, zoom + 0.25)">+</button></div>
      </div>
      <p v-if="loading" class="pdf-loading" role="status">正在加载 PDF…</p>
      <div ref="pane" class="pdf-pages" tabindex="0" aria-label="PDF 页面区域"><canvas v-show="!loading" ref="canvas" role="img" :aria-label="'PDF 第 ' + currentPage + ' 页'"></canvas></div>
    </template>
  </div>
</template>

<style scoped>
.pdf-reader { min-width: 0; }
.pdf-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; font-size: 13px; color: #637b99; }
.pdf-zoom { display: flex; align-items: center; gap: 12px; margin-left: auto; }
.pdf-zoom button { width: 30px; height: 30px; border: 1px solid #dce5f1; border-radius: 5px; background: #fff; color: #1677ff; font-size: 20px; }
.pdf-zoom button:disabled { color: #b5c0cf; }
.pdf-pages { max-height: 60vh; overflow: auto; padding: 12px; background: #e9edf3; min-width: 0; }
.pdf-pages canvas { display: block; margin: 0 auto; background: white; box-shadow: 0 2px 10px #1f365221; }
.pdf-loading, .pdf-error { padding: 40px 12px; text-align: center; color: #7b8da8; }
@media (max-width: 600px) { .pdf-toolbar { gap: 6px; font-size: 12px; } .pdf-zoom { margin-left: 0; } }
</style>
