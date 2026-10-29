package com.star.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 包职责：提供跨模块通用响应、业务异常及统一异常处理。
// 模块联系：供 auth、system、monitor、storage 共用，与前端请求封装统一响应格式。

/**
 * 统一处理认证、权限、参数、数据库及未知异常，避免向客户端暴露内部堆栈。
 *
 * <p>关联模块：接收各控制器和服务抛出的异常，返回 ApiResponse；前端 request.ts 转换为可展示错误。
 */
@RestControllerAdvice
public class ExceptionAdvice {
  private static final Logger log = LoggerFactory.getLogger(ExceptionAdvice.class);

  @ExceptionHandler(
      org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
  public void clientDisconnected(
      org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
    log.debug("Client disconnected before response completed");
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<?> business(BusinessException e) {
    if (e.status == 429)
      return ResponseEntity.status(429)
          .header("Retry-After", "60")
          .body(new ApiResponse<>(e.code, e.getMessage(), null, MDC.get("traceId")));
    return error(e.status, e.code, e.getMessage());
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<?> uploadTooLarge(Exception e) {
    return error(413, "FILE_TOO_LARGE", "文件或请求超过上传上限，单个文件不能超过 20 MB");
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  public ResponseEntity<?> notFound(Exception e) {
    return error(404, "RESOURCE_NOT_FOUND", "接口不存在");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> method(Exception e) {
    return error(405, "METHOD_NOT_ALLOWED", "请求方法不支持");
  }

  @ExceptionHandler(NotLoginException.class)
  public ResponseEntity<?> login(NotLoginException e) {
    return error(401, "AUTH_REQUIRED", "登录已失效，请重新登录");
  }

  @ExceptionHandler(NotPermissionException.class)
  public ResponseEntity<?> permission(NotPermissionException e) {
    return error(403, "PERMISSION_DENIED", "没有执行此操作的权限");
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return error(400, "VALIDATION_ERROR", "请求字段不合法，请检查必填项、格式及字段类型");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> integrity(Exception e) {
    return error(409, "RESOURCE_CONFLICT", "名称或编码已存在，或记录仍被其他数据引用");
  }

  @ExceptionHandler({
    org.springframework.data.redis.RedisConnectionFailureException.class,
    org.springframework.dao.QueryTimeoutException.class,
    org.springframework.jdbc.CannotGetJdbcConnectionException.class
  })
  public ResponseEntity<?> unavailable(Exception e) {
    log.error("Required dependency unavailable: {}", e.getClass().getSimpleName());
    return error(503, "SERVICE_UNAVAILABLE", "数据库或会话服务暂时不可用，请稍后重试");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unknown(Exception e) {
    log.error("Unhandled request failure", e);
    return error(500, "INTERNAL_ERROR", "服务处理失败，请联系管理员并提供请求编号");
  }

  private ResponseEntity<?> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ApiResponse<>(code, message, null, MDC.get("traceId")));
  }
}
