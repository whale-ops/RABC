/**
 * 验证用户 CSV 导入的引号处理、密码要求及数量限制。
 * 关联模块：测试 utils/user-import.ts 与 import-csv.ts，保护 UserImport.vue 的导入流程。
 */
import { test, expect } from '@playwright/test';

test('user import CSV parser validates quoting, passwords and batch limits', async ({page}) => {
  await page.goto('/login');
  const result = await page.evaluate(async () => {
    const path = '/src/utils/user-import.ts';
    const {parseUserImportCsv: parse} = await import(path);
    const good = parse('\uFEFF用户账号,用户姓名,初始密码,备注\r\nuser_001,"测试,用户",ImportPassword2026,"备注含""引号""\n和换行"\r\n');
    const errors: string[] = [];
    for (const source of [
      '用户账号,用户姓名,初始密码\nuser_001,测试,short123',
      '用户账号,用户姓名,初始密码\nuser_001,测试,ImportPassword2026\nUSER_001,测试,ImportPassword2026',
      '用户账号,用户姓名,初始密码\nuser_001,"未闭合,ImportPassword2026',
      '用户账号,用户姓名,初始密码\n' + Array.from({length: 101}, (_,i) => `user_${i},测试,ImportPassword2026`).join('\n'),
    ]) { try {parse(source); errors.push('NOT_REJECTED');} catch(e) {errors.push((e as Error).message);} }
    return {good, errors};
  });
  expect(result.good[0].name).toBe('测试,用户');
  expect(result.good[0].remark).toBe('备注含"引号"\n和换行');
  expect(result.good[0].status).toBe('启用');
  expect(result.errors[0]).toContain('初始密码');
  expect(result.errors[1]).toContain('重复');
  expect(result.errors[2]).toContain('未闭合');
  expect(result.errors[3]).toContain('100');
});
