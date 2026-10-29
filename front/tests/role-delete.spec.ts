/**
 * 验证演示模式删除角色只解除绑定，以及删除单个用户保留其他用户和角色。
 * 关联模块：通过 mock.ts 和 store.ts 检查删除约定，与真实 SystemService 的行为保持一致。
 */
import { test, expect } from '@playwright/test';

test('role deletion preserves users; deleting one user preserves all peers and roles', async ({page}) => {
  await page.goto('/system/roles');
  const result = await page.evaluate(async () => {
    const apiPath = '/src/api/request.ts', storePath = '/src/data/store.ts';
    const {request} = await import(apiPath), {db} = await import(storePath);
    const before = db.users.map((u: any) => u.id), role = db.roles.find((r: any) => r.id === '2');
    const target = db.users.find((u: any) => u.role === role.name && u.id !== '1001');
    await request({url: '/system/roles/batch-delete', method: 'POST', data: {ids: ['2']}});
    const allPreserved = db.users.every((u: any) => before.includes(u.id)) && db.users.length === before.length;
    const unbound = db.users.every((u: any) => !u.role.split('、').includes(role.name));
    const roleGone = !db.roles.some((r: any) => r.id === '2');
    const roles = JSON.stringify(db.roles);
    await request({url: '/system/users/batch-delete', method: 'POST', data: {ids: [target.id]}});
    return {allPreserved, unbound, roleGone, userGone: !db.users.some((u: any) => u.id === target.id),
      peersPreserved: db.users.length === before.length - 1 && db.users.every((u: any) => before.includes(u.id)),
      rolesPreserved: roles === JSON.stringify(db.roles)};
  });
  expect(Object.values(result).every(Boolean)).toBe(true);
});
