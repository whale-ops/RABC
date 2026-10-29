/** 系统监控真实接口；关联后台 MonitorController，指标不从前端随机生成。 */
import { request } from "./request";
export interface ServerPoint { at:number; cpuPercent:number|null; memoryUsed:number; memoryTotal:number; heapUsed:number; heapMax:number }
export interface ServerSnapshot { current:ServerPoint; history:ServerPoint[]; disks:{name:string;total:number;used:number}[]; os:string;arch:string;javaVersion:string;cores:number;startedAt:number;uptimeSeconds:number;threads:number;health:{name:string;healthy:boolean;latencyMs:number}[] }
export interface CachePoint {at:number;memoryUsed:number;operationsPerSecond:number}
export interface CacheSnapshot { current:CachePoint;history:CachePoint[];version:string;mode:string;database:number;keys:number;expiringKeys:number;maxMemory:number;clients:number;hits:number;misses:number;hitRate:number|null;uptimeSeconds:number;commands:{name:string;calls:number}[] }
export interface OnlineSession {id:string;username:string;name:string;ip:string;browser:string;os:string;loginAt:number;lastSeen:number;current:boolean;protectedAccount:boolean}
export const monitorApi = {
  server:()=>request<ServerSnapshot>({url:"/monitor/server"}),
  cache:()=>request<CacheSnapshot>({url:"/monitor/cache"}),
  sessions:(keyword:string,page:number,size:number)=>request<{records:OnlineSession[];total:number;truncated:boolean}>({url:"/monitor/sessions",params:{keyword,page,size}}),
  kick:(id:string)=>request<void>({url:`/monitor/sessions/${id}/kick`,method:"POST"}),
};
