package com.star.common;


// 包职责：提供跨模块通用响应、业务异常及统一异常处理。
// 模块联系：供 auth、system、monitor、storage 共用，与前端请求封装统一响应格式。

/**
 * 表示可预期的业务拒绝结果，携带 HTTP 状态、业务错误码及用户提示。
 *
 * <p>关联模块：业务服务抛出，ExceptionAdvice 转成统一响应；AuditLogAspect 提取安全错误码记录失败日志。
 */
public class BusinessException extends RuntimeException {
  public final int status;
  public final String code;

  public BusinessException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static BusinessException bad(String message) {
    return new BusinessException(400, "VALIDATION_ERROR", message);
  }

  public static BusinessException denied() {
    return new BusinessException(403, "PERMISSION_DENIED", "没有执行此操作的权限");
  }

  public static BusinessException missing() {
    return new BusinessException(404, "RESOURCE_NOT_FOUND", "记录不存在或不在可访问范围内");
  }

  public static BusinessException conflict(String message) {
    return new BusinessException(409, "CONFLICT", message);
  }
}
