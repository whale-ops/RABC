/**
 * 在真实联调测试开始前检查后台健康状态，缺少服务时给出启动提示。
 * 关联模块：由 playwright.live.config.ts 调用；不负责创建或重置 MySQL 和 Redis 数据。
 */
import { request } from '@playwright/test';
export default async function setup() {
  const api=await request.newContext({baseURL:'http://127.0.0.1:8080'});
  try {
    const response=await api.get('/api/v1/health',{timeout:5000});
    if(!response.ok()) throw new Error('Backend health probe failed');
  } catch {
    throw new Error('请先启动真实后台 http://127.0.0.1:8080 和专用 MySQL / Redis 测试环境，再执行 test:live。');
  } finally { await api.dispose(); }
}
