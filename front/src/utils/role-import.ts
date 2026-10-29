/**
 * 将角色导入表格转换为记录，处理默认数据范围、状态及批次内重复校验。
 * 关联模块：RoleImport.vue 调用，共用 import-csv.ts；后台 SystemService 继续校验并在事务中落库。
 */
import { parseImportCsv } from './import-csv';
export interface RoleImportRow {name: string; code: string; scope: string; status: string; remark: string;}
const columns: Record<string, keyof RoleImportRow> = {角色名称: 'name', 角色编码: 'code', 数据范围: 'scope', 状态: 'status', 备注: 'remark'};
export function parseRoleImportCsv(source: string): RoleImportRow[] {
  const matrix = parseImportCsv(source), headers = matrix.shift()!.map(h => h.trim());
  const keys = headers.map(h => columns[h] || (Object.values(columns).includes(h as keyof RoleImportRow) ? h as keyof RoleImportRow : undefined));
  if (keys.some(k => !k) || new Set(keys).size !== keys.length) throw Error('表头不受支持或存在重复列，请使用角色导入模板');
  if (!keys.includes('name') || !keys.includes('code')) throw Error('缺少角色名称或角色编码列');
  if (!matrix.length) throw Error('模板中尚未填写角色数据');
  const names = new Set<string>(), codes = new Set<string>();
  return matrix.map((cells, index) => {
    const fail = (message: string): never => {throw Error(`第 ${index + 1} 条数据：${message}`);};
    if (cells.length !== headers.length) fail('列数与表头不一致');
    const row: RoleImportRow = {name: '', code: '', scope: '仅本人', status: '启用', remark: ''};
    keys.forEach((key, at) => {const value = cells[at]!.trim(); if (key && (value || (key !== 'scope' && key !== 'status'))) row[key] = value;});
    if (!row.name || row.name.length > 64) fail('角色名称必填且最多 64 字');
    if (!/^[a-zA-Z][a-zA-Z0-9:_-]{1,63}$/.test(row.code)) fail('角色编码需为 2–64 位，以字母开头，可包含字母、数字、冒号、下划线或短横线');
    if (names.has(row.name.toLowerCase()) || codes.has(row.code.toLowerCase())) fail('文件内角色名称或编码重复');
    names.add(row.name.toLowerCase()); codes.add(row.code.toLowerCase());
    if (row.scope === '自定义部门') fail('自定义部门范围请在导入后通过权限设置配置');
    if (!['全部数据', '本部门及下级', '本部门', '仅本人'].includes(row.scope)) fail('数据范围不合法');
    if (!['启用', '停用'].includes(row.status)) fail('状态必须为启用或停用');
    if (row.remark.length > 500) fail('备注最多 500 字');
    return row;
  });
}
