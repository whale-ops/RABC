package com.star.monitor.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import com.star.monitor.service.MonitorMetricsService;
import com.star.monitor.service.OnlineSessionRegistry;
import org.springframework.web.bind.annotation.*;

// 包职责：提供服务监控、缓存指标和当前租户在线会话的 HTTP 接口。
// 模块联系：由 WebConfig 认证，service 校验权限；强退操作通过 AOP 写入操作日志。

/** 监控查询均为只读；不提供 Redis 全库清空或敏感配置读取接口。 */
@RestController
@RequestMapping("/api/v1/monitor")
@OperationAudit("系统监控")
public class MonitorController {
  private final MonitorMetricsService metrics;
  private final OnlineSessionRegistry sessions;
  public MonitorController(MonitorMetricsService metrics,OnlineSessionRegistry sessions){this.metrics=metrics;this.sessions=sessions;}
  @GetMapping("/server") public Object server(){return ApiResponse.ok(metrics.server());}
  @GetMapping("/cache") public Object cache(){return ApiResponse.ok(metrics.cache());}
  @GetMapping("/sessions") public Object sessions(@RequestParam(defaultValue="") String keyword,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){
    return ApiResponse.ok(sessions.list(keyword,page,size,StpUtil.getTokenSession().getString("family")));
  }
  @PostMapping("/sessions/{id}/kick") public Object kick(@PathVariable String id){
    sessions.kick(id,StpUtil.getTokenSession().getString("family"));return ApiResponse.ok(null);
  }
}
