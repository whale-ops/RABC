<script setup lang="ts">
/**
 * 封装 ECharts 的图表实例、配置更新、容器尺寸变化和销毁。
 * 关联模块：Dashboard.vue、Monitor.vue 等原型视图提供图表配置，本组件负责渲染与资源清理。
 */
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
import * as echarts from "echarts/core";
import { LineChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
echarts.use([
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
]);
const props = defineProps<{ option: Record<string, any> }>();
const el = ref<HTMLElement>();
let chart: echarts.ECharts | undefined;
let observer: ResizeObserver;
onMounted(() => {
  chart = echarts.init(el.value!);
  chart.setOption(props.option);
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(el.value!);
});
watch(
  () => props.option,
  (option) => chart?.setOption(option, true),
  { deep: true },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div ref="el" class="chart-canvas" role="img" aria-label="数据统计图表" />
</template>
