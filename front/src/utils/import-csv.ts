/**
 * 按记录数、列数和引号规则解析 CSV，并把二维表格安全还原为 CSV 文本。
 * 关联模块：user-import.ts、role-import.ts 及导入组件复用，避免用户与角色导入采用不同解析规则。
 */
/** 用户和角色导入共用的受限 CSV 解析，校验数量及引号格式。 */
export function parseImportCsv(source: string): string[][] {
  const text = source.replace(/^\uFEFF/, "");
  const matrix: string[][] = []; let row: string[] = [], cell = "", quoted = false, closed = false;
  function endCell() { row.push(cell); cell = ""; closed = false; }
  function endRow() { endCell(); if (row.some(c => c.trim())) matrix.push(row); row = []; if (matrix.length > 101) throw Error("一次最多导入 100 条数据"); }
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else { quoted = false; closed = true; } }
      else cell += c;
    } else if (c === ',') endCell();
    else if (c === '\r' || c === '\n') { if (c === '\r' && text[i + 1] === '\n') i++; endRow(); }
    else if (c === '"') { if (cell || closed) throw Error("CSV 引号格式错误，请使用下载的模板"); quoted = true; }
    else { if (closed) throw Error("CSV 引号后存在多余字符"); cell += c; }
  }
  if (quoted) throw Error("CSV 引号未闭合");
  if (cell || row.length || closed) endRow();
  if (!matrix.length) throw Error("文件为空，请下载并填写导入模板");
  return matrix;
}

export function matrixToCsv(matrix: string[][]): string {
  return matrix.map(row => row.map(cell => '"' + cell.replace(/"/g, '""') + '"').join(',')).join('\r\n');
}
