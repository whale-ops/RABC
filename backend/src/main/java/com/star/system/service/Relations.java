package com.star.system.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.framework.security.Actor;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 包职责：实现系统管理的查询、维护、授权、导入及数据关系处理。
// 模块联系：连接 system.mapper、framework.security 和 auth；保留首页历史审计，配合 monitor 处理用户日志匿名化。

/**
 * 集中维护用户角色、用户岗位、角色菜单和角色部门关系，同时写入原有业务审计。
 *
 * <p>关联模块：SystemService、AccountService 和账号审核复用；sys_audit 供首页查看历史操作，新日志由 monitor 模块独立记录。
 */
@Component
public class Relations {
  private final JdbcTemplate jdbc;

  public Relations(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final Map<String, String[]> LINKS =
      Map.of(
          "user-role", new String[] {"sys_user_role", "user_id", "role_id"},
          "user-post", new String[] {"sys_user_post", "user_id", "post_id"},
          "role-menu", new String[] {"sys_role_menu", "role_id", "menu_id"},
          "role-dept", new String[] {"sys_role_dept", "role_id", "dept_id"});

  public List<String> ids(String link, String tenant, String owner) {
    var p = LINKS.get(link);
    return jdbc.queryForList(
        "SELECT " + p[2] + " FROM " + p[0] + " WHERE tenant_id=? AND " + p[1] + "=?",
        String.class,
        tenant,
        owner);
  }

  /** 只替换当前租户下指定对象的关联集合，统一维护多对多关系。 */
  public void replace(String link, String owner, List<String> ids) {
    var p = LINKS.get(link);
    jdbc.update(
        "DELETE FROM " + p[0] + " WHERE tenant_id=? AND " + p[1] + "=?", Actor.tenant(), owner);
    for (String id : new LinkedHashSet<>(ids))
      jdbc.update(
          "INSERT INTO " + p[0] + " (tenant_id," + p[1] + "," + p[2] + ") VALUES (?,?,?)",
          Actor.tenant(),
          owner,
          id);
  }

  /** 用租户行锁串行化敏感管理变更，与授权及日志身份匿名化使用相同锁顺序。 */
  public void lockTenant() {
    jdbc.queryForObject(
        "SELECT id FROM sys_tenant WHERE id=? FOR UPDATE", String.class, Actor.tenant());
  }

  /** 保存业务成功变更的历史目标编号，供首页最近操作读取；不替代 AOP 请求日志。 */
  public void audit(String action, String resource, String id) {
    jdbc.update(
        "INSERT INTO sys_audit(id,tenant_id,user_id,action,resource,resource_id,trace_id)"
            + " VALUES(?,?,?,?,?,?,?)",
        IdWorker.getIdStr(),
        Actor.tenant(),
        Actor.user().id,
        action,
        resource,
        id,
        org.slf4j.MDC.get("traceId"));
  }

  public long references(String table, String column, String id) {
    if (!Set.of(
            "sys_user_role:role_id",
            "sys_user_post:post_id",
            "sys_role_dept:dept_id",
            "sys_role_menu:menu_id")
        .contains(table + ":" + column)) throw new IllegalArgumentException();
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND " + column + "=?",
        Long.class,
        Actor.tenant(),
        id);
  }
}
