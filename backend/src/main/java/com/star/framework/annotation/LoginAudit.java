package com.star.framework.annotation;


import java.lang.annotation.*;

// 包职责：定义登录和操作审计注解，声明需要记录的认证方法与控制器。
// 模块联系：由 auth、system、monitor、storage 标记业务入口，交给 framework.aspect 识别并拦截。

/**
 * 标记需要记录登录结果的认证方法，由 AOP 捕获成功或失败。
 *
 * <p>关联模块：应用于 AuthService.login，AuditLogAspect 读取标记并交给 AuditLogWriter 写入登录日志。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginAudit {}
