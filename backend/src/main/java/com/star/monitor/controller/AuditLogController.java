package com.star.monitor.controller;

import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import com.star.monitor.model.AuditLogQuery;
import com.star.monitor.service.AuditLogService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

// 包职责：提供登录日志和操作日志的只读查询、详情及导出接口。
// 模块联系：调用 monitor.service，对接前端日志页面；导出行为由 framework.aspect 审计。

/**
 * 提供登录日志和操作日志的只读分页、详情及导出接口。
 *
 * <p>关联模块：调用 AuditLogService；导出操作自身由 OperationAudit 记录，普通日志查询不会产生新操作日志。
 */
@OperationAudit("日志管理")
@RestController
@RequestMapping("/api/v1/audit/{kind:logins|operations}")
public class AuditLogController {
  private final AuditLogService service;

  public AuditLogController(AuditLogService service) {
    this.service = service;
  }

  @GetMapping
  public Object list(
      @PathVariable String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String action,
      @RequestParam(defaultValue = "") String start,
      @RequestParam(defaultValue = "") String end,
      @RequestParam(defaultValue = "desc") String direction) {
    return ApiResponse.ok(
        service.list(
            kind, new AuditLogQuery(page, size, keyword, status, action, start, end, direction)));
  }

  @GetMapping("/{id:[0-9]{1,20}}")
  public Object detail(@PathVariable String kind, @PathVariable String id) {
    return ApiResponse.ok(service.detail(kind, id));
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export(
      @PathVariable String kind,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String action,
      @RequestParam(defaultValue = "") String start,
      @RequestParam(defaultValue = "") String end,
      @RequestParam(defaultValue = "desc") String direction) {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"" + kind + ".xlsx\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(
            service.export(
                kind, new AuditLogQuery(1, 100, keyword, status, action, start, end, direction)));
  }
}
