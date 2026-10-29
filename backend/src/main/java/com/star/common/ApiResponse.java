package com.star.common;

import org.slf4j.MDC;

// 包职责：提供跨模块通用响应、业务异常及统一异常处理。
// 模块联系：供 auth、system、monitor、storage 共用，与前端请求封装统一响应格式。

/**
 * 定义接口统一响应结构，包含业务码、消息、数据和请求追踪编号。
 *
 * <p>关联模块：各控制器封装成功响应，ExceptionAdvice 封装失败响应；前端 request.ts 统一解包。
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>("OK", "成功", data, MDC.get("traceId"));
  }
}
