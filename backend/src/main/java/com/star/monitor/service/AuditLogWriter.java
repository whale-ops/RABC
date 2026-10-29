package com.star.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.monitor.model.LogEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

// 包职责：按租户和权限查询日志，独立事务写入审计记录并处理身份匿名化。
// 模块联系：接收 framework.aspect 的事件，供 monitor.controller 查询，关联 system 用户删除与 tool.excel 导出。

/**
 * 使用独立事务写入登录或操作日志，并与用户删除流程协调身份匿名化。
 *
 * <p>关联模块：接收 AuditLogAspect 的 LogEvent；写入 sys_login_log 或 sys_operation_log，供日志查询服务读取。
 */
@Service
public class AuditLogWriter {
  private final JdbcTemplate jdbc;

  public AuditLogWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 独立事务保存事件；先与用户删除流程协调租户锁，避免迟到事件重新写回已删除身份。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(boolean login, LogEvent event) {
    String table = login ? "sys_login_log" : "sys_operation_log";
    // 与账号删除串行协调，避免尚未结束的请求重新写回已删除的个人信息。
    if (event.tenantId() != null)
      jdbc.queryForObject(
          "SELECT id FROM sys_tenant WHERE id=? FOR UPDATE", String.class, event.tenantId());
    boolean deleted =
        event.userId() != null
            && jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND id=?",
                    Long.class,
                    event.tenantId(),
                    event.userId())
                == 0;
    jdbc.update(
        "INSERT INTO "
            + table
            + " (id,tenant_id,tenant_code,user_id,username,display_name,module,action,request_method,request_path,ip,browser,os,user_agent,status,http_status,error_code,duration_ms,trace_id)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        IdWorker.getIdStr(),
        event.tenantId(),
        event.tenantCode(),
        event.userId(),
        deleted ? "已删除" : event.username(),
        deleted ? "已删除" : event.displayName(),
        event.module(),
        event.action(),
        event.requestMethod(),
        event.requestPath(),
        deleted ? "" : event.ip(),
        event.browser(),
        event.os(),
        deleted ? "" : event.userAgent(),
        event.status(),
        event.httpStatus(),
        event.errorCode(),
        event.durationMs(),
        event.traceId());
  }
}
