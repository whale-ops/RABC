package com.star.system.dto;

import java.util.List;

// 包职责：定义首页统计的只读响应，不暴露访问账号摘要或其他租户信息。
// 模块联系：DashboardAnalyticsService 计算真实 PV/UV，SystemHome 使用 ECharts 绘制。

/** PV 为页面浏览次数，UV 为按登录账号去重的用户数，按北京时间统计。 */
public record DashboardAnalytics(int days,String timezone,String visitScope,String operationScope,
    long pv,long uv,long loginFailures,List<Point> trend,List<Distribution> distribution,long operationTotal) {
  public record Point(String date,long pv,long uv) {}
  public record Distribution(String name,long value) {}
}
