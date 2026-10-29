package com.star.auth.controller;

import com.star.auth.dto.AuthInputs;
import com.star.auth.service.AccountService;
import com.star.auth.service.AuthService;
import com.star.common.ApiResponse;
import com.star.system.service.SystemService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// 包职责：认证与账号申请的 HTTP 入口，接收请求、触发参数校验并封装响应。
// 模块联系：调用 auth.service，对接前端登录、个人中心及账号申请审核页面。

/**
 * 提供登录、刷新、退出、个人资料和改密接口，统一封装认证相关响应。
 *
 * <p>关联模块：调用 AuthService、AccountService 和 SystemService；对应前端 api/auth.ts、Login.vue 与 Profile.vue。
 */
@com.star.framework.annotation.OperationAudit("账号管理")
@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final AuthService auth;
  private final AccountService account;
  private final SystemService system;

  public AuthController(AuthService auth, AccountService account, SystemService system) {
    this.auth = auth;
    this.account = account;
    this.system = system;
  }

  @GetMapping("/health")
  public Object health() {
    return ApiResponse.ok(java.util.Map.of("status", "UP"));
  }

  @GetMapping("/auth/csrf")
  public Object csrf(HttpServletRequest req, HttpServletResponse res) {
    return ApiResponse.ok(auth.csrf(req, res));
  }

  @PostMapping("/auth/login")
  public Object login(
      @Valid @RequestBody AuthInputs.Login body, HttpServletRequest req, HttpServletResponse res) {
    return ApiResponse.ok(auth.login(body, req, res));
  }

  @PostMapping("/auth/refresh")
  public Object refresh(HttpServletRequest req, HttpServletResponse res) {
    return ApiResponse.ok(auth.refresh(req, res));
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest req, HttpServletResponse res) {
    auth.logout(req, res);
  }

  @GetMapping("/auth/me")
  public Object me() {
    return ApiResponse.ok(account.me());
  }

  @GetMapping("/auth/menus")
  public Object menus() {
    return ApiResponse.ok(system.navigation());
  }

  @PatchMapping("/auth/profile")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void profile(@Valid @RequestBody AuthInputs.Profile body) {
    account.profile(body);
  }

  @PatchMapping("/auth/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(@Valid @RequestBody AuthInputs.Password body) {
    account.password(body);
  }
}
