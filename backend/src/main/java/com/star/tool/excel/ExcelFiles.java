package com.star.tool.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import java.io.ByteArrayOutputStream;
import java.util.List;

// 包职责：封装通用 EasyExcel 工作簿读写与导出，不承载业务权限和事务。
// 模块联系：供 system 导入导出和 monitor 日志导出复用，由各业务服务负责数据筛选与权限检查。

/**
 * 使用 EasyExcel 生成带中文表头和自适应列宽的 XLSX 字节内容。
 *
 * <p>关联模块：系统导出、导入模板和日志导出共同调用；字符串单元格保留长编号并避免按公式执行。
 */
public final class ExcelFiles {
  private ExcelFiles() {}

  /** 把表头与字符串行数据写为 XLSX，保留长编号和公式样式文本的原始内容。 */
  public static byte[] write(String sheet, List<String> headers, List<List<String>> rows) {
    var output = new ByteArrayOutputStream();
    EasyExcel.write(output)
        .autoCloseStream(false)
        .head(headers.stream().map(List::of).toList())
        .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
        .sheet(sheet)
        .doWrite(rows);
    return output.toByteArray();
  }
}
