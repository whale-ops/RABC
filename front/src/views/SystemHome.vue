<script setup lang="ts">
/** 首页按原型组织欢迎区、真实 PV/UV 趋势、业务分布、快捷操作及操作表格。
 * 关联模块：dashboardApi 读取租户统计，路由钩子采集浏览事件；ChartPanel 绘图，session 控制入口。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { Pagination } from '../ui';
import AppIcon from '../components/AppIcon.vue';
import ChartPanel from '../components/ChartPanel.vue';
import StatusPill from '../components/StatusPill.vue';
import { dashboardApi, waitForPageView, type HomeOverview, type HomeAnalytics, type HomeActivity } from '../api/dashboard';
import { can, session, visiblePaths } from '../api/session';
import { errorMessage } from '../api/request';
const router=useRouter();
const overview=ref<HomeOverview>(),analytics=ref<HomeAnalytics>(),records=ref<HomeActivity[]>([]);
const page=ref(1),total=ref(0),days=ref(7),scope=ref('self');
const error=ref(''),chartError=ref(''),loading=ref(false),chartLoading=ref(false);
let active=true,chartSequence=0,recordSequence=0;
const date=(value:string)=>new Date(value).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'});
const count=(value:number|undefined|null)=>value==null?'—':value.toLocaleString();
const rangeLabel=computed(()=>`近 ${analytics.value?.days||days.value} 天 · ${analytics.value?.visitScope==='tenant'?'本租户':'仅本人'}`);
const uptime=computed(()=>{const n=overview.value?.uptimeSeconds;if(n==null)return '—';return n>=86400?`${Math.floor(n/86400)} 天`:`${Math.floor(n/3600)} 小时 ${Math.floor(n%3600/60)} 分`;});
const cards=computed(()=>[
  {label:'用户总数',value:count(overview.value?.users),icon:'users',note:can('system:user:list')?'可访问的用户范围':'未授权用户统计'},
  {label:'浏览次数 PV',value:count(analytics.value?.pv),icon:'activity',note:rangeLabel.value},
  {label:'独立访客 UV',value:count(analytics.value?.uv),icon:'user',note:rangeLabel.value},
  {label:'系统运行时间',value:uptime.value,icon:'server',note:'当前 Java 后端进程'},
]);
const quicks=computed(()=>[
  {label:'新增用户',icon:'users',path:'/system/users?create=1',permission:'system:user:create'},
  {label:'新增角色',icon:'shield',path:'/system/roles?create=1',permission:'system:role:create'},
  {label:'新增菜单',icon:'list-tree',path:'/system/menus?create=1',permission:'system:menu:create'},
  {label:'上传附件',icon:'cloud',path:'/attachments?upload=1',permission:'storage:file:upload'},
  {label:'查看日志',icon:'file-text',path:'/audit/operations',permission:'audit:operation:list'},
  {label:'服务监控',icon:'monitor',path:'/monitor/overview',permission:'monitor:server:read'},
].filter(q=>can(q.permission)&&visiblePaths().includes(q.path.split('?')[0])));
const colors=['#2782ff','#27cda3','#9675ee','#ffb745','#61a5ec','#d3dce8'];
const distribution=computed(()=>(analytics.value?.distribution||[]).map((item,i)=>({...item,color:colors[i%colors.length]})));
// 单调平滑仅调整数据点之间的连线，保留真实计数，避免零值附近出现负向过冲。
const trendOption = computed(() => ({
  animation: false,
  tooltip: {
    trigger: 'axis', renderMode: 'richText', confine: true,
    backgroundColor: 'rgba(255,255,255,.97)', borderColor: '#e8eff9',
    padding: [12, 16], textStyle: { color: '#405778', fontSize: 12 },
    axisPointer: { type: 'line', lineStyle: { color: '#b8ccec', type: 'dashed' } },
  },
  legend: {
    data: ['PV', 'UV'], top: 6, right: 20, icon: 'circle',
    itemWidth: 9, itemHeight: 9, itemGap: 20, textStyle: { color: '#7c90af' },
  },
  grid: { left: 48, right: 25, top: 48, bottom: 32 },
  xAxis: {
    type: 'category', boundaryGap: false,
    data: analytics.value?.trend.map(p => p.date.slice(5)) || [],
    axisLine: { lineStyle: { color: '#eaf0f8' } }, axisTick: { show: false },
    axisLabel: { color: '#8a9bb5', margin: 12, hideOverlap: true },
    splitLine: { show: false },
  },
  yAxis: {
    type: 'value', min: 0, minInterval: 1, splitNumber: 4,
    axisLabel: { color: '#8a9bb5' },
    splitLine: { lineStyle: { color: '#eaf0f8', type: 'dashed' } },
  },
  series: (['PV', 'UV'] as const).map(name => {
    const isPv = name === 'PV';
    const color = isPv ? '#2782ff' : '#20bd97';
    return {
      name, type: 'line', smooth: 0.35, smoothMonotone: 'x',
      data: analytics.value?.trend.map(p => isPv ? p.pv : p.uv) || [],
      showSymbol: false, symbol: 'circle', symbolSize: 7,
      lineStyle: { color, width: isPv ? 3 : 2.5, cap: 'round', join: 'round' },
      itemStyle: { color, borderColor: '#fff', borderWidth: 2 },
      emphasis: { focus: 'series', scale: 1.4, lineStyle: { width: 3.5 } },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: isPv ? 'rgba(39,130,255,.20)' : 'rgba(32,189,151,.10)' },
            { offset: 1, color: isPv ? 'rgba(39,130,255,.01)' : 'rgba(32,189,151,0)' },
          ],
        },
      },
    };
  }),
}));
const pieOption=computed(()=>({animation:false,tooltip:{trigger:'item',renderMode:'richText',formatter:'{b}: {c} 次 ({d}%)'},series:[{type:'pie',radius:['66%','90%'],center:['50%','50%'],label:{show:false},data:distribution.value.map(item=>({name:item.name,value:item.value,itemStyle:{color:item.color}}))}]}));
async function loadRecords(){const current=++recordSequence;try{const data=await dashboardApi.activities(page.value);if(active&&current===recordSequence){records.value=data.records;total.value=data.total;scope.value=data.scope;}}catch(e){if(active&&current===recordSequence)error.value=errorMessage(e);}}
async function loadAnalytics(value=days.value){days.value=value;const current=++chartSequence;chartLoading.value=true;try{await waitForPageView();const data=await dashboardApi.analytics(value);if(active&&current===chartSequence){analytics.value=data;chartError.value='';}}catch(e){if(active&&current===chartSequence)chartError.value=errorMessage(e);}finally{if(active&&current===chartSequence)chartLoading.value=false;}}
async function load(){if(loading.value)return;loading.value=true;error.value='';try{const data=await dashboardApi.overview();if(active)overview.value=data;}catch(e){if(active)error.value=errorMessage(e);}await Promise.all([loadRecords(),loadAnalytics()]);if(active)loading.value=false;}
onMounted(load);onBeforeUnmount(()=>{active=false;++chartSequence;++recordSequence;});
</script>
<template>
  <div class="dashboard real-dashboard">
    <section class="welcome-banner"><div class="welcome-copy"><h1>欢迎回来，{{session.user?.name||'用户'}}</h1><p>基于 Spring Boot + Vue3 的企业级快速开发平台</p><div class="welcome-features"><span v-for="feature in ['RBAC 权限控制','数据权限','多租户支持','快速开发']" :key="feature"><i><AppIcon name="check" :size="10" :strokeWidth="3" /></i>{{feature}}</span></div></div><img class="welcome-art" src="/illustrations/dashboard-hero.svg" alt="企业管理工作台" /></section>
    <div v-if="error" class="storage-warning" role="alert">{{error}} <button class="text-button" @click="load">重新加载</button></div>
    <section class="stats-grid"><article v-for="card in cards" :key="card.label" class="stat-card"><span class="stat-icon"><AppIcon :name="card.icon" :size="29" /></span><div class="stat-info"><p>{{card.label}}</p><strong>{{card.value}}</strong><small class="home-stat-note">{{card.note}}</small></div></article></section>
    <div class="dashboard-lower"><div class="dashboard-main">
      <div class="charts-row">
        <section class="panel trend-panel" :aria-busy="chartLoading"><div class="panel-heading"><h2>访问趋势</h2><div class="segment-control" aria-label="访问统计周期"><button v-for="n in [7,30,90]" :key="n" :class="{selected:days===n}" :aria-pressed="days===n" @click="loadAnalytics(n)">{{n}}天</button></div></div>
          <p class="home-chart-note">PV：页面浏览次数 · UV：登录账号去重人数 · 北京时间</p>
          <div v-if="chartError" class="home-chart-error" role="alert">{{chartError}}<button class="text-button" @click="loadAnalytics()">重试</button></div>
          <ChartPanel v-else-if="analytics" :option="trendOption" /><div v-else class="home-chart-placeholder">正在读取访问统计…</div>
        </section>
        <section class="panel distribution-panel"><div class="panel-heading"><h2>业务类型分布</h2></div><p class="home-chart-note">近 {{analytics?.days||days}} 天操作日志 · {{analytics?.operationScope==='tenant'?'本租户':'仅本人'}}</p>
          <div v-if="analytics?.operationTotal" class="distribution-body"><div class="donut-wrap"><ChartPanel :option="pieOption" /><div class="donut-center"><span>总计</span><strong>{{count(analytics.operationTotal)}}</strong></div></div><div class="chart-legend"><div v-for="item in distribution" :key="item.name"><i :style="{background:item.color}" /><span>{{item.name}}</span><b>{{(item.value/analytics.operationTotal*100).toFixed(0)}}%</b></div></div></div>
          <div v-else class="home-chart-placeholder">{{chartLoading?'正在读取统计…':'当前周期暂无业务操作'}}</div>
        </section>
      </div>
      <section class="panel recent-panel"><div class="panel-heading"><h2>最近操作记录</h2><span class="muted home-scope">{{scope==='tenant'?'当前租户':'仅本人'}}</span><button class="text-button" @click="loadRecords">刷新</button></div><div class="table-scroll"><table class="data-table dashboard-table" aria-label="首页最近操作"><thead><tr><th>ID</th><th>操作人</th><th>操作类型</th><th>操作内容</th><th>操作时间</th><th>IP地址</th></tr></thead><tbody><tr v-for="row in records" :key="row.id"><td :title="row.id">{{row.id.slice(-6)}}</td><td>{{row.operator||'已删除用户'}}</td><td><StatusPill :value="row.action" /></td><td>{{row.action}} · {{row.resource}} <span v-if="row.status==='失败'" class="home-failed">失败</span></td><td>{{date(row.createdAt)}}</td><td>{{row.ip||'—'}}</td></tr><tr v-if="!records.length"><td colspan="6" class="home-empty">{{loading?'正在加载操作记录…':'暂无操作记录'}}</td></tr></tbody></table></div><div class="recent-pagination"><Pagination :total="total" :pageSize="5" :currentPage="page" :onPageChange="(value:number)=>{page=value;loadRecords();}" /></div></section>
    </div>
    <aside class="dashboard-side"><section class="panel quick-panel"><div class="panel-heading"><h2>快捷操作</h2><RouterLink to="/profile">个人中心</RouterLink></div><div class="quick-grid"><button v-for="quick in quicks" :key="quick.label" @click="router.push(quick.path)"><span><AppIcon :name="quick.icon" :size="28" /></span>{{quick.label}}</button><p v-if="!quicks.length" class="home-no-quicks">暂无已授权的快捷入口</p></div></section>
      <section class="panel activity-panel"><div class="panel-heading"><h2>最新动态</h2><button class="text-button" @click="load">刷新</button></div><div v-if="!overview?.activities.length" class="empty-state"><AppIcon name="file-text" :size="28" /><p>暂无动态</p></div><div v-for="item in overview?.activities" :key="item.id" class="home-activity"><span class="activity-icon"><AppIcon :name="item.status==='失败'?'alert':'check'" :size="20" /></span><div><strong>{{item.operator||'已删除用户'}} {{item.action}} · {{item.resource}}</strong><time>{{date(item.createdAt)}}</time></div></div></section>
      <section class="panel home-security"><div class="panel-heading"><h2>登录安全</h2><AppIcon name="shield" :size="18" /></div><p>近 {{analytics?.days||days}} 天登录失败 <strong>{{count(analytics?.loginFailures)}}</strong> 次</p><small>{{analytics?.visitScope==='tenant'?'本租户范围':'仅当前账号'}} · 失败登录不等同于已确认攻击事件</small></section>
    </aside></div>
    <p class="home-data-note">访问统计从本功能启用后开始采集，之前日期显示 0；同一账号跨浏览器去重，所选周期 UV 不等于每日 UV 相加。</p>
  </div>
</template>
<style scoped>
.real-dashboard .recent-panel { height:auto; }
.real-dashboard .stat-card { height:auto; min-height:114px; }
.real-dashboard .stat-info { min-width:0; }
@media(max-width:800px) {
  .real-dashboard .welcome-art { opacity:.14; }
  .real-dashboard .stat-card { min-height:125px; }
  .real-dashboard .stat-info { padding-left:10px; }
  .real-dashboard .stat-info strong { font-size:20px; }
  .real-dashboard .stat-icon { width:36px; height:36px; }
  .real-dashboard .home-stat-note { line-height:1.5; }
  .real-dashboard .recent-pagination { justify-content:center; padding-inline:6px; }
}
.real-dashboard .stat-card{cursor:default;}.real-dashboard .stat-card:hover{transform:none;}.home-stat-note{display:block;margin-top:5px;color:#8b9ab1;font-size:11px;}.real-dashboard .stat-info strong{font-size:28px;overflow-wrap:anywhere;}.home-chart-note{padding:0 20px;color:#899ab4;font-size:11px;line-height:1.6;}.home-chart-placeholder,.home-chart-error{display:flex;min-height:240px;align-items:center;justify-content:center;gap:10px;color:#8b9ab1;font-size:12px;padding:20px;text-align:center;}.real-dashboard .trend-panel,.real-dashboard .distribution-panel{height:326px;}.real-dashboard .trend-panel .chart-canvas{height:240px;}.real-dashboard .charts-row{align-items:stretch;}.real-dashboard .distribution-body{min-height:230px;}.home-scope{font-size:11px;margin-left:auto;}.home-empty{height:230px;text-align:center;color:#8b9ab1;}.home-failed{color:#e6586f;font-size:11px;}.home-activity{display:flex;align-items:flex-start;gap:10px;padding:14px 18px;border-top:1px solid #f0f4f9;}.home-activity strong{font-size:12px;line-height:1.7;color:#304b72;}.home-activity time{display:block;font-size:11px;color:#8b9ab1;margin-top:5px;}.home-no-quicks{grid-column:1/-1;padding:15px;color:#8b9ab1;font-size:12px;}.home-security{padding-bottom:20px;}.home-security>p,.home-security>small{display:block;margin:0 20px;font-size:12px;color:#7c90af;line-height:1.8;}.home-security>p strong{color:#e99d39;font-size:20px;margin:0 5px;}.home-security>small{font-size:10px;margin-top:8px;}.home-data-note{color:#8b9ab1;font-size:11px;margin:18px 0 0;line-height:1.8;}.real-dashboard .recent-panel .table-scroll{min-height:295px;}.real-dashboard .dashboard-table{min-width:800px;}.real-dashboard .chart-legend{max-height:210px;overflow:auto;}.real-dashboard .segment-control button{white-space:nowrap;}@media(max-width:800px){.real-dashboard .stat-info strong{font-size:23px;}.home-chart-note{padding:0 15px;}.home-scope{display:none;}.real-dashboard .panel-heading{gap:8px;}.home-activity{padding:12px 15px;}}
</style>
