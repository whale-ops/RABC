package com.star.monitor.service;

import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.monitor.model.AuditLogQuery;
import com.star.tool.excel.ExcelFiles;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 包职责：按租户和权限查询日志，独立事务写入审计记录并处理身份匿名化。
// 模块联系：接收 framework.aspect 的事件，供 monitor.controller 查询，关联 system 用户删除与 tool.excel 导出。

/**
 * 按当前租户查询日志，校验筛选条件，提供详情和有数量限制的 Excel 导出。
 *
 * <p>关联模块：AuditLogController 调用，PermissionService 校验日志权限；对应前端 api/audit.ts 和 AuditLogs.vue。
 */
@Service
public class AuditLogService {
  private final JdbcTemplate jdbc;
  private final PermissionService permission;

  public AuditLogService(JdbcTemplate jdbc, PermissionService permission) {
    this.jdbc = jdbc;
    this.permission = permission;
  }

  private String table(String kind) {
    return switch (kind) {
      case "logins" -> "sys_login_log";
      case "operations" -> "sys_operation_log";
      default -> throw BusinessException.missing();
    };
  }

  private void authorize(String kind, String action) {
    table(kind);
    permission.require("audit:" + (kind.equals("logins") ? "login" : "operation") + ":" + action);
  }

  private String columns() {
    return "CAST(id AS CHAR) id,CAST(user_id AS CHAR) userId,tenant_code"
        + " tenantCode,username,display_name operator,module,action,request_method"
        + " requestMethod,request_path requestPath,ip,browser,os,status,http_status"
        + " httpStatus,error_code errorCode,duration_ms durationMs,trace_id"
        + " traceId,created_at createdAt";
  }

  private Map<String, Object> row(Map<String, Object> row) {
    if (row.get("createdAt") instanceof Timestamp stamp)
      row.put("createdAt", stamp.toInstant().toString());
    else if (row.get("createdAt") instanceof LocalDateTime time)
      row.put("createdAt", time.toInstant(ZoneOffset.UTC).toString());
    return row;
  }

  private record Filter(String where, List<Object> args) {}

  /** 校验查询范围并绑定 SQL 参数；关键字按普通文本匹配，时间统一转换为 UTC。 */
  private Filter filter(AuditLogQuery q) {
    if (q.page() < 1
        || q.page() > 100000
        || q.size() < 1
        || q.size() > 100
        || q.keyword().length() > 100
        || q.action().length() > 40
        || !Set.of("", "成功", "失败").contains(q.status())
        || !Set.of("asc", "desc").contains(q.direction())) throw BusinessException.bad("日志查询条件不合法");
    StringBuilder where = new StringBuilder(" WHERE tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(Actor.tenant());
    if (!q.keyword().isBlank()) {
      where.append(
          " AND (username LIKE ? ESCAPE '!' OR display_name LIKE ? ESCAPE '!' OR module LIKE ?"
              + " ESCAPE '!' OR ip LIKE ? ESCAPE '!' OR trace_id LIKE ? ESCAPE '!')");
      String like =
          "%" + q.keyword().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
      for (int i = 0; i < 5; i++) args.add(like);
    }
    if (!q.status().isBlank()) {
      where.append(" AND status=?");
      args.add(q.status());
    }
    if (!q.action().isBlank()) {
      where.append(" AND action=?");
      args.add(q.action());
    }
    LocalDateTime start = time(q.start()), end = time(q.end());
    if (start != null && end != null && start.isAfter(end))
      throw BusinessException.bad("开始时间不能晚于结束时间");
    if (start != null) {
      where.append(" AND created_at>=?");
      args.add(start);
    }
    if (end != null) {
      where.append(" AND created_at<=?");
      args.add(end);
    }
    return new Filter(where.toString(), args);
  }

  private LocalDateTime time(String value) {
    if (value.isBlank()) return null;
    try {
      return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
    } catch (RuntimeException invalid) {
      throw BusinessException.bad("日志时间需使用带时区的 ISO 格式");
    }
  }

  /** 先检查日志列表权限，再只查询当前租户的分页结果。 */
  public Map<String, Object> list(String kind, AuditLogQuery query) {
    authorize(kind, "list");
    var f = filter(query);
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table(kind) + f.where, Long.class, f.args.toArray());
    var args = new ArrayList<>(f.args);
    args.add(query.size());
    args.add((query.page() - 1) * query.size());
    var records =
        jdbc
            .queryForList(
                "SELECT "
                    + columns()
                    + " FROM "
                    + table(kind)
                    + f.where
                    + " ORDER BY created_at "
                    + query.direction()
                    + ",id "
                    + query.direction()
                    + " LIMIT ? OFFSET ?",
                args.toArray())
            .stream()
            .map(this::row)
            .toList();
    return Map.of("records", records, "total", total, "page", query.page(), "size", query.size());
  }

  /** 按当前租户和日志编号读取详情，其他租户的编号视为不存在。 */
  public Map<String, Object> detail(String kind, String id) {
    authorize(kind, "read");
    var rows =
        jdbc.queryForList(
            "SELECT "
                + columns()
                + ",user_agent userAgent FROM "
                + table(kind)
                + " WHERE tenant_id=? AND id=?",
            Actor.tenant(),
            id);
    if (rows.isEmpty()) throw BusinessException.missing();
    return row(rows.get(0));
  }

  /** 按当前筛选条件生成 XLSX，独立检查导出权限并限制最大记录数。 */
  public byte[] export(String kind, AuditLogQuery query) {
    authorize(kind, "export");
    var f = filter(query);
    var rows =
        jdbc.queryForList(
            "SELECT "
                + columns()
                + " FROM "
                + table(kind)
                + f.where
                + " ORDER BY created_at "
                + query.direction()
                + ",id "
                + query.direction()
                + " LIMIT 10001",
            f.args.toArray());
    if (rows.size() > 10000) throw BusinessException.bad("单次最多导出一万条，请缩小筛选范围");
    var keys =
        List.of(
            "id",
            "username",
            "operator",
            "module",
            "action",
            "ip",
            "browser",
            "os",
            "status",
            "httpStatus",
            "errorCode",
            "durationMs",
            "createdAt",
            "traceId");
    var labels =
        List.of(
            "日志ID", "用户账号", "操作人", "模块", "操作类型", "IP地址", "浏览器", "操作系统", "结果", "HTTP状态码", "错误码",
            "耗时(ms)", "时间(UTC)", "请求编号");
    return ExcelFiles.write(
        kind.equals("logins") ? "登录日志" : "操作日志",
        labels,
        rows.stream()
            .map(this::row)
            .map(row -> keys.stream().map(key -> Objects.toString(row.get(key), "")).toList())
            .toList());
  }
}
