package com.star.storage.model;

import java.util.List;

// 包职责：定义文件预览的结构化展示模型，隔离文档解析器与前端。
// 模块联系：StoragePreviewService 生成数据，StorageFileController 返回给附件预览组件。

/** 文档只读预览：表格为文本单元格，Word 为段落、表格或受限图片，不返回可执行 HTML。 */
public record StorageDocumentPreview(
    String kind, List<Sheet> sheets, List<Block> blocks, boolean truncated) {
  public record Sheet(String name, List<List<String>> rows, List<Merge> merges) {}

  public record Merge(int row, int column, int rowSpan, int columnSpan) {}

  public record Block(
      String kind, String text, List<List<String>> rows, int heading, String alignment) {}
}
