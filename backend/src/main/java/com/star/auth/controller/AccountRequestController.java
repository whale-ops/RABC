package com.star.auth.controller;

import com.star.auth.dto.AuthInputs;
import com.star.auth.service.AccountRequestService;
import com.star.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// 包职责：认证与账号申请的 HTTP 入口，接收请求、触发参数校验并封装响应。
// 模块联系：调用 auth.service，对接前端登录、个人中心及账号申请审核页面。

/**
 * 接收公开注册、找回密码申请，以及管理员查询和审核申请的请求。
 *
 * <p>关联模块：调用 AccountRequestService；前端 Login.vue 提交申请，AccountRequests.vue 执行审核。
 */
@com.star.framework.annotation.OperationAudit("账号申请")
@RestController
@RequestMapping("/api/v1")
public class AccountRequestController {
  private final AccountRequestService service;

  public AccountRequestController(AccountRequestService service) {
    this.service = service;
  }

  @PostMapping("/auth/register")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object register(@Valid @RequestBody AuthInputs.Register body, HttpServletRequest req) {
    return ApiResponse.ok(service.register(body, req));
  }

  @PostMapping("/auth/forgot-password")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object forgot(@Valid @RequestBody AuthInputs.ForgotPassword body, HttpServletRequest req) {
    service.forgot(body, req);
    return ApiResponse.ok("若信息与账号一致，管理员将收到申请。请通过企业内部渠道联系管理员核实身份并获取新密码");
  }

  @GetMapping("/system/account-requests")
  public Object list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "pending") String status) {
    return ApiResponse.ok(service.list(page, size, status));
  }

  @PostMapping("/system/account-requests/{id}/review")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void review(@PathVariable String id, @Valid @RequestBody AuthInputs.ReviewRequest body) {
    service.review(id, body);
  }
}
