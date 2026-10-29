package com.star.monitor.service;

import com.star.common.BusinessException;
import com.star.framework.security.PermissionService;
import com.star.monitor.model.CacheSnapshot;
import com.star.monitor.model.ServerSnapshot;
import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// 包职责：采集主机、JVM、数据库健康和 Redis 实际指标，保存有界采样历史。
// 模块联系：读取 JDK 管理接口及 Redis INFO，由 MonitorController 经 RBAC 权限提供给前端。

/** 每五秒采样，最多保留十二分钟；未采集到的 CPU 值返回 null，不生成演示数据。 */
@Service
public class MonitorMetricsService {
  private final PermissionService permissions;
  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;
  private final int database;
  private final Deque<ServerSnapshot.Point> hostHistory = new ArrayDeque<>();
  private final Deque<CacheSnapshot.Point> cacheHistory = new ArrayDeque<>();
  private ServerSnapshot server;
  private CacheSnapshot cache;

  public MonitorMetricsService(PermissionService permissions, StringRedisTemplate redis,
      JdbcTemplate jdbc, @Value("${spring.data.redis.database:0}") int database) {
    this.permissions = permissions; this.redis = redis; this.jdbc = jdbc; this.database = database;
  }

  @Scheduled(fixedDelay = 5000)
  public synchronized void sample() {
    long now = System.currentTimeMillis();
    var os = ManagementFactory.getOperatingSystemMXBean();
    Double cpu = null; long total = 0, used = 0;
    if (os instanceof OperatingSystemMXBean extended) {
      double raw = extended.getCpuLoad();
      if (Double.isFinite(raw) && raw >= 0) cpu = Math.min(100, raw * 100);
      total = Math.max(0, extended.getTotalMemorySize());
      used = Math.max(0, total - extended.getFreeMemorySize());
    }
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    var point = new ServerSnapshot.Point(now, cpu, used, total, heap.getUsed(), Math.max(0, heap.getMax()));
    append(hostHistory, point);
    var disks = new ArrayList<ServerSnapshot.Disk>();
    var roots = File.listRoots();
    if (roots != null) for (var root : roots) {
      long capacity = root.getTotalSpace();
      if (capacity > 0) disks.add(new ServerSnapshot.Disk(root.toString(), capacity, capacity - root.getFreeSpace()));
    }
    var health = new ArrayList<ServerSnapshot.Health>();
    health.add(new ServerSnapshot.Health("应用服务", true, 0));
    long start = System.nanoTime(); boolean dbHealthy;
    try { dbHealthy = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)); }
    catch (RuntimeException unavailable) { dbHealthy = false; }
    health.add(new ServerSnapshot.Health("MySQL", dbHealthy, elapsed(start)));
    start = System.nanoTime();
    try {
      var connectionFactory = redis.getConnectionFactory();
      if (connectionFactory == null) throw new IllegalStateException();
      try (var connection = connectionFactory.getConnection()) {
        boolean redisHealthy = "PONG".equals(connection.ping());
        health.add(new ServerSnapshot.Health("Redis", redisHealthy, elapsed(start)));
        // 仅提取下列指标，不把 INFO 原文、客户端地址或键值返回前端。
        Properties info = connection.serverCommands().info();
        Properties commands = connection.serverCommands().info("commandstats");
        if (info == null) throw new IllegalStateException();
        var cachePoint = new CacheSnapshot.Point(now, number(info,"used_memory"), number(info,"instantaneous_ops_per_sec"));
        append(cacheHistory, cachePoint);
        long hits = number(info,"keyspace_hits"), misses = number(info,"keyspace_misses");
        Double hitRate = hits + misses > 0 ? 100d * hits / (hits + misses) : null;
        var counts = new ArrayList<CacheSnapshot.Command>();
        if (commands != null) for (String key : commands.stringPropertyNames()) {
          if (key.matches("cmdstat_[a-zA-Z0-9_|-]+")) counts.add(new CacheSnapshot.Command(
              key.substring(8).toUpperCase(Locale.ROOT), field(commands.getProperty(key), "calls")));
        }
        counts.sort(Comparator.comparingLong(CacheSnapshot.Command::calls).reversed());
        String keyspace = info.getProperty("db"+database, "");
        cache = new CacheSnapshot(cachePoint, List.copyOf(cacheHistory), info.getProperty("redis_version","未知"),
            info.getProperty("redis_mode","未知"), database, field(keyspace,"keys"), field(keyspace,"expires"),
            number(info,"maxmemory"), number(info,"connected_clients"), hits, misses, hitRate,
            number(info,"uptime_in_seconds"), List.copyOf(counts.subList(0,Math.min(12,counts.size()))));
      }
    } catch (RuntimeException unavailable) {
      cache = null;
      if (health.stream().noneMatch(h -> h.name().equals("Redis")))
        health.add(new ServerSnapshot.Health("Redis", false, elapsed(start)));
    }
    var runtime = ManagementFactory.getRuntimeMXBean();
    server = new ServerSnapshot(point, List.copyOf(hostHistory), disks, os.getName(), os.getArch(),
        System.getProperty("java.version"), os.getAvailableProcessors(), runtime.getStartTime(),
        runtime.getUptime()/1000, ManagementFactory.getThreadMXBean().getThreadCount(), List.copyOf(health));
  }

  public synchronized ServerSnapshot server() {
    permissions.require("monitor:server:read");
    if (server == null) sample();
    return server;
  }
  public synchronized CacheSnapshot cache() {
    permissions.require("monitor:cache:read");
    if (server == null) sample();
    if (cache == null) throw new BusinessException(503,"MONITOR_UNAVAILABLE","Redis 指标暂不可用，请检查连接或 INFO 命令权限");
    return cache;
  }
  private static <T> void append(Deque<T> points, T value) { points.addLast(value); while(points.size()>144) points.removeFirst(); }
  private static long elapsed(long start) { return Math.max(0,(System.nanoTime()-start)/1_000_000); }
  private static long number(Properties p, String key) { try { return Math.max(0,Long.parseLong(p.getProperty(key,"0"))); } catch(NumberFormatException invalid) { return 0; } }
  static long field(String value, String key) {
    for(String item:value.split(",")) if(item.startsWith(key+"=")) {
      try {return Math.max(0,Long.parseLong(item.substring(key.length()+1)));} catch(NumberFormatException invalid) {return 0;}
    }
    return 0;
  }
}
