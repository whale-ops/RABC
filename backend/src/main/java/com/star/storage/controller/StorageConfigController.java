package com.star.storage.controller;

import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import com.star.storage.model.StorageConfigInput;
import com.star.storage.service.StorageConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// 包职责：提供系统配置中的存储分组 API，仅租户管理员可以读取和保存。
// 模块联系：复用系统配置 RBAC 与操作审计，响应只含地址、Bucket 和凭据是否设置的标记。
@RestController
@RequestMapping("/api/v1/system/config/storage")
@OperationAudit("存储配置")
public class StorageConfigController {
  private final StorageConfigService service;

  public StorageConfigController(StorageConfigService service) {
    this.service = service;
  }

  @GetMapping
  public Object read() {
    return ApiResponse.ok(service.read());
  }

  @PutMapping
  public Object save(@Valid @RequestBody StorageConfigInput input) {
    return ApiResponse.ok(service.save(input));
  }
}
