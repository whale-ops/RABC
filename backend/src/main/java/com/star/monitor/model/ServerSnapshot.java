package com.star.monitor.model;

import java.util.List;

// 包职责：定义监控接口的白名单响应，不包含环境变量、数据库地址和凭据。
// 模块联系：MonitorMetricsService 采集，ServiceMonitor.vue 使用 ECharts 展示。

/** 当前 Java 后端所在主机和 JVM 的真实采样，不代表远程对象存储服务器。 */
public record ServerSnapshot(Point current, List<Point> history, List<Disk> disks,
    String os, String arch, String javaVersion, int cores, long startedAt,
    long uptimeSeconds, int threads, List<Health> health) {
  public record Point(long at, Double cpuPercent, long memoryUsed, long memoryTotal,
      long heapUsed, long heapMax) {}
  public record Disk(String name, long total, long used) {}
  public record Health(String name, boolean healthy, long latencyMs) {}
}
