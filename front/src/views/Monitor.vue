<script setup lang="ts">
/**
 * 展示原型服务监控与缓存面板，提供本地模拟刷新和清理反馈。
 * 关联模块：使用 ChartPanel.vue 绘图；尚未对接后台监控接口，不会修改真实 Redis 数据。
 */
import { ref, computed } from "vue";
import { useRoute } from "vue-router";
import { Button, Modal, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import ChartPanel from "../components/ChartPanel.vue";
const route = useRoute();
const cache = computed(() => route.params.view === "cache");
const refreshedAt = ref("16:32:10");
const refreshCount = ref(0);
const confirming = ref(false);
const cleaned = ref(false);
const metrics = computed(() =>
  cache.value
    ? [
        {
          label: "缓存命中率",
          value: "98.6",
          suffix: "%",
          icon: "activity",
          color: "blue",
          progress: 98.6,
        },
        {
          label: "内存占用",
          value: cleaned.value ? "42.8" : "128.6",
          suffix: "MB",
          icon: "database",
          color: "purple",
          progress: cleaned.value ? 8 : 25,
        },
        {
          label: "缓存键数量",
          value: cleaned.value ? "368" : "2,486",
          suffix: "个",
          icon: "key",
          color: "mint",
          progress: 40,
        },
        {
          label: "连接客户端",
          value: "12",
          suffix: "个",
          icon: "network",
          color: "orange",
          progress: 12,
        },
      ]
    : [
        {
          label: "CPU 使用率",
          value: String(24.6 + (refreshCount.value % 4)),
          suffix: "%",
          icon: "cpu",
          color: "blue",
          progress: 24.6,
        },
        {
          label: "内存使用率",
          value: "48.2",
          suffix: "%",
          icon: "database",
          color: "purple",
          progress: 48.2,
        },
        {
          label: "磁盘使用率",
          value: "36.8",
          suffix: "%",
          icon: "disk",
          color: "mint",
          progress: 36.8,
        },
        {
          label: "系统运行时间",
          value: "32",
          suffix: "天",
          icon: "clock",
          color: "orange",
          progress: 65,
        },
      ],
);
const chart = computed(() => ({
  tooltip: { trigger: "axis" },
  legend: {
    data: cache.value ? ["命中率", "内存使用"] : ["CPU", "内存"],
    right: 15,
    top: 8,
    icon: "circle",
    itemWidth: 8,
    textStyle: { color: "#7c8ba5" },
  },
  grid: { left: 45, right: 24, top: 55, bottom: 30 },
  xAxis: {
    type: "category",
    data: [
      "15:40",
      "15:45",
      "15:50",
      "15:55",
      "16:00",
      "16:05",
      "16:10",
      "16:15",
      "16:20",
      "16:25",
      "16:30",
    ],
    axisTick: { show: false },
    axisLine: { lineStyle: { color: "#edf1f7" } },
    axisLabel: { color: "#8c9ab1" },
  },
  yAxis: {
    type: "value",
    max: 100,
    axisLabel: { formatter: "{value}%", color: "#8c9ab1" },
    splitLine: { lineStyle: { color: "#f0f3f8" } },
  },
  series: [
    {
      name: cache.value ? "命中率" : "CPU",
      type: "line",
      smooth: true,
      symbol: "none",
      data: cache.value
        ? [92, 95, 96, 94, 97, 98, 96, 99, 97, 98, 98.6]
        : [
            18,
            25,
            21,
            35,
            23,
            26,
            19,
            32,
            28,
            21,
            24.6 + (refreshCount.value % 4),
          ],
      lineStyle: { color: "#1681ff", width: 2.5 },
      itemStyle: { color: "#1681ff" },
      areaStyle: { color: "rgba(22,129,255,.08)" },
    },
    {
      name: cache.value ? "内存使用" : "内存",
      type: "line",
      smooth: true,
      symbol: "none",
      data: [42, 43, 46, 45, 48, 44, 49, 47, 48, 46, 48.2],
      lineStyle: { color: "#21c9a0", width: 2 },
      itemStyle: { color: "#21c9a0" },
    },
  ],
}));
function refresh() {
  refreshedAt.value = new Date().toLocaleTimeString("zh-CN");
  refreshCount.value++;
  Toast.success("演示指标已刷新");
}
function clearCache() {
  cleaned.value = true;
  confirming.value = false;
  Toast.success("演示缓存已清理，业务数据不受影响");
}
</script>
<template>
  <div class="monitor-page">
    <div class="page-heading">
      <div>
        <h1>{{ cache ? "缓存监控" : "服务监控" }}</h1>
        <p>
          掌握{{ cache ? "缓存使用情况" : "系统运行状态" }}，守护平台稳定运行
        </p>
      </div>
      <div class="monitor-heading-actions">
        <span class="status-pill green"><i />运行正常</span
        ><Button theme="outline" type="tertiary" :onClick="refresh"
          ><AppIcon name="refresh" :size="16" />刷新</Button
        >
      </div>
    </div>
    <div class="monitor-demo-notice">
      <AppIcon
        name="activity"
        :size="16"
      />当前为前端演示指标，尚未连接服务器监控数据。<span
        >更新于 {{ refreshedAt }}</span
      >
    </div>
    <div class="monitor-metrics">
      <section v-for="m in metrics" :key="m.label" class="panel monitor-metric">
        <div>
          <span class="summary-icon" :class="m.color"
            ><AppIcon :name="m.icon" :size="24" /></span
          ><span>{{ m.label }}</span>
        </div>
        <strong
          >{{ m.value }}<small>{{ m.suffix }}</small></strong
        >
        <div class="metric-track">
          <i :class="m.color" :style="{ width: m.progress + '%' }" />
        </div>
        <p>
          {{
            cache
              ? "缓存服务运行正常"
              : m.label === "内存使用率"
                ? "已用 7.71 GB / 总计 16 GB"
                : m.label === "磁盘使用率"
                  ? "已用 188.4 GB / 总计 512 GB"
                  : "运行状态良好"
          }}
        </p>
      </section>
    </div>
    <div class="monitor-middle">
      <section class="panel resource-chart">
        <div class="panel-heading">
          <h2>{{ cache ? "缓存性能趋势" : "资源使用趋势" }}</h2>
          <span class="muted">最近 1 小时</span>
        </div>
        <ChartPanel :option="chart" />
      </section>
      <section class="panel service-panel">
        <div class="panel-heading"><h2>服务健康状态</h2></div>
        <div
          v-for="service in [
            { name: '应用服务', sub: 'Spring Boot · Java 17', icon: 'server' },
            { name: '数据库', sub: 'MySQL 8.4', icon: 'database' },
            { name: '缓存服务', sub: 'Redis 7.x', icon: 'disk' },
            { name: '文件服务', sub: '对象存储', icon: 'cloud' },
          ]"
          :key="service.name"
          class="service-row"
        >
          <span class="notice-icon"
            ><AppIcon :name="service.icon" :size="22"
          /></span>
          <div>
            <strong>{{ service.name }}</strong
            ><small>{{ service.sub }}</small>
          </div>
          <span class="status-pill green"><i />正常</span>
        </div>
      </section>
    </div>
    <section class="panel server-info">
      <div class="panel-heading">
        <h2>{{ cache ? "Redis 信息" : "服务器信息" }}</h2>
        <Button
          v-if="cache"
          type="warning"
          theme="light"
          :onClick="() => (confirming = true)"
          >清理演示缓存</Button
        ><span v-else class="subtle-badge">演示环境</span>
      </div>
      <div class="server-info-grid">
        <dl
          v-for="item in cache
            ? [
                ['Redis 版本', '7.2.5'],
                ['运行模式', 'Standalone'],
                ['端口', '6379'],
                ['已处理请求', '1,248,639'],
                ['持久化策略', 'AOF + RDB'],
                ['运行时长', '32 天 8 小时'],
              ]
            : [
                ['服务器名称', 'star-admin-server'],
                ['操作系统', 'Ubuntu 22.04 LTS'],
                ['系统架构', 'amd64'],
                ['Java 版本', '17.0.12'],
                ['应用名称', 'StarAdmin'],
                ['应用版本', '1.0.0'],
                ['CPU 核心数', '8 核'],
                ['系统总内存', '16 GB'],
              ]"
          :key="item[0]"
        >
          <dt>{{ item[0] }}</dt>
          <dd>{{ item[1] }}</dd>
        </dl>
      </div>
    </section>
    <Modal
      :visible="confirming"
      title="清理演示缓存"
      :onCancel="
        () => {
          confirming = false;
        }
      "
      :onOk="clearCache"
      okText="确认清理"
      :okButtonProps="{ 'aria-label': '确认清理' }"
      cancelText="取消"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      >此操作仅更新缓存监控的演示数据，不会删除用户、角色或文件。</Modal
    >
  </div>
</template>
