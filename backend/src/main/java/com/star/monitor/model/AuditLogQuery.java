package com.star.monitor.model;

// 包职责：定义日志筛选参数和审计事件模型，分离查询输入与写入快照。
// 模块联系：连接 monitor.controller、framework.aspect 和 monitor.service，避免业务层相互依赖内部类型。

/**
 * 定义日志分页、关键字、结果、操作类型及时间范围等筛选参数，供查询和导出复用。
 *
 * <p>关联模块：AuditLogController 构造参数，AuditLogService 校验并按当前租户查询登录日志或操作日志。
 */
public record AuditLogQuery(
    int page,
    int size,
    String keyword,
    String status,
    String action,
    String start,
    String end,
    String direction) {}
