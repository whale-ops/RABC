package com.star.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 包职责：启用服务端定时采样，避免监控图表依赖浏览器模拟数据。
// 模块联系：驱动 monitor.service 的采样任务；采样历史仅保留在当前应用内存。

/** 启用 Spring 定时任务，应用停止时采样线程由框架关闭。 */
@Configuration
@EnableScheduling
public class MonitorConfig {}
