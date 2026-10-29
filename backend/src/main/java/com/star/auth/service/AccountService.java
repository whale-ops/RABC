package com.star.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.star.auth.dto.AuthInputs;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.system.entity.User;
import com.star.system.mapper.UserMapper;
import com.star.system.service.Relations;
import com.star.system.service.SystemService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：处理登录会话、密码、个人资料及账号申请审核业务。
// 模块联系：连接 system 用户数据、framework 权限上下文和 Redis 会话，由 auth.controller 调用。

/**
 * 读取和修改当前登录用户的个人资料，校验旧密码并更新新密码。
 *
 * <p>关联模块：由 AuthController 调用；使用 AuthService、PermissionService 和 Relations，对接前端个人中心。
 */
@Service
public class AccountService {
  private final UserMapper users;
  private final PermissionService permissions;
  private final SystemService system;
  private final Relations relations;
  private final BCryptPasswordEncoder encoder;
  private final JdbcTemplate jdbc;
  private final AuthService auth;

  public AccountService(
      UserMapper users,
      PermissionService permissions,
      SystemService system,
      Relations relations,
      BCryptPasswordEncoder encoder,
      JdbcTemplate jdbc,
      AuthService auth) {
    this.users = users;
    this.permissions = permissions;
    this.system = system;
    this.relations = relations;
    this.encoder = encoder;
    this.jdbc = jdbc;
    this.auth = auth;
  }

  /** 返回当前账号的展示资料、实际权限及租户信息，供前端恢复会话和导航。 */
  public Object me() {
    var u = Actor.user();
    var row = new LinkedHashMap<>(system.view("users", u));
    row.put("bio", u.bio);
    row.put("permissions", permissions.getPermissionList(u.id, "login"));
    row.put("roleCodes", permissions.getRoleList(u.id, "login"));
    row.put("admin", permissions.admin(u));
    row.put("mustChangePassword", u.mustChangePassword);
    row.put("tenant", jdbc.queryForMap("SELECT code,name FROM sys_tenant WHERE id=?", u.tenantId));
    return row;
  }

  /** 锁定租户后更新当前账号资料，并同步版本和原有业务审计。 */
  @Transactional
  public void profile(AuthInputs.Profile input) {
    relations.lockTenant();
    Actor.set(auth.authenticate());
    var u =
        users.selectOne(
            new QueryWrapper<User>().eq("tenant_id", Actor.tenant()).eq("id", Actor.user().id));
    u.name = input.name().trim();
    u.email = input.email();
    u.phone = input.phone();
    u.bio = input.bio();
    u.version++;
    users.update(u, new QueryWrapper<User>().eq("tenant_id", Actor.tenant()).eq("id", u.id));
    relations.audit("profile", "users", u.id);
  }

  /** 校验旧密码并保存新 BCrypt 摘要，递增认证版本以撤销所有旧登录状态。 */
  @Transactional
  public void password(AuthInputs.Password input) {
    relations.lockTenant();
    Actor.set(auth.authenticate());
    var u =
        users.selectOne(
            new QueryWrapper<User>().eq("tenant_id", Actor.tenant()).eq("id", Actor.user().id));
    if (!encoder.matches(input.oldPassword(), u.passwordHash))
      throw BusinessException.bad("原密码不正确");
    AuthService.passwordPolicy(input.newPassword());
    if (encoder.matches(input.newPassword(), u.passwordHash))
      throw BusinessException.bad("新密码不能与原密码相同");
    u.passwordHash = encoder.encode(input.newPassword());
    u.mustChangePassword = false;
    u.authVersion++;
    u.version++;
    users.update(u, new QueryWrapper<User>().eq("tenant_id", Actor.tenant()).eq("id", u.id));
    relations.audit("password", "users", u.id);
  }
}
