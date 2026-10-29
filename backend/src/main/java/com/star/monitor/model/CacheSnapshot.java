package com.star.monitor.model;

import java.util.List;

// 包职责：定义 Redis 实例指标与当前逻辑库统计的安全响应。
// 模块联系：MonitorMetricsService 读取 INFO 白名单，缓存监控页面绘制趋势和命令分布。

/** 内存、命中率和命令统计属于 Redis 实例；键数量仅属于配置的逻辑库。 */
public record CacheSnapshot(Point current, List<Point> history, String version, String mode,
    int database, long keys, long expiringKeys, long maxMemory, long clients,
    long hits, long misses, Double hitRate, long uptimeSeconds, List<Command> commands) {
  public record Point(long at, long memoryUsed, long operationsPerSecond) {}
  public record Command(String name, long calls) {}
}
