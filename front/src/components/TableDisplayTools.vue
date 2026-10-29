<script setup lang="ts">
/** 表格刷新与列显示设置；可放在筛选行或操作栏，按钮不触发表单提交。 */
import { ref } from "vue";
import AppIcon from "./AppIcon.vue";
import ListLoadingStatus from "./ListLoadingStatus.vue";
const props = defineProps<{
  columns: { key: string; label: string }[];
  hiddenColumns: string[];
  refreshing?: boolean;
}>();
const emit = defineEmits<{
  refresh: [];
  "update:hiddenColumns": [keys: string[]];
}>();
const open = ref(false);
function toggle(key: string) {
  emit(
    "update:hiddenColumns",
    props.hiddenColumns.includes(key)
      ? props.hiddenColumns.filter((k) => k !== key)
      : [...props.hiddenColumns, key],
  );
}
</script>
<template>
  <div class="toolbar-right">
    <ListLoadingStatus :loading="refreshing" />
    <button
      type="button"
      class="icon-button bordered"
      :aria-disabled="!!refreshing"
      aria-label="刷新列表"
      title="刷新列表"
      @click="!refreshing && emit('refresh')"
    >
      <AppIcon name="refresh" :size="17" />
    </button>
    <div class="column-picker">
      <button
        type="button"
        class="icon-button bordered"
        aria-label="设置显示列"
        title="设置显示列"
        :aria-expanded="open"
        @click="open = !open"
      >
        <AppIcon name="sliders" :size="17" />
      </button>
      <div v-if="open" class="column-popover">
        <strong>显示列</strong
        ><label v-for="column in columns" :key="column.key"
          ><input
            type="checkbox"
            :checked="!hiddenColumns.includes(column.key)"
            @change="toggle(column.key)"
          />{{ column.label }}</label
        >
      </div>
    </div>
  </div>
</template>
