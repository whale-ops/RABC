/**
 * 验证角色导入中的必填项、默认值及重复名称和编码检查。
 * 关联模块：测试 utils/role-import.ts，保护 RoleImport.vue 的预览与提交数据。
 */
import { test, expect } from '@playwright/test';

test('role import validates fields, defaults, duplicate names and codes', async ({page}) => {
  await page.goto('/login');
  const result = await page.evaluate(async () => {
    const path = '/src/utils/role-import.ts';
    const {parseRoleImportCsv: parse} = await import(path);
    const defaults = parse('\uFEFF角色名称,角色编码\r\n审计专员,audit_reader\r\n');
    const quoted = parse('角色名称,角色编码,数据范围,状态,备注\n审计员,audit_v2,本部门,停用,"含逗号,备注"');
    const errors: string[] = [];
    for (const source of [
      '角色名称,角色编码\n角色,1bad',
      '角色名称,角色编码\n角色甲,same\n角色乙,SAME',
      '角色名称,角色编码\n同名,first\n同名,second',
      '角色名称,角色编码,数据范围\n角色,custom,自定义部门',
      '角色名称,角色编码,状态\n角色,status,未知',
      '角色名称,角色编码\n' + Array.from({length: 101}, (_,i) => `角色${i},role_${i}`).join('\n'),
    ]) {try {parse(source);errors.push('NOT_REJECTED');} catch(e) {errors.push((e as Error).message);}}
    return {defaults, quoted, errors};
  });
  expect(result.defaults[0]).toMatchObject({name: '审计专员', code: 'audit_reader', scope: '仅本人', status: '启用', remark: ''});
  expect(result.quoted[0]).toMatchObject({scope: '本部门', status: '停用', remark: '含逗号,备注'});
  expect(result.errors[0]).toContain('角色编码');
  expect(result.errors[1]).toContain('重复');
  expect(result.errors[2]).toContain('重复');
  expect(result.errors[3]).toContain('自定义部门');
  expect(result.errors[4]).toContain('状态');
  expect(result.errors[5]).toContain('100');
});
