package com.star.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.auth.dto.AuthInputs;
import com.star.common.BusinessException;
import com.star.system.entity.User;
import com.star.system.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

// 包职责：处理登录会话、密码、个人资料及账号申请审核业务。
// 模块联系：连接 system 用户数据、framework 权限上下文和 Redis 会话，由 auth.controller 调用。

/**
 * 处理密码校验、登录限流、访问令牌、刷新凭据轮换和会话有效性检查。
 *
 * <p>关联模块：AuthController 与 WebConfig 调用；Redis 保存会话及限流数据，LoginAudit 驱动登录日志记录。
 */
@Service
public class AuthService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long REFRESH_TTL = 7 * 24 * 3600L;
  private final com.star.system.service.SystemConfigService settings;
  private final CaptchaService captcha;
  private final UserMapper users;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final BCryptPasswordEncoder passwords;
  private final ObjectMapper json;
  private final boolean secure;
  private final Set<String> origins;
  private final String decoyHash;
  private final com.star.monitor.service.OnlineSessionRegistry online;

  public AuthService(
      UserMapper users,
      JdbcTemplate jdbc,
      StringRedisTemplate redis,
      BCryptPasswordEncoder passwords,
      ObjectMapper json,
      @Value("${star.cookie-secure}") boolean secure,
      @Value("${star.public-origins}") String origins,
      com.star.monitor.service.OnlineSessionRegistry online,
      com.star.system.service.SystemConfigService settings,
      CaptchaService captcha) {
    this.settings = settings;
    this.captcha = captcha;
    this.users = users;
    this.jdbc = jdbc;
    this.redis = redis;
    this.passwords = passwords;
    this.json = json;
    this.online = online;
    this.secure = secure;
    this.origins = Set.of(origins.split(","));
    decoyHash = passwords.encode(random());
  }

  private record Refresh(
      String userId, String tenantId, int authVersion, String family, long expiresAt) {}

  public static String random() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 统一校验密码字符组合和 UTF-8 字节长度，防止超过 BCrypt 有效输入范围。 */
  public static void passwordPolicy(String value) {
    if (value == null
        || value.length() < 12
        || value.getBytes(StandardCharsets.UTF_8).length > 72
        || !value.matches("(?s).*(?=.*[A-Za-z])(?=.*[0-9]).*"))
      throw BusinessException.bad("密码需 12–72 字节，包含字母和数字");
  }

  private void cookie(HttpServletResponse response, String name, String value, long ttl) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(ttl)
            .build()
            .toString());
    response.setHeader("Cache-Control", "no-store");
  }

  private String cookie(HttpServletRequest request, String name) {
    if (request.getCookies() != null)
      for (var c : request.getCookies()) if (name.equals(c.getName())) return c.getValue();
    return "";
  }

  /** 生成或复用页面校验令牌；随机 Cookie 对应的服务端状态保存在 Redis。 */
  public Map<String, String> csrf(HttpServletRequest req, HttpServletResponse res) {
    String nonce = cookie(req, "star_csrf"),
        token = nonce.isEmpty() ? null : redis.opsForValue().get("star:csrf:" + hash(nonce));
    if (token == null) {
      nonce = random();
      token = random();
      redis.opsForValue().set("star:csrf:" + hash(nonce), token, Duration.ofHours(2));
      cookie(res, "star_csrf", nonce, 7200);
    }
    res.setHeader("Cache-Control", "no-store");
    return Map.of("token", token);
  }

  /** 校验请求来源和页面令牌，用于登录、刷新及公开账号申请等接口。 */
  public void verifyCsrf(HttpServletRequest req) {
    String origin = req.getHeader("Origin");
    if (origin != null && !origins.contains(origin))
      throw new BusinessException(403, "CSRF_INVALID", "请求来源不被允许");
    String nonce = cookie(req, "star_csrf"), given = req.getHeader("X-CSRF-Token");
    String expected = nonce.isEmpty() ? null : redis.opsForValue().get("star:csrf:" + hash(nonce));
    if (expected == null
        || given == null
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8)))
      throw new BusinessException(403, "CSRF_INVALID", "页面验证已过期，请重试");
  }

  /** 按租户编码查找账号，先执行限流和 BCrypt 校验，再签发访问令牌与刷新凭据。 */
  @com.star.framework.annotation.LoginAudit
  public Object login(AuthInputs.Login body, HttpServletRequest req, HttpServletResponse res) {
    verifyCsrf(req);
    String key =
        "star:login-limit:"
            + hash(
                req.getRemoteAddr()
                    + ":"
                    + body.tenantCode()
                    + ":"
                    + body.username().toLowerCase(Locale.ROOT));
    Long attempts =
        redis.execute(
            new DefaultRedisScript<>(
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60)"
                    + " end; return n",
                Long.class),
            List.of(key));
    if (attempts != null && attempts > 10)
      throw new BusinessException(429, "RATE_LIMITED", "登录尝试过于频繁，请一分钟后重试");
    if (settings.forCode(body.tenantCode()).captchaEnabled())
      captcha.verify(body.tenantCode(), "login", body.captchaId(), body.captchaCode(), req);
    var tenants =
        jdbc.queryForList(
            "SELECT id FROM sys_tenant WHERE code=? AND status='启用'",
            String.class,
            body.tenantCode());
    User u =
        tenants.isEmpty()
            ? null
            : users.selectOne(
                new QueryWrapper<User>()
                    .eq("tenant_id", tenants.get(0))
                    .eq("username", body.username()));
    boolean valid = passwords.matches(body.password(), u == null ? decoyHash : u.passwordHash);
    if (!valid || u == null || !"启用".equals(u.status))
      throw new BusinessException(401, "INVALID_CREDENTIALS", "账号或密码错误");
    redis.delete(key);
    var family = random();
    var refresh =
        new Refresh(
            u.id,
            u.tenantId,
            u.authVersion,
            family,
            System.currentTimeMillis() / 1000 + REFRESH_TTL);
    issueRefresh(refresh, res);
    return issueAccess(u, family);
  }

  /** 建立 Sa-Token 会话，并保存租户、认证版本和刷新家族以供后续请求复核。 */
  private Object issueAccess(User u, String family) {
    StpUtil.login(u.id);
    var session = StpUtil.getTokenSession();
    session.set("tenantId", u.tenantId);
    session.set("authVersion", u.authVersion);
    session.set("family", family);
    online.touch(u, family);
    return Map.of(
        "accessToken",
        StpUtil.getTokenValue(),
        "tokenType",
        "Bearer",
        "expiresIn",
        900,
        "mustChangePassword",
        u.mustChangePassword);
  }

  /** 仅把刷新凭据摘要存入 Redis，原始凭据通过 HttpOnly Cookie 返回浏览器。 */
  private void issueRefresh(Refresh r, HttpServletResponse res) {
    try {
      long ttl = Math.max(1, r.expiresAt - System.currentTimeMillis() / 1000);
      String raw = random();
      redis
          .opsForValue()
          .set(
              "star:refresh:" + hash(raw),
              json.writeValueAsString(r),
              Duration.ofSeconds(Math.min(ttl, 86400)));
      cookie(res, "star_refresh", raw, ttl);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 原子消费一次性刷新凭据；检测重放、账号状态及认证版本后再轮换令牌。 */
  public Object refresh(HttpServletRequest req, HttpServletResponse res) {
    verifyCsrf(req);
    String raw = cookie(req, "star_refresh");
    if (raw.isEmpty()) throw expired();
    String digest = hash(raw);
    // 原子消费刷新凭据并保留重放标记；发现重放时撤销整个刷新家族。
    String value =
        redis.execute(
            new DefaultRedisScript<>(
                """
local v=redis.call('GET',KEYS[1])
if v then redis.call('DEL',KEYS[1]); redis.call('SET',KEYS[2],v,'EX',604800); return 'OK:'..v end
local used=redis.call('GET',KEYS[2]); if used then return 'REPLAY:'..used end
return ''
""",
                String.class),
            List.of("star:refresh:" + digest, "star:refresh-used:" + digest));
    try {
      if (value == null || value.isEmpty()) throw expired();
      boolean replay = value.startsWith("REPLAY:");
      Refresh r = json.readValue(value.substring(replay ? 7 : 3), Refresh.class);
      if (replay) {
        redis.opsForValue().set("star:revoked:" + r.family, "1", Duration.ofDays(7));
        throw expired();
      }
      User u = users.selectById(r.userId);
      if (u == null
          || !u.tenantId.equals(r.tenantId)
          || !"启用".equals(u.status)
          || u.authVersion != r.authVersion
          || r.expiresAt <= System.currentTimeMillis() / 1000
          || !tenantEnabled(u.tenantId)
          || Boolean.TRUE.equals(redis.hasKey("star:revoked:" + r.family))) throw expired();
      issueRefresh(r, res);
      return issueAccess(u, r.family);
    } catch (JsonProcessingException e) {
      throw expired();
    }
  }

  /** 撤销当前会话及刷新家族，并清除客户端刷新 Cookie。 */
  public void logout(HttpServletRequest req, HttpServletResponse res) {
    verifyCsrf(req);
    if (StpUtil.isLogin()) {
      var family = StpUtil.getTokenSession().getString("family");
      online.remove(StpUtil.getTokenSession().getString("tenantId"), family);
      if (family != null)
        redis.opsForValue().set("star:revoked:" + family, "1", Duration.ofDays(7));
      StpUtil.logout();
    }
    String raw = cookie(req, "star_refresh");
    if (!raw.isEmpty()) {
      String v = redis.opsForValue().getAndDelete("star:refresh:" + hash(raw));
      if (v != null)
        try {
          Refresh r = json.readValue(v, Refresh.class);
          online.remove(r.tenantId, r.family);
          redis.opsForValue().set("star:revoked:" + r.family, "1", Duration.ofDays(7));
        } catch (JsonProcessingException e) {
          throw expired();
        }
    }
    cookie(res, "star_refresh", "", 0);
  }

  private BusinessException expired() {
    return new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
  }

  public boolean tenantEnabled(String tenant) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_tenant WHERE id=? AND status='启用'", Long.class, tenant)
        > 0;
  }

  /** 重新读取用户及会话状态，阻止已删除、停用、改密或已撤销的账号继续访问。 */
  public User authenticate() {
    StpUtil.checkLogin();
    User user = users.selectById(StpUtil.getLoginIdAsString());
    var session = StpUtil.getTokenSession();
    if (user == null
        || !"启用".equals(user.status)
        || !Objects.equals(user.tenantId, session.getString("tenantId"))
        || !Objects.equals(user.authVersion, session.get("authVersion"))
        || !tenantEnabled(user.tenantId)
        || Boolean.TRUE.equals(redis.hasKey("star:revoked:" + session.getString("family"))))
      throw expired();
    online.touch(user, session.getString("family"));
    return user;
  }
}
