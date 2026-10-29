package com.star.storage.controller;

import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import com.star.storage.model.StorageDocumentPreview;
import com.star.storage.model.StorageFilePage;
import com.star.storage.model.StorageFileView;
import com.star.storage.service.StorageFileService;
import com.star.storage.service.StoragePreviewService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

// 包职责：提供文件上传、列表、下载及删除的 HTTP 接口，管理响应流。
// 模块联系：对接前端文件管理页面，调用 storage.service；写操作通过 framework.aspect 记录日志。

/**
 * 提供文件列表、上传、鉴权下载及删除接口，负责 HTTP 响应和下载流关闭。
 *
 * <p>关联模块：对接 Vue 文件管理页面，委托 StorageFileService 校验权限并处理文件；OperationAudit 与 AuditLogAspect 记录上传、删除操作。
 */
@RestController
@RequestMapping("/api/v1/storage/files")
@OperationAudit("文件管理")
public class StorageFileController {
  private final StorageFileService files;
  private final StoragePreviewService previews;

  public StorageFileController(StorageFileService files, StoragePreviewService previews) {
    this.files = files;
    this.previews = previews;
  }

  /** Office 预览与下载复用相同的租户、所有者及权限校验。 */
  @GetMapping("/{id}/preview")
  public ApiResponse<StorageDocumentPreview> preview(@PathVariable String id) {
    return ApiResponse.ok(previews.preview(id));
  }

  @GetMapping
  public ApiResponse<StorageFilePage> list(
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int pageSize,
      @RequestParam(defaultValue = "all") String category) {
    return ApiResponse.ok(files.list(keyword, page, pageSize, category));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<StorageFileView> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "all") String category) {
    return ApiResponse.ok(files.upload(file, category));
  }

  // 独立鉴权签发，视频字节由对象存储直接分段传输，避免后端和浏览器双重全量缓冲。
  @GetMapping("/{id}/playback")
  public ApiResponse<com.star.storage.model.StoragePlayback> playback(@PathVariable String id) {
    return ApiResponse.ok(files.playback(id));
  }

  @GetMapping("/{id}/content")
  public void download(@PathVariable String id, HttpServletResponse response) throws IOException {
    var download = files.download(id);
    try (var stream = download.stream()) {
      response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
      response.setContentLengthLong(download.size());
      response.setHeader(
          HttpHeaders.CONTENT_DISPOSITION,
          ContentDisposition.attachment()
              .filename(download.name(), StandardCharsets.UTF_8)
              .build()
              .toString());
      stream.transferTo(response.getOutputStream());
    }
  }

  /** 明确的本地记录删除入口，存储离线或配置失效时仍可清理列表，不自动补删远端对象。 */
  @DeleteMapping("/{id}/record")
  public ApiResponse<Void> deleteRecord(@PathVariable String id) {
    files.deleteRecord(id);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable String id) {
    files.delete(id);
    return ApiResponse.ok(null);
  }
}
