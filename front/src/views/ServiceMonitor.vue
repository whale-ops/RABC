<script setup lang="ts">
/** 服务与缓存监控：轮询真实采样，用 ECharts 展示趋势、磁盘和命令统计。
 * 关联模块：monitorApi 对接后台白名单指标，ChartPanel 管理图表生命周期，不清理 Redis。
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { Button } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import ChartPanel from "../components/ChartPanel.vue";
import { monitorApi, type ServerSnapshot, type CacheSnapshot } from "../api/monitor";
import { errorMessage } from "../api/request";
import { formatSize as fileSize } from "../data/files";
// 主机磁盘容量较大，使用 GB/TB 展示，避免把数十万 MB 挤入信息栏。
const formatSize=(bytes:number)=>bytes>=1024**4?(bytes/1024**4).toFixed(2)+' TB':bytes>=1024**3?(bytes/1024**3).toFixed(2)+' GB':fileSize(bytes);
const route=useRoute(),cacheMode=computed(()=>route.params.view==="cache");
const server=ref<ServerSnapshot>(),cache=ref<CacheSnapshot>();
const loading=ref(false),error=ref(""),automatic=ref(true);
let timer:ReturnType<typeof setInterval>|undefined,active=true;
const percent=(value:number|null|undefined)=>value==null?'—':value.toFixed(1)+'%';
const ratio=(used:number,total:number)=>total>0?Math.min(100,used/total*100):null;
const time=(value:number)=>new Date(value).toLocaleTimeString('zh-CN',{hour12:false});
const duration=(seconds:number)=>`${Math.floor(seconds/86400)} 天 ${Math.floor(seconds%86400/3600)} 小时 ${Math.floor(seconds%3600/60)} 分钟`;
async function load(){
  if(loading.value)return;loading.value=true;
  try{if(cacheMode.value){const data=await monitorApi.cache();if(active)cache.value=data;}
    else {const data=await monitorApi.server();if(active)server.value=data;}if(active)error.value='';}
  catch(e){if(active)error.value=errorMessage(e);}finally{if(active)loading.value=false;}
}
onMounted(()=>{void load();timer=setInterval(()=>{if(automatic.value&&!document.hidden)void load();},10000);});
onBeforeUnmount(()=>{active=false;clearInterval(timer);});
const cards=computed(()=>{
  if(cacheMode.value){const s=cache.value;if(!s)return[];return[
    {label:'缓存命中率',value:percent(s.hitRate),icon:'activity',note:'Redis 实例累计命中 / 总访问'},
    {label:'实例内存',value:formatSize(s.current.memoryUsed),icon:'database',note:s.maxMemory?`上限 ${formatSize(s.maxMemory)}`:'未配置 maxmemory 上限'},
    {label:'当前库键数量',value:s.keys.toLocaleString(),icon:'key',note:`DB ${s.database} · ${s.expiringKeys} 个设置过期时间`},
    {label:'连接客户端',value:s.clients.toLocaleString(),icon:'network',note:'Redis 实例的当前连接数'},
  ];}
  const s=server.value;if(!s)return[];return[
    {label:'CPU 使用率',value:percent(s.current.cpuPercent),icon:'cpu',note:`${s.cores} 个可用处理器`},
    {label:'主机内存',value:percent(ratio(s.current.memoryUsed,s.current.memoryTotal)),icon:'database',note:`${formatSize(s.current.memoryUsed)} / ${formatSize(s.current.memoryTotal)}`},
    {label:'JVM 堆内存',value:percent(ratio(s.current.heapUsed,s.current.heapMax)),icon:'server',note:`${formatSize(s.current.heapUsed)} / ${formatSize(s.current.heapMax)}`},
    {label:'应用运行时长',value:duration(s.uptimeSeconds),icon:'clock',note:`${s.threads} 个 JVM 线程`},
  ];
});
const historyCount=computed(()=>cacheMode.value?cache.value?.history.length:server.value?.history.length);
const updated=computed(()=>cacheMode.value?cache.value?.current.at:server.value?.current.at);
const baseChart={animation:false,tooltip:{trigger:'axis'},legend:{top:8,textStyle:{color:'#7c8ba5'}},grid:{left:54,right:56,top:52,bottom:34},xAxis:{type:'category',boundaryGap:false,axisLabel:{color:'#8c9ab1'}},yAxis:{type:'value',axisLabel:{color:'#8c9ab1'},splitLine:{lineStyle:{color:'#edf1f7'}}}};
const trend=computed(()=>{
  if(cacheMode.value){const h=cache.value?.history||[];return{...baseChart,xAxis:{...baseChart.xAxis,data:h.map(p=>time(p.at))},yAxis:[{...baseChart.yAxis,name:'MB'},{...baseChart.yAxis,name:'次/秒',splitLine:{show:false}}],series:[
    {name:'实例内存',type:'line',showSymbol:h.length===1,data:h.map(p=>+(p.memoryUsed/1048576).toFixed(2)),itemStyle:{color:'#1677ff'},areaStyle:{color:'#1677ff14'}},
    {name:'命令速率',type:'line',showSymbol:h.length===1,yAxisIndex:1,data:h.map(p=>p.operationsPerSecond),itemStyle:{color:'#22b99a'}}]};}
  const h=server.value?.history||[];return{...baseChart,xAxis:{...baseChart.xAxis,data:h.map(p=>time(p.at))},yAxis:{...baseChart.yAxis,max:100,min:0,axisLabel:{formatter:'{value}%'}},series:[
    {name:'CPU',type:'line',showSymbol:h.length===1,data:h.map(p=>p.cpuPercent),itemStyle:{color:'#1677ff'},areaStyle:{color:'#1677ff14'}},
    {name:'主机内存',type:'line',showSymbol:h.length===1,data:h.map(p=>ratio(p.memoryUsed,p.memoryTotal)),itemStyle:{color:'#22b99a'}},
    {name:'JVM 堆',type:'line',showSymbol:h.length===1,data:h.map(p=>ratio(p.heapUsed,p.heapMax)),itemStyle:{color:'#9675ee'}}]};
});
const distribution=computed(()=>{
  let data:{name:string;value:number}[]=[];
  if(cacheMode.value)data=(cache.value?.commands||[]).map(c=>({name:c.name,value:c.calls}));
  else{const disks=server.value?.disks||[],used=disks.reduce((n,d)=>n+d.used,0),total=disks.reduce((n,d)=>n+d.total,0);data=[{name:'已使用',value:used},{name:'可用',value:Math.max(0,total-used)}];}
  return{animation:false,color:['#1677ff','#22b99a','#9675ee','#f4b345','#61a5ec','#f28b96'],tooltip:{trigger:'item',valueFormatter:(n:number)=>cacheMode.value?n.toLocaleString()+' 次':formatSize(n)},legend:{bottom:0,type:'scroll'},series:[{type:'pie',radius:['42%','68%'],center:['50%','44%'],label:{show:false},data}]};
});
</script>
<template>
  <div class="monitor-page live-monitor">
    <div class="page-heading"><div><h1>{{cacheMode?'缓存监控':'服务监控'}}</h1><p>{{cacheMode?'查看 Redis 实例指标及当前逻辑库统计':'查看当前 Java 后端所在主机和 JVM 的实际运行状态'}}</p></div>
      <div class="monitor-heading-actions"><label class="monitor-auto"><input v-model="automatic" type="checkbox" />自动刷新</label><Button :onClick="load"><AppIcon name="refresh" :size="16" />刷新</Button></div>
    </div>
    <p class="monitor-live-note">每 5 秒采样，页面每 10 秒刷新；历史最多保留最近 12 分钟，应用重启后重新采集。<span v-if="updated">更新于 {{time(updated)}}</span></p>
    <div v-if="error" class="storage-warning" role="alert">{{error}}<button class="text-button" @click="load">重试</button></div>
    <p v-if="loading&&!cards.length" class="empty-state" role="status">正在读取实际监控指标…</p>
    <template v-if="cards.length">
      <div class="monitor-metrics"><section v-for="(card,i) in cards" :key="card.label" class="panel monitor-metric"><div><span class="summary-icon" :class="['blue','purple','mint','orange'][i]"><AppIcon :name="card.icon" :size="24" /></span><span>{{card.label}}</span></div><strong>{{card.value}}</strong><p>{{card.note}}</p></section></div>
      <div class="monitor-middle"><section class="panel resource-chart"><div class="panel-heading"><h2>{{cacheMode?'内存与命令速率趋势':'资源使用趋势'}}</h2><span class="muted">已采集 {{historyCount}} 个点</span></div><ChartPanel :option="trend" /></section>
        <section class="panel resource-chart"><div class="panel-heading"><h2>{{cacheMode?'高频命令累计（前12项）':'磁盘空间分布'}}</h2></div><ChartPanel :option="distribution" /></section></div>
      <section v-if="!cacheMode&&server" class="panel live-health"><div class="panel-heading"><h2>服务健康状态</h2></div><div class="live-health-grid"><div v-for="item in server.health" :key="item.name"><AppIcon :name="item.name==='应用服务'?'server':'database'" :size="20" /><strong>{{item.name}}</strong><span :class="['live-health-pill',item.healthy?'healthy':'unhealthy']">{{item.healthy?'正常':'异常'}}</span><small>{{item.latencyMs}} ms</small></div></div></section>
      <section class="panel server-info"><div class="panel-heading"><h2>{{cacheMode?'Redis 信息':'服务器信息'}}</h2><span class="muted">{{cacheMode?'内存、命中率和命令统计属于整个 Redis 实例':'仅展示运行信息，不包含环境变量或凭据'}}</span></div>
        <div v-if="!cacheMode&&server" class="server-info-grid"><dl v-for="[label,value] in [['操作系统',server.os],['系统架构',server.arch],['Java 版本',server.javaVersion],['可用处理器',server.cores],['JVM 线程',server.threads],['启动时间',new Date(server.startedAt).toLocaleString('zh-CN')]]" :key="label"><dt>{{label}}</dt><dd>{{value}}</dd></dl></div>
        <div v-else-if="cache" class="server-info-grid"><dl v-for="[label,value] in [['Redis 版本',cache.version],['运行模式',cache.mode],['当前逻辑库','DB '+cache.database],['运行时长',duration(cache.uptimeSeconds)],['累计命中',cache.hits.toLocaleString()],['累计未命中',cache.misses.toLocaleString()]]" :key="label"><dt>{{label}}</dt><dd>{{value}}</dd></dl></div>
        <div v-if="!cacheMode&&server" class="table-scroll monitor-disk-table"><table class="data-table"><thead><tr><th>磁盘</th><th>已使用</th><th>总容量</th><th>使用率</th></tr></thead><tbody><tr v-for="disk in server.disks" :key="disk.name"><td>{{disk.name}}</td><td>{{formatSize(disk.used)}}</td><td>{{formatSize(disk.total)}}</td><td>{{percent(ratio(disk.used,disk.total))}}</td></tr></tbody></table></div>
      </section>
    </template>
  </div>
</template>
<style scoped>
.monitor-auto { display:flex; align-items:center; gap:6px; font-size:12px; color:#7184a0; white-space:nowrap; }
.monitor-live-note { font-size:12px; color:#8292ae; margin:-8px 0 20px; line-height:1.8; }
.monitor-live-note span { margin-left:12px; }
.live-monitor .monitor-metric strong { font-size:27px; overflow-wrap:anywhere; }
.live-monitor .monitor-metric >p { margin-top:16px; }
.live-health { margin-bottom:20px; }
.live-health-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); padding:0 22px 22px; gap:18px; }
.live-health-grid>div { display:flex; align-items:center; gap:10px; color:#6c85a7; flex-wrap:wrap; }
.live-health-grid strong { color:#304b72; font-size:13px; }
.live-health-pill { padding:4px 8px; border-radius:4px; font-size:12px; }
.healthy { color:#17a783; background:#e6f7ef; }.unhealthy { color:#e04d63; background:#fff0f2; }
.live-health-grid small { color:#8292ae; }
.monitor-disk-table { margin:0 22px 20px; }
.monitor-disk-table table { min-width:480px; }
.live-monitor .server-info-grid dd { overflow-wrap:anywhere; }
@media(max-width:800px){.live-health-grid{grid-template-columns:1fr;}.live-monitor .panel-heading{align-items:flex-start;gap:10px;flex-wrap:wrap;}.monitor-heading-actions{flex-wrap:wrap;}.monitor-live-note span{display:block;margin:0;}.live-monitor .monitor-metric strong{font-size:22px;}}
</style>
