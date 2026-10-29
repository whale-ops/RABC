<script setup lang="ts">
/** 在线用户：表格展示本租户的活跃登录会话，支持筛选、分页、刷新和授权强退。
 * 关联模块：monitorApi 读取 Redis 会话索引，session.ts 控制操作权限；不展示令牌或刷新凭据。
 */
import { onBeforeUnmount, onMounted, ref } from "vue";
import { Button, Input, Pagination, Modal, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import { monitorApi, type OnlineSession } from "../api/monitor";
import { can, session } from "../api/session";
import { errorMessage } from "../api/request";
const rows=ref<OnlineSession[]>([]),total=ref(0),page=ref(1),size=ref(10),keyword=ref(''),applied=ref('');
const loading=ref(false),error=ref(''),truncated=ref(false),automatic=ref(true);
const pending=ref<OnlineSession|null>(null),kicking=ref(false);
let active=true,sequence=0,timer:ReturnType<typeof setInterval>|undefined;
async function load(){const current=++sequence;loading.value=true;try{const data=await monitorApi.sessions(applied.value,page.value,size.value);if(!active||current!==sequence)return;rows.value=data.records;total.value=data.total;truncated.value=data.truncated;error.value='';if(!rows.value.length&&page.value>1){page.value=Math.max(1,Math.ceil(total.value/size.value));await load();}}catch(e){if(active&&current===sequence)error.value=errorMessage(e);}finally{if(active&&current===sequence)loading.value=false;}}
function search(){applied.value=keyword.value.trim();page.value=1;void load();}
async function kick(){if(!pending.value||kicking.value)return;kicking.value=true;try{await monitorApi.kick(pending.value.id);pending.value=null;Toast.success('会话已强退，该账号其他会话不受影响');await load();}catch(e){Toast.error(errorMessage(e));}finally{kicking.value=false;}}
const date=(n:number)=>new Date(n).toLocaleString('zh-CN',{hour12:false});
onMounted(()=>{void load();timer=setInterval(()=>{if(automatic.value&&!document.hidden&&!loading.value&&!pending.value)void load();},10000);});
onBeforeUnmount(()=>{active=false;++sequence;clearInterval(timer);});
</script>
<template>
  <div class="online-page"><div class="page-heading"><div><h1>在线用户</h1><p>查看当前租户的活跃登录会话，及时发现异常访问</p></div><span class="page-heading-note"><AppIcon name="users" :size="16" />共 {{total}} 个在线会话</span></div>
    <section class="panel records-panel" :aria-busy="loading">
      <form class="online-filters" @submit.prevent="search"><label><span>用户信息</span><Input :value="keyword" placeholder="账号 / 姓名 / IP" :maxLength="100" :onChange="(value:string)=>keyword=value" /></label><div class="filter-actions"><Button theme="solid" htmlType="submit"><AppIcon name="search" :size="15" />查询</Button><Button :onClick="()=>{keyword='';search();}"><AppIcon name="refresh" :size="15" />重置</Button></div><div class="online-refresh"><label><input v-model="automatic" type="checkbox" />自动刷新</label><button type="button" class="icon-button" aria-label="刷新在线用户" @click="!loading&&load()"><AppIcon name="refresh" :size="18" /></button></div></form>
      <p class="online-note">最近 15 分钟有认证请求的会话视为在线；同一账号不同浏览器分别计数。关闭浏览器后会话可能保留至活跃窗口结束。每 10 秒刷新。</p>
      <p v-if="truncated" class="storage-warning">当前仅展示最近活跃的 2000 个会话，请缩小管理范围。</p>
      <div v-if="error" class="storage-warning" role="alert">{{error}}<button class="text-button" @click="load">重试</button></div>
      <div class="table-scroll online-table-scroll"><table class="data-table online-table" aria-label="在线用户列表"><thead><tr><th>用户账号</th><th>姓名</th><th>IP 地址</th><th>浏览器 / 系统</th><th>登录时间</th><th>最近活动</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{row.username}} <span v-if="row.current" class="subtle-badge">当前会话</span></td><td>{{row.name}}</td><td>{{row.ip||'—'}}</td><td>{{row.browser}} / {{row.os}}</td><td>{{date(row.loginAt)}}</td><td>{{date(row.lastSeen)}}</td><td><span v-if="row.current" class="muted">当前会话</span><span v-else-if="row.protectedAccount" class="muted">受保护账号</span><button v-else-if="can('monitor:session:kick')&&session.user?.admin" type="button" class="text-button online-kick" @click="pending=row">强退</button><span v-else class="muted">—</span></td></tr><tr v-if="!rows.length"><td colspan="7" class="online-empty">{{loading?'正在读取在线会话…':'暂无符合条件的在线用户'}}</td></tr></tbody></table></div>
      <div class="table-pagination"><span>共 {{total}} 个会话</span><Pagination :total="total" :currentPage="page" :pageSize="size" showSizeChanger :pageSizeOpts="[10,20,50,100]" :onPageChange="(value:number)=>{page=value;load();}" :onPageSizeChange="(value:number)=>{size=value;page=1;load();}" /></div>
    </section>
    <Modal :visible="!!pending" title="强退会话" okText="确认强退" cancelText="取消" :confirmLoading="kicking" :onOk="kick" :onCancel="()=>{if(!kicking)pending=null;}"><p>确认强退 {{pending?.username}} 在 {{pending?.ip}} 的该次登录吗？</p><p class="online-note">该会话下次请求即失效，账号和其他登录会话保留。</p></Modal>
  </div>
</template>
<style scoped>
.online-filters { display:flex; align-items:flex-end; flex-wrap:wrap; gap:16px; padding-bottom:22px; border-bottom:1px solid var(--border); }
.online-filters>label { display:flex; flex-direction:column; gap:9px; width:280px; max-width:100%; font-size:13px; color:#627895; }
.online-refresh { display:flex; align-items:center; gap:14px; margin-left:auto; min-height:34px; }
.online-refresh label { display:flex; align-items:center; gap:6px; font-size:12px; color:#8292ae; white-space:nowrap; }
.online-note { margin:16px 0; color:#8292ae; font-size:12px; line-height:1.8; }
.online-table { min-width:1000px; }.online-table-scroll { min-height:300px; }.online-empty { height:240px; text-align:center; color:#8292ae; }
.online-kick { color:#e6586f; }
@media(max-width:800px){.online-filters>label{width:100%;}.page-heading{gap:12px;flex-wrap:wrap;}.online-refresh{margin-left:0;}}
</style>
