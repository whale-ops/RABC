package com.star.framework.annotation;


import java.lang.annotation.*;

// 包职责：定义登录和操作审计注解，声明需要记录的认证方法与控制器。
// 模块联系：由 auth、system、monitor、storage 标记业务入口，交给 framework.aspect 识别并拦截。

/**
 * 标记需要审计的控制器，启用写请求和导出请求的操作日志。
 *
 * <p>关联模块：系统、账号、申请及日志控制器使用此注解；AuditLogAspect 筛选请求且不序列化业务正文。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationAudit {
  String value();
}
