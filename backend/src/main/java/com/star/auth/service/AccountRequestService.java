package com.star.auth.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.auth.dto.AuthInputs;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.system.dto.Inputs;
import com.star.system.entity.User;
import com.star.system.mapper.UserMapper;
import com.star.system.service.Relations;
import com.star.system.service.SystemService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：处理登录会话、密码、个人资料及账号申请审核业务。
// 模块联系：连接 system 用户数据、framework 权限上下文和 Redis 会话，由 auth.controller 调用。

/**
 * 保存待审核申请，核验处理权限，并在审核通过后创建用户或重置密码。
 *
 * <p>关联模块：复用 AuthService 的验证和 SystemService 的用户规则；对应 sys_account_request 与管理员审核组件。
 */
@Service
public class AccountRequestService {
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final BCryptPasswordEncoder encoder;
  private final UserMapper users;
  private final PermissionService permissions;
  private final Relations relations;
  private final SystemService system;
  private final AuthService auth;
  private final com.star.system.service.SystemConfigService settings;
  private final CaptchaService captcha;

  public AccountRequestService(
      JdbcTemplate jdbc,
      StringRedisTemplate redis,
      BCryptPasswordEncoder encoder,
      UserMapper users,
      PermissionService permissions,
      Relations relations,
      SystemService system,
      AuthService auth,
      com.star.system.service.SystemConfigService settings,
      CaptchaService captcha) {
    this.settings = settings;
    this.captcha = captcha;
    this.jdbc = jdbc;
    this.redis = redis;
    this.encoder = encoder;
    this.users = users;
    this.permissions = permissions;
    this.relations = relations;
    this.system = system;
    this.auth = auth;
  }

  private void limit(HttpServletRequest request) {
    Long attempts =
        redis.execute(
            new DefaultRedisScript<>(
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60)"
                    + " end; return n",
                Long.class),
            List.of("star:account-request-limit:" + AuthService.hash(request.getRemoteAddr())));
    if (attempts == null || attempts > 5)
      throw new BusinessException(429, "RATE_LIMITED", "申请过于频繁，请一分钟后重试");
  }

  private String tenant(String code) {
    var found =
        jdbc.queryForList(
            "SELECT id FROM sys_tenant WHERE code=? AND status='启用' FOR UPDATE",
            String.class,
            code.trim());
    return found.isEmpty() ? null : found.get(0);
  }

  private boolean pending(String tenant, String kind, String username) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_account_request WHERE tenant_id=? AND kind=? AND username=?"
                + " AND status='pending'",
            Long.class,
            tenant,
            kind,
            username)
        > 0;
  }

  /** 验证页面令牌、限流与账号冲突后保存待审申请；此时尚不创建可登录用户。 */
  @Transactional
  public Map<String, String> register(AuthInputs.Register body, HttpServletRequest req) {
    auth.verifyCsrf(req);
    limit(req);
    var config = settings.forCode(body.tenantCode());
    if (!config.registrationEnabled()) throw BusinessException.bad("当前租户已关闭用户注册，请联系管理员");
    if (config.registrationCaptchaEnabled())
      captcha.verify(body.tenantCode(), "register", body.captchaId(), body.captchaCode(), req);
    AuthService.passwordPolicy(body.password());
    String hash = encoder.encode(body.password()), tenant = tenant(body.tenantCode());
    if (tenant == null) throw BusinessException.bad("租户不存在或已停用，请核对租户编码");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND username=?",
            Long.class,
            tenant,
            body.username())
        > 0) throw BusinessException.conflict("该账号已存在，请直接登录或更换账号");
    var previous =
        jdbc.queryForList(
            "SELECT CAST(id AS CHAR) FROM sys_account_request WHERE tenant_id=? AND kind='register'"
                + " AND username=? AND status='pending'",
            String.class,
            tenant,
            body.username());
    if (!previous.isEmpty())
      return Map.of(
          "requestId",
          previous.get(0),
          "status",
          "pending",
          "message",
          "该账号已有待审核的注册申请，无需重复提交。请联系企业管理员审核原申请");
    String requestId = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_account_request(id,tenant_id,kind,username,name,email,phone,password_hash)"
            + " VALUES(?,?,'register',?,?,?,?,?)",
        requestId,
        tenant,
        body.username(),
        body.name().trim(),
        body.email().trim(),
        body.phone(),
        hash);
    return Map.of(
        "requestId", requestId, "status", "pending", "message", "申请已受理，请联系企业管理员在用户管理的账号申请中审核");
  }

  /** 匹配账号及联系方式后保存找回申请，对外始终给出统一提示以避免泄露账号存在性。 */
  @Transactional
  public void forgot(AuthInputs.ForgotPassword body, HttpServletRequest req) {
    auth.verifyCsrf(req);
    limit(req);
    String tenant = tenant(body.tenantCode());
    if (tenant == null || pending(tenant, "reset", body.username().trim())) return;
    var found =
        jdbc.queryForList(
            "SELECT id,name FROM sys_user WHERE tenant_id=? AND username=? AND email=? AND phone=?",
            tenant,
            body.username().trim(),
            body.email().trim(),
            body.phone());
    if (found.isEmpty()) return;
    jdbc.update(
        "INSERT INTO sys_account_request(id,tenant_id,kind,username,name,email,phone,user_id)"
            + " VALUES(?,?,'reset',?,?,?,?,?)",
        IdWorker.getIdStr(),
        tenant,
        body.username().trim(),
        found.get(0).get("name"),
        body.email().trim(),
        body.phone(),
        found.get(0).get("id"));
  }

  public Object list(int page, int size, String status) {
    permissions.requireAdmin();
    permissions.require("system:user:list");
    int safeSize = Math.max(1, Math.min(size, 100)), safePage = Math.max(1, Math.min(page, 100000));
    if (!Set.of("pending", "approved", "rejected", "").contains(status))
      throw BusinessException.bad("申请状态不合法");
    String where = " WHERE tenant_id=?" + (status.isEmpty() ? "" : " AND status=?");
    var params = new ArrayList<Object>();
    params.add(Actor.tenant());
    if (!status.isEmpty()) params.add(status);
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_account_request" + where, Long.class, params.toArray());
    params.add(safeSize);
    params.add((safePage - 1) * safeSize);
    var rows =
        jdbc.queryForList(
            "SELECT CAST(id AS CHAR) AS id,kind,username,name,email,phone,status,note,CAST(user_id"
                + " AS CHAR) AS userId,created_at AS createdAt,handled_at AS handledAt FROM"
                + " sys_account_request"
                + where
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            params.toArray());
    return Map.of("records", rows, "total", total);
  }

  /** 锁定租户并检查管理员权限，按审核结果创建用户、复用重置密码流程或拒绝申请。 */
  @Transactional
  public void review(String id, AuthInputs.ReviewRequest body) {
    relations.lockTenant();
    Actor.set(auth.authenticate());
    permissions.requireAdmin();
    permissions.require("system:user:list");
    if (body.note() == null || body.note().isBlank()) throw BusinessException.bad("请填写身份核实与处理说明");
    var found =
        jdbc.queryForList(
            "SELECT * FROM sys_account_request WHERE tenant_id=? AND id=? FOR UPDATE",
            Actor.tenant(),
            id);
    if (found.isEmpty()) throw BusinessException.missing();
    var row = found.get(0);
    if (!"pending".equals(row.get("status"))) throw BusinessException.conflict("该申请已处理，请刷新列表");
    String userId = null;
    if ("approve".equals(body.decision())) {
      if ("register".equals(row.get("kind"))) {
        permissions.require("system:user:create");
        permissions.require("system:user:assign-role");
        if (body.deptId() == null || body.roleIds() == null || body.roleIds().isEmpty())
          throw BusinessException.bad("请选择部门和角色");
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_dept WHERE tenant_id=? AND id=? AND status='启用'",
                Long.class,
                Actor.tenant(),
                body.deptId())
            != 1) throw BusinessException.bad("部门不存在或已停用");
        User u = new User();
        u.id = IdWorker.getIdStr();
        u.tenantId = Actor.tenant();
        u.name = String.valueOf(row.get("name"));
        u.username = String.valueOf(row.get("username"));
        u.passwordHash = String.valueOf(row.get("password_hash"));
        u.deptId = body.deptId();
        u.email = String.valueOf(row.get("email"));
        u.phone = String.valueOf(row.get("phone"));
        u.status = "启用";
        u.version = 0;
        u.authVersion = 0;
        u.mustChangePassword = false;
        u.builtin = false;
        u.bio = "";
        u.remark = "管理员审核注册";
        u.createdAt = u.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        users.insert(u);
        system.assignRoles(u.id, new Inputs.Roles(body.roleIds(), 0));
        userId = u.id;
      } else {
        userId = String.valueOf(row.get("user_id"));
        User user = users.selectById(userId);
        if (user == null || !Actor.tenant().equals(user.tenantId))
          throw BusinessException.missing();
        if (Boolean.TRUE.equals(user.builtin))
          throw BusinessException.bad("内置管理员请通过个人中心修改密码；无法登录时请联系系统维护人员");
        // 复用已有重置流程，统一检查数据范围、密码策略并撤销旧会话。
        system.resetPassword(userId, new Inputs.PasswordReset(body.newPassword(), user.version));
      }
    }
    jdbc.update(
        "UPDATE sys_account_request SET"
            + " status=?,password_hash=NULL,note=?,user_id=COALESCE(?,user_id),handled_by=?,handled_at=UTC_TIMESTAMP(6)"
            + " WHERE tenant_id=? AND id=?",
        "approve".equals(body.decision()) ? "approved" : "rejected",
        body.note() == null ? "" : body.note().trim(),
        userId,
        Actor.user().id,
        Actor.tenant(),
        id);
    relations.audit(
        "approve".equals(body.decision()) ? "approve-request" : "reject-request",
        "account-requests",
        id);
  }
}
