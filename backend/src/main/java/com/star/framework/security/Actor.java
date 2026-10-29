package com.star.framework.security;

import com.star.system.entity.User;

// 包职责：提供当前登录身份、RBAC 权限及用户数据范围计算。
// 模块联系：从 auth 获取认证身份，读取 system 用户、角色和菜单数据，供各业务服务校验权限。

/**
 * 在当前请求线程中保存已认证用户和租户，避免业务层接收可伪造的租户参数。
 *
 * <p>关联模块：由 WebConfig 写入，RequestContextFilter 兜底清理；PermissionService 与各业务服务读取。
 */
public final class Actor {
  private static final ThreadLocal<User> CURRENT = new ThreadLocal<>();

  public static User user() {
    var u = CURRENT.get();
    if (u == null) throw new com.star.common.BusinessException(401, "AUTH_REQUIRED", "请先登录");
    return u;
  }

  public static String tenant() {
    return user().tenantId;
  }

  public static void set(User user) {
    CURRENT.set(user);
  }

  public static void clear() {
    CURRENT.remove();
  }

  private Actor() {}
}
