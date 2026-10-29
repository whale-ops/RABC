package com.star.monitor.model;


// 包职责：定义日志筛选参数和审计事件模型，分离查询输入与写入快照。
// 模块联系：连接 monitor.controller、framework.aspect 和 monitor.service，避免业务层相互依赖内部类型。

/**
 * 定义日志切面传递给写入器的事件快照，仅包含身份、请求、结果与耗时信息。
 *
 * <p>关联模块：AuditLogAspect 组装，AuditLogWriter 保存；不包含密码、令牌或请求响应正文。
 */
public record LogEvent(
    String tenantId,
    String tenantCode,
    String userId,
    String username,
    String displayName,
    String module,
    String action,
    String requestMethod,
    String requestPath,
    String ip,
    String browser,
    String os,
    String userAgent,
    String status,
    int httpStatus,
    String errorCode,
    long durationMs,
    String traceId) {}
