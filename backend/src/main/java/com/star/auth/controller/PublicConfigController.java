package com.star.auth.controller;

import com.star.auth.service.AuthService;
import com.star.auth.service.CaptchaService;
import com.star.common.ApiResponse;
import com.star.system.service.SystemConfigService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

// 包职责：向登录页提供品牌和认证开关白名单，以及经过页面校验的图片验证码。
// 模块联系：公开读取不返回内部环境配置；验证码签发由 AuthService 验证 CSRF，再委托 CaptchaService。
@RestController
@Validated
@RequestMapping("/api/v1/auth")
public class PublicConfigController {
  private final SystemConfigService settings;
  private final CaptchaService captcha;
  private final AuthService auth;

  public PublicConfigController(
      SystemConfigService settings, CaptchaService captcha, AuthService auth) {
    this.settings = settings;
    this.captcha = captcha;
    this.auth = auth;
  }

  public record Challenge(
      @NotBlank @Size(max = 64) String tenantCode,
      @Pattern(regexp = "login|register") @NotNull String purpose) {}

  @GetMapping("/public-config")
  public Object read(
      @RequestParam @Size(min = 1, max = 64) String tenantCode, HttpServletResponse res) {
    res.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(settings.publicSettings(tenantCode));
  }

  @PostMapping("/captcha")
  public Object challenge(
      @Valid @RequestBody Challenge b, HttpServletRequest req, HttpServletResponse res) {
    auth.verifyCsrf(req);
    res.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(captcha.issue(b.tenantCode(), b.purpose(), req));
  }
}
