<script setup lang="ts">
/** 列表请求较慢时在固定位置提示，快速请求不切换按钮图标、颜色或尺寸。 */
import { onBeforeUnmount, ref, watch } from "vue";
const props = defineProps<{ loading?: boolean }>();
const visible = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
watch(
  () => props.loading,
  (loading) => {
    clearTimeout(timer);
    visible.value = false;
    if (loading) timer = setTimeout(() => (visible.value = true), 250);
  },
  { immediate: true },
);
onBeforeUnmount(() => clearTimeout(timer));
</script>
<template>
  <span class="list-loading-status" role="status" aria-live="polite">{{
    visible ? "正在更新数据…" : ""
  }}</span>
</template>
<style scoped>
.list-loading-status {
  display: inline-block;
  width: 108px;
  min-height: 20px;
  flex-shrink: 0;
  color: #8292ae;
  font-size: 12px;
  line-height: 20px;
  text-align: right;
}
@media (max-width: 800px) {
  .list-loading-status {
    width: 84px;
    font-size: 11px;
  }
}
</style>
