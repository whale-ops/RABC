<script setup lang="ts">
/**
 * 展示原型首页的演示统计、快捷入口和趋势图，用于复现设计效果。
 * 关联模块：读取 store.ts，使用 ChartPanel.vue；真实模式的首页由 SystemHome.vue 调用后台数据。
 */
import { ref, computed } from "vue";
import { useRouter } from "vue-router";
import { Pagination } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import ChartPanel from "../components/ChartPanel.vue";
import StatusPill from "../components/StatusPill.vue";
import { db, ui } from "../data/store";
const router = useRouter();
const days = ref(7);
const page = ref(1);
const stats = computed(() => [
  {
    label: "用户总数",
    value: db.users.length.toLocaleString(),
    icon: "users",
    change: "12%",
    path: "/system/users",
  },
  {
    label: "项目总数",
    value: db.projects.length,
    icon: "file-text",
    change: "8%",
    path: "/business/projects",
  },
  {
    label: "系统运行时间",
    value: "32",
    suffix: "天",
    icon: "server",
    change: "5%",
    path: "/monitor/overview",
  },
  {
    label: "安全事件",
    value: "0",
    icon: "shield",
    change: "100%",
    path: "/audit/logins",
  },
]);
const quicks = [
  { label: "新增用户", icon: "users", path: "/system/users?create=1" },
  { label: "新增角色", icon: "shield", path: "/system/roles?create=1" },
  { label: "新增菜单", icon: "list", path: "/system/menus?create=1" },
  { label: "上传附件", icon: "cloud", path: "/attachments?upload=1" },
  { label: "查看日志", icon: "file-text", path: "/audit/operations" },
  { label: "服务监控", icon: "monitor", path: "/monitor/overview" },
];
const actions = [
  {
    icon: "users",
    text: "用户 张三 新增了一个项目",
    time: "29分钟前",
    path: "/business/projects",
  },
  {
    icon: "shield",
    text: "角色 运维人员 被创建",
    time: "159分钟前",
    path: "/system/roles",
  },
  {
    icon: "settings",
    text: "系统配置 已更新",
    time: "329分钟前",
    path: "/system/config",
  },
  {
    icon: "file-text",
    text: "文件 附件.zip 上传成功",
    time: "1小时前",
    path: "/attachments",
  },
  {
    icon: "user",
    text: "用户 李四 登录系统",
    time: "1小时前",
    path: "/audit/logins",
  },
];
const lineOption = computed(() => {
  const count = days.value;
  const labels = Array.from({ length: count }, (_, i) => {
    const d = new Date(2025, 8, 17 - count + i);
    return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  });
  const values =
    count === 7
      ? [280, 410, 600, 570, 750, 800, 1080]
      : labels.map((_, i) =>
          Math.round(280 + i * (700 / count) + Math.sin(i * 0.8) * 140),
        );
  return {
    animationDuration: 550,
    tooltip: {
      trigger: "axis",
      backgroundColor: "#fff",
      borderColor: "#eaf0f8",
      textStyle: { color: "#24385b", fontSize: 12 },
    },
    grid: { left: 47, right: 26, top: 24, bottom: 27 },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: labels,
      axisLine: { lineStyle: { color: "#eaf0f8" } },
      axisTick: { show: false },
      axisLabel: { color: "#7c90af", fontSize: 11 },
      splitLine: { show: true, lineStyle: { color: "#f0f5fb" } },
    },
    yAxis: {
      type: "value",
      min: 0,
      max: 1200,
      interval: 300,
      axisLabel: { color: "#7c90af", fontSize: 11 },
      splitLine: { lineStyle: { color: "#edf3fb" } },
    },
    series: [
      {
        type: "line",
        data: values,
        symbol: "circle",
        symbolSize: count > 7 ? 4 : 7,
        smooth: false,
        itemStyle: { color: "#fff", borderColor: "#1681ff", borderWidth: 2 },
        lineStyle: { color: "#1681ff", width: 2.5 },
        areaStyle: {
          color: {
            type: "linear",
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: "rgba(22,119,255,.20)" },
              { offset: 1, color: "rgba(22,119,255,.015)" },
            ],
          },
        },
      },
    ],
  };
});
const distributions = [
  { name: "系统管理", value: 32, color: "#2782ff" },
  { name: "业务管理", value: 24, color: "#6a98ff" },
  { name: "文件管理", value: 18, color: "#27cda3" },
  { name: "日志管理", value: 12, color: "#ffb745" },
  { name: "其他", value: 14, color: "#d3dce8" },
];
const pieOption = {
  tooltip: { trigger: "item", formatter: "{b}: {d}%" },
  series: [
    {
      type: "pie",
      radius: ["66%", "92%"],
      center: ["50%", "50%"],
      label: { show: false },
      borderWidth: 0,
      emphasis: { scaleSize: 5 },
      data: distributions.map((x) => ({
        name: x.name,
        value: x.value,
        itemStyle: { color: x.color },
      })),
    },
  ],
};
const records = computed(() =>
  db.operations.slice((page.value - 1) * 5, page.value * 5),
);
</script>
<template>
  <div class="dashboard">
    <section class="welcome-banner">
      <div class="welcome-copy">
        <h1>欢迎回来，{{ ui.profile.name }}</h1>
        <p>基于 Spring Boot + Vue3 的企业级快速开发平台</p>
        <div class="welcome-features">
          <span
            v-for="feature in [
              'RBAC 权限控制',
              '数据权限',
              '多租户支持',
              '快速开发',
            ]"
            :key="feature"
            ><i><AppIcon name="check" :size="10" :stroke-width="3" /></i
            >{{ feature }}</span
          >
        </div>
      </div>
      <img
        class="welcome-art"
        src="/illustrations/dashboard-hero.svg"
        alt="蓝色笔记本电脑和数据分析面板插画"
      />
    </section>
    <section class="stats-grid">
      <button
        v-for="stat in stats"
        :key="stat.label"
        class="stat-card"
        @click="router.push(stat.path)"
      >
        <span class="stat-icon"
          ><AppIcon :name="stat.icon" :size="29" :stroke-width="2.5"
        /></span>
        <div class="stat-info">
          <p>{{ stat.label }}</p>
          <strong
            >{{ stat.value
            }}<span v-if="stat.suffix">{{ stat.suffix }}</span></strong
          >
        </div>
        <div class="stat-change">
          <span
            ><AppIcon name="up" :size="13" :stroke-width="3" />{{
              stat.change
            }}</span
          ><small>较上周</small>
        </div>
      </button>
    </section>
    <div class="dashboard-lower">
      <div class="dashboard-main">
        <div class="charts-row">
          <section class="panel trend-panel">
            <div class="panel-heading">
              <h2>访问趋势</h2>
              <div class="segment-control">
                <button
                  v-for="n in [7, 30, 90]"
                  :key="n"
                  :class="{ selected: days === n }"
                  @click="days = n"
                >
                  {{ n }}天
                </button>
              </div>
            </div>
            <ChartPanel :option="lineOption" />
          </section>
          <section class="panel distribution-panel">
            <div class="panel-heading"><h2>业务类型分布</h2></div>
            <div class="distribution-body">
              <div class="donut-wrap">
                <ChartPanel :option="pieOption" />
                <div class="donut-center">
                  <span>总计</span><strong>{{ db.projects.length }}</strong>
                </div>
              </div>
              <div class="chart-legend">
                <div v-for="item in distributions" :key="item.name">
                  <i :style="{ background: item.color }" /><span>{{
                    item.name
                  }}</span
                  ><b>{{ item.value }}%</b>
                </div>
              </div>
            </div>
          </section>
        </div>
        <section class="panel recent-panel">
          <div class="panel-heading"><h2>最近操作记录</h2></div>
          <div class="table-scroll">
            <table class="data-table dashboard-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>操作人</th>
                  <th>操作类型</th>
                  <th>操作内容</th>
                  <th>操作时间</th>
                  <th>IP地址</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in records"
                  :key="row.id"
                  @dblclick="router.push('/audit/operations')"
                >
                  <td>{{ row.id.length > 8 ? row.id.slice(-6) : row.id }}</td>
                  <td>{{ row.operator }}</td>
                  <td><StatusPill :value="row.action" /></td>
                  <td>{{ row.name }}</td>
                  <td>{{ row.createdAt }}</td>
                  <td>{{ row.ip }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="recent-pagination">
            <Pagination
              :total="db.operations.length"
              :pageSize="5"
              :currentPage="page"
              :onPageChange="(p: number) => (page = p)"
            />
          </div>
        </section>
      </div>
      <div class="dashboard-side">
        <section class="panel quick-panel">
          <div class="panel-heading">
            <h2>快捷操作</h2>
            <RouterLink to="/profile">个人中心</RouterLink>
          </div>
          <div class="quick-grid">
            <button
              v-for="quick in quicks"
              :key="quick.label"
              @click="router.push(quick.path)"
            >
              <span
                ><AppIcon
                  :name="quick.icon"
                  :size="28"
                  :stroke-width="2.3" /></span
              >{{ quick.label }}
            </button>
          </div>
        </section>
        <section class="panel activity-panel">
          <div class="panel-heading">
            <h2>最新动态</h2>
            <RouterLink to="/audit/operations">更多</RouterLink>
          </div>
          <button
            v-for="action in actions"
            :key="action.text"
            class="activity-row"
            @click="router.push(action.path)"
          >
            <span class="activity-icon"
              ><AppIcon
                :name="action.icon"
                :size="21"
                :stroke-width="2.2" /></span
            ><span class="activity-text">{{ action.text }}</span
            ><time>{{ action.time }}</time>
          </button>
        </section>
      </div>
    </div>
  </div>
</template>
