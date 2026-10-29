/**
 * 将用户导入表格转换为待提交记录，校验账号、密码及关联标识等字段。
 * 关联模块：UserImport.vue 调用，共用 import-csv.ts；通过校验后再请求后台用户批量导入接口。
 */
import { parseImportCsv } from './import-csv';
export interface ImportRow {
  username: string; name: string; initialPassword: string; deptId: string;
  roleIds: string[]; postIds: string[]; email: string; phone: string; status: string; remark: string;
}
const columns: Record<string, keyof ImportRow> = {
  用户账号: "username", 用户姓名: "name", 初始密码: "initialPassword", 部门ID: "deptId",
  角色ID: "roleIds", 岗位ID: "postIds", 电子邮箱: "email", 手机号码: "phone", 状态: "status", 备注: "remark",
};
export const userImportTemplate = '\uFEFF' + Object.keys(columns).join(',') + '\r\n';
/** 严格处理 CSV 引号，支持引号内逗号、转义引号及换行。 */
export function parseUserImportCsv(source: string): ImportRow[] {
  const matrix = parseImportCsv(source);
  const headers = matrix.shift()!.map(h => h.trim());
  const keys = headers.map(h => columns[h] || (Object.values(columns).includes(h as keyof ImportRow) ? h as keyof ImportRow : undefined));
  if (keys.some(k => !k) || new Set(keys).size !== keys.length) throw Error("表头不受支持或存在重复列，请使用下载的模板");
  if (!["username", "name", "initialPassword"].every(k => keys.includes(k as keyof ImportRow))) throw Error("缺少用户账号、用户姓名或初始密码列");
  if (!matrix.length) throw Error("模板中尚未填写用户数据");
  const accounts = new Set<string>();
  return matrix.map((cells, index) => {
    const fail = (message: string): never => {throw Error(`第 ${index + 1} 条数据：${message}`);};
    if (cells.length !== headers.length) fail("列数与表头不一致");
    const result: ImportRow = {username: "", name: "", initialPassword: "", deptId: "", roleIds: [], postIds: [], email: "", phone: "", status: "启用", remark: ""};
    keys.forEach((key, at) => {
      const value = key === "initialPassword" ? cells[at]! : cells[at]!.trim();
      if (key === "roleIds" || key === "postIds") result[key] = value ? value.split(/[,，|、;；]/).map(v => v.trim()).filter(Boolean) : [];
      else if (key) result[key] = key === "status" && !value ? "启用" : value;
    });
    if (!/^[a-zA-Z0-9_]{3,64}$/.test(result.username)) fail("账号需为 3–64 位字母、数字或下划线");
    if (accounts.has(result.username.toLowerCase())) fail("文件内账号重复"); accounts.add(result.username.toLowerCase());
    if (!result.name || result.name.length > 64) fail("姓名必填且最多 64 字");
    if (result.initialPassword.length < 12 || new TextEncoder().encode(result.initialPassword).length > 72 || !/[a-zA-Z]/.test(result.initialPassword) || !/\d/.test(result.initialPassword)) fail("初始密码需至少 12 位、不超过 72 字节，包含字母和数字");
    if (result.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(result.email)) fail("电子邮箱格式不正确");
    if (result.phone && !/^1\d{10}$/.test(result.phone)) fail("手机号码格式不正确");
    if (!['启用', '停用'].includes(result.status)) fail("状态必须为启用或停用");
    if (result.deptId && !/^\d{1,20}$/.test(result.deptId)) fail("部门ID格式不正确");
    if (result.roleIds.length > 10 || result.postIds.length > 20 || [...result.roleIds, ...result.postIds].some(id => !/^\d{1,20}$/.test(id))) fail("角色或岗位ID格式或数量不正确");
    if (result.remark.length > 500) fail("备注最多 500 字");
    return result;
  });
}
