package com.star.storage.controller;

import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import com.star.storage.model.StorageEntryInput;
import com.star.storage.service.StorageCatalogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// 包职责：提供存储配置列表、新增编辑、移除和当前上传选择 API。
// 模块联系：复用系统配置权限与审计，只返回非敏感字段和凭据已设置标记。
@RestController
@RequestMapping("/api/v1/system/config/storage/catalog")
@OperationAudit("存储配置")
public class StorageCatalogController {
  private final StorageCatalogService service;

  public StorageCatalogController(StorageCatalogService service) {
    this.service = service;
  }

  @GetMapping
  public Object read() {
    return ApiResponse.ok(service.read());
  }

  @PostMapping
  public Object create(@Valid @RequestBody StorageEntryInput b) {
    return ApiResponse.ok(service.save(null, b));
  }

  @PutMapping("/{id}")
  public Object edit(@PathVariable String id, @Valid @RequestBody StorageEntryInput b) {
    return ApiResponse.ok(service.save(id, b));
  }

  @PutMapping("/selection")
  public Object select(@Valid @RequestBody StorageEntryInput.Selection b) {
    return ApiResponse.ok(service.select(b));
  }

  @DeleteMapping("/{id}")
  public Object remove(@PathVariable String id, @RequestParam long version) {
    return ApiResponse.ok(service.remove(id, version));
  }
}
