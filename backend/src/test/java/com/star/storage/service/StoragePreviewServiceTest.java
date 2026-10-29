package com.star.storage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.star.common.BusinessException;
import com.star.storage.model.StorageDownload;
import java.io.*;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

/** 验证真实 Office 字节解析、合并单元格、缓存公式、Word 内容和损坏文件的安全报错。 */
class StoragePreviewServiceTest {
  private final StorageFileService files = mock(StorageFileService.class);
  private final StoragePreviewService preview = new StoragePreviewService(files);

  private void source(String name, byte[] bytes) {
    when(files.download("1"))
        .thenAnswer(
            call -> new StorageDownload(name, bytes.length, new ByteArrayInputStream(bytes)));
  }

  @Test
  void previewsModernAndLegacyExcelWithTabsMergedCellsAndCachedValues() throws Exception {
    for (boolean legacy : List.of(false, true)) {
      try (Workbook book = legacy ? new HSSFWorkbook() : new XSSFWorkbook()) {
        var sheet = book.createSheet("员工信息");
        sheet.createRow(0).createCell(0).setCellValue("员工信息表");
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
        var row = sheet.createRow(1);
        row.createCell(0).setCellValue("李灿");
        row.createCell(1).setCellValue("<script>禁止执行</script>");
        row.createCell(2).setCellFormula("SUM(1,2)");
        book.getCreationHelper().createFormulaEvaluator().evaluateAll();
        book.createSheet("部门信息").createRow(0).createCell(0).setCellValue("研发部");
        var bytes = new ByteArrayOutputStream();
        book.write(bytes);
        source("员工信息." + (legacy ? "xls" : "xlsx"), bytes.toByteArray());
        var result = preview.preview("1");
        assertEquals("spreadsheet", result.kind());
        assertEquals(2, result.sheets().size());
        assertEquals("员工信息", result.sheets().get(0).name());
        assertEquals(2, result.sheets().get(0).merges().get(0).columnSpan());
        assertEquals("李灿", result.sheets().get(0).rows().get(1).get(0));
        assertEquals("<script>禁止执行</script>", result.sheets().get(0).rows().get(1).get(1));
        assertEquals("3", result.sheets().get(0).rows().get(1).get(2));
        assertFalse(result.truncated());
      }
    }
  }

  @Test
  void previewsWordParagraphsTablesAndEmbeddedImagesWithoutHtml() throws Exception {
    try (var document = new XWPFDocument()) {
      var heading = document.createParagraph();
      heading.setStyle("Heading1");
      heading.setAlignment(ParagraphAlignment.CENTER);
      heading.createRun().setText("员工手册");
      document.createParagraph().createRun().setText("中文正文 <img onerror=alert(1)>");
      var table = document.createTable(1, 2);
      table.getRow(0).getCell(0).setText("姓名");
      table.getRow(0).getCell(1).setText("李灿");
      byte[] png =
          Base64.getDecoder()
              .decode(
                  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jS9sAAAAASUVORK5CYII=");
      document
          .createParagraph()
          .createRun()
          .addPicture(
              new ByteArrayInputStream(png),
              Document.PICTURE_TYPE_PNG,
              "test.png",
              Units.toEMU(12),
              Units.toEMU(12));
      var bytes = new ByteArrayOutputStream();
      document.write(bytes);
      source("员工手册.docx", bytes.toByteArray());
      var result = preview.preview("1");
      assertEquals("document", result.kind());
      assertEquals(1, result.blocks().get(0).heading());
      assertEquals("center", result.blocks().get(0).alignment());
      assertTrue(
          result.blocks().stream().anyMatch(b -> b.text().contains("<img onerror=alert(1)>")));
      assertTrue(
          result.blocks().stream()
              .anyMatch(b -> b.kind().equals("table") && b.rows().get(0).contains("李灿")));
      assertTrue(
          result.blocks().stream()
              .anyMatch(
                  b -> b.kind().equals("image") && b.text().startsWith("data:image/png;base64,")));
    }
  }

  @Test
  void boundsLargeWorkbooksAndReportsInvalidDocumentsWithoutDetails() throws Exception {
    try (var book = new XSSFWorkbook()) {
      var sheet = book.createSheet("大表");
      for (int i = 0; i < 301; i++) sheet.createRow(i).createCell(0).setCellValue("记录" + i);
      var bytes = new ByteArrayOutputStream();
      book.write(bytes);
      source("大表.xlsx", bytes.toByteArray());
      var result = preview.preview("1");
      assertTrue(result.truncated());
      assertEquals(300, result.sheets().get(0).rows().size());
    }
    source("损坏.docx", new byte[] {1, 2, 3});
    var error = assertThrows(BusinessException.class, () -> preview.preview("1"));
    assertEquals(400, error.status);
    assertFalse(error.getMessage().contains("Exception"));
    var denied = BusinessException.denied();
    when(files.download("1")).thenThrow(denied);
    assertSame(denied, assertThrows(BusinessException.class, () -> preview.preview("1")));
  }
}
