package com.star.storage.service;

import com.star.common.BusinessException;
import com.star.storage.model.StorageDocumentPreview;
import com.star.storage.model.StorageDocumentPreview.*;
import com.star.storage.model.StorageDocumentPreview.Sheet;
import java.io.*;
import java.util.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

// 包职责：读取已鉴权的 Office 文档，生成有大小限制的只读预览。
// 模块联系：复用 StorageFileService 的租户、上传人和下载权限校验，供附件预览接口调用。

/** 使用 POI 读取 Office 内容；不执行公式、宏或外部链接，不访问第三方在线转换服务。 */
@Service
public class StoragePreviewService {
  private static final int MAX_ROWS = 300, MAX_COLUMNS = 50, MAX_SHEETS = 10, MAX_BLOCKS = 1000;
  private final StorageFileService files;
  private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(2);

  public StoragePreviewService(StorageFileService files) {
    this.files = files;
    // 限制 OOXML 单个解压条目；POI 自身仍执行压缩比例校验，避免小压缩包展开成巨量内容。
    ZipSecureFile.setMaxEntrySize(32L * 1024 * 1024);
    ZipSecureFile.setMaxTextSize(8L * 1024 * 1024);
  }

  public StorageDocumentPreview preview(String id) {
    if (!slots.tryAcquire()) throw new BusinessException(429, "PREVIEW_BUSY", "文档预览繁忙，请稍后重试");
    try {
      return read(id);
    } finally {
      slots.release();
    }
  }

  private StorageDocumentPreview read(String id) {
    var file = files.download(id);
    try (var source = file.stream();
        var input = new BufferedInputStream(source)) {
      String extension =
          file.name().substring(file.name().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      var budget = new Budget();
      return switch (extension) {
        case "xlsx", "xls" -> workbook(input, budget);
        case "docx" -> docx(input, budget);
        case "doc" -> doc(input, budget);
        default -> throw BusinessException.bad("该接口支持 XLS、XLSX、DOC、DOCX 文档预览");
      };
    } catch (BusinessException failure) {
      throw failure;
    } catch (Exception failure) {
      // 不将文档内容、SDK 位置、解析器堆栈或密钥信息返回给浏览器。
      throw BusinessException.bad("文档解析失败，文件可能已加密、损坏或内容过大；请下载后查看");
    }
  }

  private StorageDocumentPreview workbook(InputStream input, Budget budget) throws IOException {
    try (var workbook = WorkbookFactory.create(input)) {
      var formatter = new DataFormatter(Locale.CHINA);
      // 使用保存时的公式缓存值，不运行公式或解析外部工作簿。
      formatter.setUseCachedValuesForFormulaCells(true);
      List<Sheet> sheets = new ArrayList<>();
      if (workbook.getNumberOfSheets() > MAX_SHEETS) budget.truncated = true;
      for (int s = 0;
          s < Math.min(workbook.getNumberOfSheets(), MAX_SHEETS) && budget.remaining > 0;
          s++) {
        var sheet = workbook.getSheetAt(s);
        int count = Math.min(sheet.getLastRowNum() + 1, MAX_ROWS), columns = 0;
        if (sheet.getLastRowNum() >= MAX_ROWS) budget.truncated = true;
        for (int r = 0; r < count; r++) {
          var row = sheet.getRow(r);
          if (row != null) columns = Math.max(columns, row.getLastCellNum());
        }
        if (columns > MAX_COLUMNS) budget.truncated = true;
        columns = Math.min(columns, MAX_COLUMNS);
        List<List<String>> rows = new ArrayList<>();
        int lastNonempty = -1;
        for (int r = 0; r < count && budget.remaining > 0; r++) {
          var row = sheet.getRow(r);
          List<String> cells = new ArrayList<>();
          for (int c = 0; c < columns; c++) {
            String value =
                budget.text(row == null ? "" : formatter.formatCellValue(row.getCell(c)));
            if (!value.isBlank()) lastNonempty = r;
            cells.add(value);
          }
          rows.add(cells);
        }
        rows = new ArrayList<>(rows.subList(0, Math.min(lastNonempty + 1, rows.size())));
        List<Merge> merges = new ArrayList<>();
        var regions = sheet.getMergedRegions();
        if (regions.size() > 500) budget.truncated = true;
        for (var merged : regions.subList(0, Math.min(regions.size(), 500))) {
          if (merged.getFirstRow() < rows.size() && merged.getFirstColumn() < columns)
            merges.add(
                new Merge(
                    merged.getFirstRow(),
                    merged.getFirstColumn(),
                    Math.min(merged.getLastRow() + 1, rows.size()) - merged.getFirstRow(),
                    Math.min(merged.getLastColumn() + 1, columns) - merged.getFirstColumn()));
        }
        sheets.add(new Sheet(budget.text(sheet.getSheetName()), rows, merges));
      }
      return new StorageDocumentPreview("spreadsheet", sheets, List.of(), budget.truncated);
    }
  }

  private StorageDocumentPreview docx(InputStream input, Budget budget) throws IOException {
    List<Block> blocks = new ArrayList<>();
    try (var document = new XWPFDocument(input)) {
      for (var element : document.getBodyElements()) {
        if (blocks.size() >= MAX_BLOCKS || budget.remaining <= 0) {
          budget.truncated = true;
          break;
        }
        if (element instanceof XWPFParagraph paragraph) {
          String style = Objects.toString(paragraph.getStyle(), "");
          int heading =
              style.matches("(?i)(heading)?[1-6]")
                  ? Character.digit(style.charAt(style.length() - 1), 10)
                  : 0;
          String alignment =
              switch (paragraph.getAlignment()) {
                case CENTER -> "center";
                case RIGHT -> "right";
                default -> "left";
              };
          blocks.add(
              new Block(
                  "paragraph", budget.text(paragraph.getText()), List.of(), heading, alignment));
          for (var run : paragraph.getRuns())
            for (var picture : run.getEmbeddedPictures()) {
              var data = picture.getPictureData();
              if (data == null) continue;
              String mime =
                  switch (data.suggestFileExtension().toLowerCase(Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "gif" -> "image/gif";
                    default -> "";
                  };
              byte[] bytes = data.getData();
              if (!mime.isEmpty()
                  && bytes.length <= budget.imageRemaining
                  && blocks.size() < MAX_BLOCKS) {
                budget.imageRemaining -= bytes.length;
                blocks.add(
                    new Block(
                        "image",
                        "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes),
                        List.of(),
                        0,
                        "center"));
              } else budget.truncated = true;
            }
        } else if (element instanceof XWPFTable table) {
          List<List<String>> rows = new ArrayList<>();
          if (table.getNumberOfRows() > MAX_ROWS) budget.truncated = true;
          for (var row : table.getRows().subList(0, Math.min(table.getNumberOfRows(), MAX_ROWS))) {
            if (row.getTableCells().size() > MAX_COLUMNS) budget.truncated = true;
            rows.add(
                row.getTableCells().stream()
                    .limit(MAX_COLUMNS)
                    .map(cell -> budget.text(cell.getText()))
                    .toList());
          }
          blocks.add(new Block("table", "", rows, 0, "left"));
        }
      }
    }
    return new StorageDocumentPreview("document", List.of(), blocks, budget.truncated);
  }

  private StorageDocumentPreview doc(InputStream input, Budget budget) throws IOException {
    List<Block> blocks = new ArrayList<>();
    try (var document = new HWPFDocument(input)) {
      var range = document.getRange();
      for (int i = 0;
          i < range.numParagraphs() && blocks.size() < MAX_BLOCKS && budget.remaining > 0;
          i++) {
        var paragraph = range.getParagraph(i);
        // 表格独立展示，避免在普通段落中重复输出每个单元格。
        if (!paragraph.isInTable()) {
          blocks.add(
              new Block(
                  "paragraph",
                  budget.text(WordExtractor.stripFields(paragraph.text()).replace("\r", "")),
                  List.of(),
                  0,
                  "left"));
          continue;
        }
        var table = range.getTable(paragraph);
        List<List<String>> rows = new ArrayList<>();
        if (table.numRows() > MAX_ROWS) budget.truncated = true;
        for (int r = 0; r < Math.min(table.numRows(), MAX_ROWS); r++) {
          var row = table.getRow(r);
          List<String> cells = new ArrayList<>();
          if (row.numCells() > MAX_COLUMNS) budget.truncated = true;
          for (int c = 0; c < Math.min(row.numCells(), MAX_COLUMNS); c++)
            cells.add(
                budget.text(
                    WordExtractor.stripFields(row.getCell(c).text())
                        .replace("\u0007", "")
                        .strip()));
          rows.add(cells);
        }
        blocks.add(new Block("table", "", rows, 0, "left"));
        i += Math.max(0, table.numParagraphs() - 1);
      }
      if (range.numParagraphs() > MAX_BLOCKS) budget.truncated = true;
    }
    return new StorageDocumentPreview("document", List.of(), blocks, budget.truncated);
  }

  private static class Budget {
    int remaining = 1_000_000, imageRemaining = 3 * 1024 * 1024;
    boolean truncated;

    String text(String value) {
      int length = Math.min(value.length(), Math.min(remaining, 10_000));
      if (length < value.length() || length == remaining) truncated = true;
      remaining -= length;
      return value.substring(0, length);
    }
  }
}
