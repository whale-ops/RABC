package com.star.system.controller;

import com.star.common.ApiResponse;
import com.star.system.service.DashboardService;
import com.star.system.service.DashboardAnalyticsService;
import org.springframework.web.bind.annotation.*;

// 包职责：提供首页统计、趋势和访问采集接口，不在控制器拼接业务数据。
// 模块联系：system.service 核验数据范围，前端 SystemHome 与路由钩子调用。

/** 首页统计要求 dashboard:read；访问采集要求已认证，不写操作审计日志。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  private final DashboardService service;
  private final DashboardAnalyticsService analytics;
  public DashboardController(DashboardService service,DashboardAnalyticsService analytics){this.service=service;this.analytics=analytics;}
  @GetMapping("/overview") public Object overview(){return ApiResponse.ok(service.overview());}
  @GetMapping("/activities") public Object activities(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="5") int size){return ApiResponse.ok(service.activities(page,size));}
  @GetMapping("/analytics") public Object analytics(@RequestParam(defaultValue="7") int days){return ApiResponse.ok(analytics.analytics(days));}
  public record VisitInput(String eventId) {}
  @PostMapping("/visit") public Object visit(@RequestBody VisitInput body){analytics.visit(body.eventId());return ApiResponse.ok(null);}
}