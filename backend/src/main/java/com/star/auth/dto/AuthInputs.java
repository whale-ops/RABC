package com.star.auth.dto;

import jakarta.validation.constraints.*;
import java.util.List;

// 包职责：定义认证、个人资料与账号申请的请求字段及校验约束。
// 模块联系：由 auth.controller 接收，交给 auth.service 执行业务校验，供 framework.aspect 提取安全身份信息。

/**
 * 定义认证、个人资料及账号申请的请求模型，保留字段校验约束。
 *
 * <p>关联模块：auth.controller 接收参数并触发校验；AuthService、AccountService 和 AccountRequestService
 * 执行业务校验，AuditLogAspect 读取安全身份信息。
 */
public final class AuthInputs {
  private AuthInputs() {}

  /** 登录使用租户编码和账号定位用户；角色编码不能代替租户编码。 */
  public record Login(
      @NotBlank @Size(max = 64) String tenantCode,
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Size(max = 72) String password,
      @Size(max = 64) String captchaId,
      @Size(max = 16) String captchaCode) {}

  public record Password(
      @NotBlank @Size(max = 72) String oldPassword, @NotBlank @Size(max = 72) String newPassword) {}

  public record Register(
      @NotBlank @Size(max = 64) String tenantCode,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,64}") String username,
      @NotBlank @Size(max = 64) String name,
      @NotBlank @Email @Size(max = 128) String email,
      @NotBlank @Pattern(regexp = "1[0-9]{10}") String phone,
      @NotBlank @Size(max = 72) String password,
      @Size(max = 64) String captchaId,
      @Size(max = 16) String captchaCode) {}

  public record ForgotPassword(
      @NotBlank @Size(max = 64) String tenantCode,
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Email @Size(max = 128) String email,
      @NotBlank @Pattern(regexp = "1[0-9]{10}") String phone) {}

  /** 管理员审核请求：处理说明及部门角色由账号申请服务继续校验。 */
  public record ReviewRequest(
      @NotBlank @Pattern(regexp = "approve|reject") String decision,
      @Size(max = 500) String note,
      @Pattern(regexp = "[0-9]{1,20}") String deptId,
      @Size(max = 10) List<@Pattern(regexp = "[0-9]{1,20}") String> roleIds,
      @Size(max = 72) String newPassword) {}

  public record Profile(
      @NotBlank @Size(max = 64) String name,
      @Email @Size(max = 128) String email,
      @Pattern(regexp = "^$|^1[0-9]{10}$") String phone,
      @Size(max = 500) String bio) {}
}
