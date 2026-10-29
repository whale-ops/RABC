package com.star.system.controller;

import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import com.star.system.dto.SystemConfigInputs;
import com.star.system.service.SystemConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// 包职责：提供系统配置的受保护读取和分组保存接口。
// 模块联系：调用 SystemConfigService，供系统管理下的基础、登录和注册配置 Tab；存储配置由独立控制器处理 使用，并记录修改审计。
@OperationAudit("系统配置")
@RestController
@RequestMapping("/api/v1/system/config")
public class SystemConfigController {
  private final SystemConfigService service;

  public SystemConfigController(SystemConfigService service) {
    this.service = service;
  }

  @GetMapping
  public Object read() {
    return ApiResponse.ok(service.read());
  }

  @PutMapping("/basic")
  public Object basic(@Valid @RequestBody SystemConfigInputs.Basic b) {
    return ApiResponse.ok(service.basic(b));
  }

  @PutMapping("/login")
  public Object login(@Valid @RequestBody SystemConfigInputs.Login b) {
    return ApiResponse.ok(service.login(b));
  }

  @PutMapping("/registration")
  public Object registration(@Valid @RequestBody SystemConfigInputs.Registration b) {
    return ApiResponse.ok(service.registration(b));
  }
}
