package com.star.framework.aspect;

import cn.dev33.satoken.exception.*;
import com.star.auth.dto.AuthInputs;
import com.star.common.BusinessException;
import com.star.framework.annotation.OperationAudit;
import com.star.framework.security.Actor;
import com.star.monitor.model.LogEvent;
import com.star.monitor.service.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.aspectj.lang.*;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.*;

// 包职责：提供跨模块日志切面，统一采集登录和操作的安全快照。
// 模块联系：拦截审计注解，从 auth 和 framework.security 获取身份，委托 monitor.service 保存日志。

/**
 * 通过 AOP 统一捕获登录和业务操作的结果、客户端信息及执行耗时。
 *
 * <p>关联模块：识别 LoginAudit 与 OperationAudit；调用 AuditLogWriter 保存，保留原业务返回值和异常。
 */
@Aspect
@Component
@Order(20)
public class AuditLogAspect {
  private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
  private final AuditLogWriter writer;
  private final JdbcTemplate jdbc;

  public AuditLogAspect(AuditLogWriter writer, JdbcTemplate jdbc) {
    this.writer = writer;
    this.jdbc = jdbc;
  }

  /** 围绕标记的认证方法记录一次登录结果，身份匹配失败时仍保留尝试账号。 */
  @Around("@annotation(com.star.framework.annotation.LoginAudit)")
  public Object login(ProceedingJoinPoint point) throws Throwable {
    AuthInputs.Login input =
        Arrays.stream(point.getArgs())
            .filter(AuthInputs.Login.class::isInstance)
            .map(AuthInputs.Login.class::cast)
            .findFirst()
            .orElseThrow();
    return record(point, true, "账号认证", "登录", safeIdentity(input.tenantCode(), input.username()));
  }

  /** 只拦截控制器中的写请求和导出，读取当前身份后交给统一记录流程。 */
  @Around("@within(audit)")
  public Object operation(ProceedingJoinPoint point, OperationAudit audit) throws Throwable {
    var req = request();
    if (req == null || !shouldRecord(req)) return point.proceed();
    Identity identity = new Identity(null, "", null, "", "");
    try {
      var user = Actor.user();
      String code =
          jdbc.queryForObject(
              "SELECT code FROM sys_tenant WHERE id=?", String.class, user.tenantId);
      identity = new Identity(user.tenantId, code, user.id, user.username, user.name);
    } catch (RuntimeException ignored) {
      for (Object arg : point.getArgs()) {
        if (arg instanceof AuthInputs.Register input)
          identity = safeIdentity(input.tenantCode(), input.username());
        if (arg instanceof AuthInputs.ForgotPassword input)
          identity = safeIdentity(input.tenantCode(), input.username());
      }
    }
    return record(point, false, module(req, audit.value()), action(req), identity);
  }

  /** 保留业务原返回或异常，在结束时组装安全快照；日志保存失败不覆盖业务结果。 */
  private Object record(
      ProceedingJoinPoint point, boolean login, String module, String action, Identity identity)
      throws Throwable {
    long started = System.nanoTime();
    Throwable failure = null;
    Object result = null;
    try {
      result = point.proceed();
      return result;
    } catch (Throwable error) {
      failure = error;
      throw error;
    } finally {
      var req = request();
      String agent = req == null ? "" : limited(req.getHeader("User-Agent"), 512);
      int status = failure == null ? successStatus(point, result) : failureStatus(failure);
      String code = failure == null ? "" : failureCode(failure);
      var event =
          new LogEvent(
              identity.tenantId,
              limited(identity.tenantCode, 64),
              identity.userId,
              limited(identity.username, 64),
              limited(identity.name, 64),
              limited(module, 64),
              action,
              req == null ? "" : limited(req.getMethod(), 10),
              req == null ? "" : limited(req.getRequestURI(), 300),
              req == null ? "" : limited(req.getRemoteAddr(), 64),
              browser(agent),
              os(agent),
              agent,
              failure == null ? "成功" : "失败",
              status,
              code,
              Math.max(0, (System.nanoTime() - started) / 1_000_000),
              limited(MDC.get("traceId"), 64));
      try {
        writer.write(login, event);
      } catch (RuntimeException writeFailure) {
        // 日志保存失败不能改写已完成的业务结果，也不能输出业务正文。
        log.error(
            "Audit persistence failed: traceId={}, category={}, errorType={}",
            MDC.get("traceId"),
            login ? "login" : "operation",
            writeFailure.getClass().getSimpleName());
      }
    }
  }

  private record Identity(
      String tenantId, String tenantCode, String userId, String username, String name) {}

  private Identity safeIdentity(String tenantCode, String username) {
    try {
      return identity(tenantCode, username);
    } catch (RuntimeException unavailable) {
      return new Identity(null, tenantCode, null, username, "");
    }
  }

  private Identity identity(String tenantCode, String username) {
    var rows =
        jdbc.queryForList(
            "SELECT CAST(t.id AS CHAR) tenantId,CAST(u.id AS CHAR) userId,u.name FROM sys_tenant t"
                + " LEFT JOIN sys_user u ON u.tenant_id=t.id AND u.username=? WHERE t.code=?",
            username,
            tenantCode);
    if (rows.isEmpty()) return new Identity(null, tenantCode, null, username, "");
    var row = rows.get(0);
    return new Identity(
        (String) row.get("tenantId"),
        tenantCode,
        (String) row.get("userId"),
        username,
        Objects.toString(row.get("name"), ""));
  }

  private HttpServletRequest request() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        ? attrs.getRequest()
        : null;
  }

  /** 排除登录、刷新和退出等专门处理的路径，避免重复日志或查询引起自循环。 */
  private boolean shouldRecord(HttpServletRequest req) {
    String path = req.getRequestURI();
    if (Set.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").contains(path))
      return false;
    return Set.of("POST", "PUT", "PATCH", "DELETE").contains(req.getMethod())
        || path.endsWith("/export");
  }

  private String module(HttpServletRequest req, String fallback) {
    String path = req.getRequestURI();
    for (var entry :
        Map.of(
                "users",
                "用户管理",
                "roles",
                "角色管理",
                "menus",
                "菜单管理",
                "depts",
                "部门管理",
                "posts",
                "岗位管理",
                "account-requests",
                "账号申请")
            .entrySet())
      if (path.startsWith("/api/v1/system/" + entry.getKey() + "/")
          || path.equals("/api/v1/system/" + entry.getKey())) return entry.getValue();
    return fallback;
  }

  private String action(HttpServletRequest req) {
    if (req != null && req.getRequestURI().endsWith("/kick")) return "强退会话";
    String path = req.getRequestURI();
    if (path.startsWith("/api/v1/system/config/") && "PUT".equals(req.getMethod())) return "修改配置";
    if (path.startsWith("/api/v1/business/orders/")) {
      if (path.endsWith("/cancel")) return "取消订单";
      if (path.endsWith("/pay")) return "确认收款";
      if (path.endsWith("/ship")) return "订单发货";
      if (path.endsWith("/complete")) return "完成订单";
    }
    if ("DELETE".equals(req.getMethod())
        && path.matches("/api/v1/storage/files/[0-9]{1,20}/record")) return "移除文件记录";
    if (path.endsWith("/export")) return "导出";
    if (path.endsWith("/batch-delete") || "DELETE".equals(req.getMethod())) return "删除";
    if (path.endsWith("/import")) return "导入";
    if (path.endsWith("/password-reset")) return "重置密码";
    if (path.endsWith("/password")) return "修改密码";
    if (path.endsWith("/profile")) return "更新资料";
    if (path.endsWith("/status")) return "切换状态";
    if (path.endsWith("/permissions") || "PUT".equals(req.getMethod())) return "分配权限";
    if (path.endsWith("/review")) return "审核申请";
    if (path.endsWith("/register")) return "注册申请";
    if (path.endsWith("/forgot-password")) return "找回申请";
    return "POST".equals(req.getMethod()) ? "新增" : "修改";
  }

  private int successStatus(ProceedingJoinPoint point, Object result) {
    if (result instanceof ResponseEntity<?> response) return response.getStatusCode().value();
    var annotation =
        ((MethodSignature) point.getSignature()).getMethod().getAnnotation(ResponseStatus.class);
    return annotation == null ? 200 : annotation.value().value();
  }

  private int failureStatus(Throwable error) {
    if (error instanceof BusinessException business) return business.status;
    if (error instanceof NotPermissionException) return 403;
    if (error instanceof NotLoginException) return 401;
    if (error instanceof org.springframework.dao.DataIntegrityViolationException) return 409;
    return 500;
  }

  private String failureCode(Throwable error) {
    if (error instanceof BusinessException business) return limited(business.code, 64);
    if (error instanceof NotPermissionException) return "PERMISSION_DENIED";
    if (error instanceof NotLoginException) return "AUTH_REQUIRED";
    if (error instanceof org.springframework.dao.DataIntegrityViolationException)
      return "RESOURCE_CONFLICT";
    return "INTERNAL_ERROR";
  }

  static String limited(String value, int length) {
    if (value == null) return "";
    String clean = value.replaceAll("[\\p{Cntrl}]", " ");
    return clean.substring(0, Math.min(length, clean.length()));
  }

  static String browser(String agent) {
    if (agent.contains("Edg/")) return "Edge";
    if (agent.contains("OPR/")) return "Opera";
    if (agent.contains("Chrome/")) return "Chrome";
    if (agent.contains("Firefox/")) return "Firefox";
    if (agent.contains("Safari/")) return "Safari";
    return "未知";
  }

  static String os(String agent) {
    if (agent.contains("Android")) return "Android";
    if (agent.contains("iPhone") || agent.contains("iPad")) return "iOS";
    if (agent.contains("Windows")) return "Windows";
    if (agent.contains("Mac OS")) return "macOS";
    if (agent.contains("Linux")) return "Linux";
    return "未知";
  }
}
