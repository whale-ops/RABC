package com.star.system.dto;

import jakarta.validation.constraints.*;

// 包职责：定义系统配置的分组请求与长度、范围约束。
// 模块联系：供系统配置控制器接收，由 SystemConfigService 按租户校验和保存。
public final class SystemConfigInputs {
  private SystemConfigInputs() {}

  public record Basic(
      @Min(0) long version,
      @NotBlank @Size(max = 40) String systemName,
      @NotBlank @Size(max = 360000) String systemLogo,
      @NotBlank @Size(max = 120) String systemDescription) {}

  public record Login(
      @Min(0) long version,
      boolean captchaEnabled,
      @Min(4) @Max(6) int captchaLength,
      @Min(60) @Max(300) int captchaExpireSeconds) {}

  public record Registration(
      @Min(0) long version, boolean registrationEnabled, boolean registrationCaptchaEnabled) {}
}
