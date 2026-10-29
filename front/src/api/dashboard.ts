/** 首页真实统计与访问采集；关联 DashboardController，浏览器不指定租户或访客身份。 */
import { request } from './request';
export interface HomeActivity {id:string;operator:string;action:string;resource:string;status:string;ip:string;createdAt:string}
export interface HomeOverview {users:number|null;roles:number|null;departments:number|null;posts:number|null;uptimeSeconds:number;activities:HomeActivity[]}
export interface HomeAnalytics {days:number;timezone:string;visitScope:'tenant'|'self';operationScope:'tenant'|'self';pv:number;uv:number;loginFailures:number;trend:{date:string;pv:number;uv:number}[];distribution:{name:string;value:number}[];operationTotal:number}
export const dashboardApi={
  overview:()=>request<HomeOverview>({url:'/dashboard/overview'}),
  analytics:(days:number)=>request<HomeAnalytics>({url:'/dashboard/analytics',params:{days}}),
  activities:(page:number)=>request<{records:HomeActivity[];total:number;scope:'tenant'|'self'}>({url:'/dashboard/activities',params:{page,size:5}}),
};
let pendingVisit:Promise<void>=Promise.resolve();
/** 每次实际路由浏览生成新事件，401 重试复用原事件编号；采集失败不阻断业务页面。 */
export function reportPageView(){
  const eventId=crypto.randomUUID();
  pendingVisit=request<void>({url:'/dashboard/visit',method:'POST',data:{eventId},timeout:5000}).catch(()=>{});
}
export const waitForPageView=()=>pendingVisit;
