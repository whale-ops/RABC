package com.star.monitor.model;

import java.util.List;

// 包职责：提供在线会话表格的安全分页模型，不包含访问令牌或刷新凭据。
// 模块联系：OnlineSessionRegistry 校验会话，OnlineUsers.vue 展示并管理授权范围内的会话。

/** 在线指最近十五分钟有认证请求；同一账号不同登录会话分别计数。 */
public record OnlineSessionPage(List<Item> records, int total, boolean truncated) {
  public record Item(String id, String username, String name, String ip, String browser, String os,
      long loginAt, long lastSeen, boolean current, boolean protectedAccount) {}
}
