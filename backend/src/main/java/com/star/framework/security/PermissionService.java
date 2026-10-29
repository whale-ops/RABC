package com.star.framework.security;

import cn.dev33.satoken.stp.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.star.common.BusinessException;
import com.star.system.entity.Dept;
import com.star.system.entity.User;
import com.star.system.mapper.DeptMapper;
import com.star.system.mapper.UserMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 包职责：提供当前登录身份、RBAC 权限及用户数据范围计算。
// 模块联系：从 auth 获取认证身份，读取 system 用户、角色和菜单数据，供各业务服务校验权限。

/**
 * 为 Sa-Token 提供角色和权限，并按当前操作合并用户数据范围。
 *
 * <p>关联模块：读取用户、角色、菜单及关联表；SystemService 与 AuditLogService 依赖其进行权限校验。
 */
@Component
public class PermissionService implements StpInterface {
  private final JdbcTemplate jdbc;
  private final UserMapper users;
  private final DeptMapper depts;

  public PermissionService(JdbcTemplate jdbc, UserMapper users, DeptMapper depts) {
    this.jdbc = jdbc;
    this.users = users;
    this.depts = depts;
  }

  /** 同时核验用户内置标识和启用的内置管理员角色，不按角色名称识别管理员。 */
  public boolean admin(User user) {
    return Boolean.TRUE.equals(user.builtin)
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id"
                    + " AND r.id=ur.role_id WHERE ur.tenant_id=? AND ur.user_id=? AND r.builtin=1"
                    + " AND r.status='启用'",
                Long.class,
                user.tenantId,
                user.id)
            > 0;
  }

  public void requireAdmin() {
    if (!admin(Actor.user()))
      throw new BusinessException(403, "ADMIN_REQUIRED", "授权配置和菜单维护仅限租户管理员");
  }

  /** 从当前启用的角色与菜单关系读取权限；停用或删除后不依赖缓存延迟生效。 */
  @Override
  public List<String> getPermissionList(Object loginId, String loginType) {
    User user = users.selectById(String.valueOf(loginId));
    if (user == null || !"启用".equals(user.status)) return List.of();
    if (admin(user))
      return jdbc.queryForList(
          "SELECT DISTINCT permission FROM sys_menu WHERE tenant_id=? AND status='启用' AND"
              + " permission<>''",
          String.class,
          user.tenantId);
    return jdbc.queryForList(
        """
        SELECT DISTINCT m.permission FROM sys_user_role ur
        JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='启用'
        JOIN sys_role_menu rm ON rm.tenant_id=r.tenant_id AND rm.role_id=r.id
        JOIN sys_menu m ON m.tenant_id=rm.tenant_id AND m.id=rm.menu_id AND m.status='启用'
        WHERE ur.tenant_id=? AND ur.user_id=? AND m.permission<>''""",
        String.class,
        user.tenantId,
        user.id);
  }

  @Override
  public List<String> getRoleList(Object loginId, String loginType) {
    User user = users.selectById(String.valueOf(loginId));
    if (user == null) return List.of();
    return jdbc.queryForList(
        "SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.tenant_id=r.tenant_id AND"
            + " ur.role_id=r.id WHERE ur.tenant_id=? AND ur.user_id=? AND r.status='启用'",
        String.class,
        user.tenantId,
        user.id);
  }

  public void require(String permission) {
    StpUtil.checkPermission(permission);
  }

  public record Scope(boolean all, Set<String> depts, boolean self) {}

  /** 只合并持有当前操作权限的角色数据范围，避免无关角色扩大数据访问范围。 */
  public Scope scope(String permission) {
    var user = Actor.user();
    if (admin(user)) return new Scope(true, Set.of(), false);
    var roles =
        jdbc.queryForList(
            """
            SELECT DISTINCT r.id,r.scope FROM sys_user_role ur
            JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='启用'
            JOIN sys_role_menu rm ON rm.tenant_id=r.tenant_id AND rm.role_id=r.id
            JOIN sys_menu m ON m.tenant_id=rm.tenant_id AND m.id=rm.menu_id AND m.status='启用'
            WHERE ur.tenant_id=? AND ur.user_id=? AND m.permission=?""",
            user.tenantId,
            user.id,
            permission);
    Set<String> allowed = new HashSet<>();
    boolean self = false;
    for (var role : roles) {
      String kind = String.valueOf(role.get("scope"));
      switch (kind) {
        case "全部数据":
          return new Scope(true, Set.of(), true);
        case "本部门":
          if (user.deptId != null) allowed.add(user.deptId);
          break;
        case "本部门及下级":
          if (user.deptId != null) allowed.addAll(descendants(user.deptId));
          break;
        case "自定义部门":
          allowed.addAll(
              jdbc.queryForList(
                  "SELECT dept_id FROM sys_role_dept WHERE tenant_id=? AND role_id=?",
                  String.class,
                  user.tenantId,
                  role.get("id")));
          break;
        case "仅本人":
          self = true;
          break;
        default:
          break;
      }
    }
    return new Scope(false, allowed, self);
  }

  /** 遍历当前租户部门树，计算指定部门及下级部门集合。 */
  public Set<String> descendants(String root) {
    var tree = depts.selectList(new QueryWrapper<Dept>().eq("tenant_id", Actor.tenant()));
    Set<String> result = new HashSet<>(Set.of(root));
    boolean more;
    do {
      more = false;
      for (var dept : tree)
        if (dept.parentId != null && result.contains(dept.parentId)) more |= result.add(dept.id);
    } while (more);
    return result;
  }

  /** 将租户、本人和部门范围转换为用户查询条件，供系统管理统一使用。 */
  public QueryWrapper<User> userQuery(String permission) {
    var q = new QueryWrapper<User>().eq("tenant_id", Actor.tenant());
    var s = scope(permission);
    if (!s.all)
      q.and(
          w -> {
            w.eq("id", "0");
            if (!s.depts.isEmpty()) w.or().in("dept_id", s.depts);
            if (s.self) w.or().eq("id", Actor.user().id);
          });
    return q;
  }

  public void checkDepartment(String deptId, String permission) {
    var s = scope(permission);
    if (!s.all && (deptId == null || !s.depts.contains(deptId))) throw BusinessException.denied();
  }
}
