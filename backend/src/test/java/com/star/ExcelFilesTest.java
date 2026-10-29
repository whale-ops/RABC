package com.star;

import static org.junit.jupiter.api.Assertions.*;

import com.star.tool.excel.ExcelFiles;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;

/**
 * 验证导出的 XLSX 能被读取，并确保长编号和公式样式文本保持字符串。
 *
 * <p>关联模块：直接测试 ExcelFiles，保护系统导出、日志导出与导入模板的公共表格能力。
 */
class ExcelFilesTest {
  @Test
  void upgradedPoiStillSupportsEasyExcelImport() {
    byte[] bytes =
        ExcelFiles.write(
            "导入测试", List.of("编号", "姓名"), List.of(List.of("00123456789012345678", "李灿")));
    List<java.util.Map<Integer, String>> rows =
        com.alibaba.excel.EasyExcel.read(new ByteArrayInputStream(bytes))
            .headRowNumber(1)
            .doReadAllSync();
    assertEquals("00123456789012345678", rows.get(0).get(0));
    assertEquals("李灿", rows.get(0).get(1));
  }

  @Test
  void workbookPreservesIdsAndWritesFormulaLookingTextAsStrings() throws Exception {
    byte[] bytes =
        ExcelFiles.write(
            "测试", List.of("ID", "姓名"), List.of(List.of("2112518740815155203", "=SUM(1,2)")));
    assertEquals('P', bytes[0]);
    assertEquals('K', bytes[1]);
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var row = book.getSheetAt(0).getRow(1);
      assertEquals(CellType.STRING, row.getCell(0).getCellType());
      assertEquals("2112518740815155203", row.getCell(0).getStringCellValue());
      assertEquals(CellType.STRING, row.getCell(1).getCellType());
      assertEquals("=SUM(1,2)", row.getCell(1).getStringCellValue());
    }
  }
}
