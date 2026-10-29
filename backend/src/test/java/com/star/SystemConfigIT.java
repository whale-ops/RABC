package com.star;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;

/**
 * 使用真实 MySQL、Redis 和 MockMvc 验证系统配置、公开开关、验证码和注册服务端约束。
 *
 * <p>关联模块：覆盖认证和系统管理模块；每项测试使用独立临时租户，并在结束后清理其数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SystemConfigIT {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired BCryptPasswordEncoder encoder;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  String tenant, code, dept, admin, normalRole, builtinRole, csrf, remote, adminToken;
  Cookie cookie;
  final List<String> fixtureTenants = new ArrayList<>();
  final String password = "RequestTestPass2026";

  @BeforeEach
  void setup() throws Exception {
    tenant = IdWorker.getIdStr();
    code = "request_test_" + tenant;
    remote = code;
    dept = IdWorker.getIdStr();
    admin = IdWorker.getIdStr();
    normalRole = IdWorker.getIdStr();
    builtinRole = IdWorker.getIdStr();
    jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'账号流程测试')", tenant, code);
    fixtureTenants.add(tenant);
    jdbc.update("INSERT INTO sys_dept(id,tenant_id,name) VALUES(?,?,'测试部门')", dept, tenant);
    jdbc.update(
        "INSERT INTO sys_role(id,tenant_id,name,code,scope,builtin)"
            + " VALUES(?,?,'管理员','admin','全部数据',1),(?,?,'普通成员','member','仅本人',0)",
        builtinRole,
        tenant,
        normalRole,
        tenant);
    for (String permission :
        List.of(
            "dashboard:read",
            "system:user:list",
            "system:config:read",
            "system:config:update",
            "system:role:list",
            "system:role:create",
            "system:role:delete",
            "system:user:create",
            "system:user:delete",
            "system:user:export",
            "system:user:assign-role",
            "system:user:reset-password")) {
      String menu = IdWorker.getIdStr();
      jdbc.update(
          "INSERT INTO sys_menu(id,tenant_id,name,type,path,permission) VALUES(?, ?, ?, '菜单',"
              + " '/dashboard', ?)",
          menu,
          tenant,
          permission,
          permission);
      if (permission.equals("dashboard:read"))
        jdbc.update(
            "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) VALUES(?,?,?)",
            tenant,
            normalRole,
            menu);
    }
    jdbc.update(
        "INSERT INTO"
            + " sys_user(id,tenant_id,name,username,password_hash,dept_id,builtin,must_change_password)"
            + " VALUES(?,?,'测试管理员','admin',?,?,1,1)",
        admin,
        tenant,
        encoder.encode(password),
        dept);
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
        tenant,
        admin,
        builtinRole);
    var result = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
    csrf = json.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
    String header = result.getResponse().getHeader("Set-Cookie");
    cookie =
        new Cookie("star_csrf", header.substring(header.indexOf('=') + 1, header.indexOf(';')));
    adminToken = login("admin", password);
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      if (adminToken != null) postJson("/auth/logout", Map.of(), adminToken);
      if (remote != null) redis.delete("star:account-request-limit:" + AuthService.hash(remote));
      if (cookie != null) redis.delete("star:csrf:" + AuthService.hash(cookie.getValue()));
    } finally {
      for (String fixture : fixtureTenants) {
        for (String table :
            List.of(
                "sys_system_config",
                "sys_user_post",
                "sys_user_role",
                "sys_role_menu",
                "sys_role_dept",
                "sys_account_request",
                "sys_audit",
                "sys_login_log",
                "sys_operation_log",
                "sys_user",
                "sys_post",
                "sys_dept",
                "sys_role",
                "sys_menu")) jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", fixture);
        jdbc.update("DELETE FROM sys_tenant WHERE id=?", fixture);
      }
    }
  }

  MvcResult postJson(String path, Object data, String token) throws Exception {
    var request =
        post("/api/v1" + path)
            .contentType("application/json")
            .content(json.writeValueAsBytes(data))
            .cookie(cookie)
            .header("X-CSRF-Token", csrf)
            .header("Origin", "http://127.0.0.1:5173")
            .with(
                r -> {
                  r.setRemoteAddr(remote);
                  return r;
                });
    if (token != null) request.header("Authorization", "Bearer " + token);
    return mvc.perform(request).andReturn();
  }

  JsonNode data(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString()).get("data");
  }

  String login(String name, String pwd) throws Exception {
    var r =
        postJson(
            "/auth/login", Map.of("tenantCode", code, "username", name, "password", pwd), null);
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return data(r).get("accessToken").asText();
  }

  Map<String, String> registration(String name) {
    return Map.of(
        "tenantCode",
        code,
        "username",
        name,
        "name",
        "测试成员",
        "email",
        "member@example.test",
        "phone",
        "13800138000",
        "password",
        password);
  }

  String requestId(String name) {
    return jdbc.queryForObject(
        "SELECT CAST(id AS CHAR) FROM sys_account_request WHERE tenant_id=? AND username=? AND"
            + " status='pending'",
        String.class,
        tenant,
        name);
  }

  int getStatus(String path, String token) throws Exception {
    return mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Autowired com.star.auth.service.CaptchaService captchas;

  MvcResult save(String group, Object body) throws Exception {
    return mvc.perform(
            put("/api/v1/system/config/" + group)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(json.writeValueAsBytes(body)))
        .andReturn();
  }

  JsonNode config() throws Exception {
    var result =
        mvc.perform(get("/api/v1/system/config").header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, result.getResponse().getStatus());
    return data(result);
  }

  Map<String, Object> loginSettings(long version, boolean enabled) {
    return Map.of(
        "version",
        version,
        "captchaEnabled",
        enabled,
        "captchaLength",
        5,
        "captchaExpireSeconds",
        90);
  }

  Map<String, Object> loginBody(String id, String answer) {
    return Map.of(
        "tenantCode",
        code,
        "username",
        "admin",
        "password",
        password,
        "captchaId",
        id,
        "captchaCode",
        answer);
  }

  String seed(String tenantCode, String purpose, String answer) {
    String id = AuthService.random();
    var req = new org.springframework.mock.web.MockHttpServletRequest();
    req.setCookies(cookie);
    redis
        .opsForValue()
        .set(
            captchas.key(tenantCode, purpose, id, req),
            AuthService.hash(answer),
            java.time.Duration.ofSeconds(30));
    return id;
  }

  @Test
  void defaultsAreCompatibleAndReadsDoNotCreateRows() throws Exception {
    var c = config();
    assertEquals("StarAdmin", c.get("systemName").asText());
    assertFalse(c.get("captchaEnabled").asBoolean());
    assertTrue(c.get("registrationEnabled").asBoolean());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_system_config WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(
        401,
        postJson(
                "/auth/login",
                Map.of("tenantCode", code, "username", "admin", "password", "wrong"),
                null)
            .getResponse()
            .getStatus());
    var pub = mvc.perform(get("/api/v1/auth/public-config").param("tenantCode", code)).andReturn();
    assertEquals(200, pub.getResponse().getStatus());
    var fields = new HashSet<String>();
    data(pub).fieldNames().forEachRemaining(fields::add);
    assertEquals(
        Set.of(
            "systemName",
            "systemLogo",
            "systemDescription",
            "captchaEnabled",
            "captchaLength",
            "captchaExpireSeconds",
            "registrationEnabled",
            "registrationCaptchaEnabled"),
        fields);
    assertEquals(
        401, mvc.perform(get("/api/v1/system/config")).andReturn().getResponse().getStatus());
  }

  @Test
  void tabSavesArePersistentVersionedAndTenantScoped() throws Exception {
    var basic =
        Map.of(
            "version",
            0,
            "systemName",
            "测试工作台",
            "systemLogo",
            "/favicon.svg",
            "systemDescription",
            "企业系统描述");
    assertEquals(200, save("basic", basic).getResponse().getStatus());
    assertEquals(409, save("basic", basic).getResponse().getStatus());
    assertEquals(200, save("login", loginSettings(1, true)).getResponse().getStatus());
    var c = config();
    assertEquals("测试工作台", c.get("systemName").asText());
    assertTrue(c.get("captchaEnabled").asBoolean());
    assertEquals(2, c.get("version").asInt());
    String otherTenant = IdWorker.getIdStr(), otherCode = "config_other_" + otherTenant;
    fixtureTenants.add(otherTenant);
    jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'另一租户')", otherTenant, otherCode);
    jdbc.update(
        "INSERT INTO sys_system_config(tenant_id,system_name,registration_enabled)"
            + " VALUES(?,'另一品牌',0)",
        otherTenant);
    var otherPublic =
        mvc.perform(get("/api/v1/auth/public-config").param("tenantCode", otherCode)).andReturn();
    assertEquals("另一品牌", data(otherPublic).get("systemName").asText());
    assertFalse(data(otherPublic).get("registrationEnabled").asBoolean());
    assertEquals("测试工作台", config().get("systemName").asText());
    var unknown =
        mvc.perform(get("/api/v1/auth/public-config").param("tenantCode", "other_" + code))
            .andReturn();
    assertEquals("StarAdmin", data(unknown).get("systemName").asText());
    assertFalse(data(unknown).get("captchaEnabled").asBoolean());
  }

  @Test
  void logoAndSecurityRangesAreValidated() throws Exception {
    var img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var out = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(img, "png", out);
    String logo = "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    assertEquals(
        200,
        save(
                "basic",
                Map.of(
                    "version",
                    0,
                    "systemName",
                    "品牌",
                    "systemLogo",
                    logo,
                    "systemDescription",
                    "描述"))
            .getResponse()
            .getStatus());
    assertTrue(config().get("systemLogo").asText().startsWith("data:image/png;base64,"));
    assertEquals(
        400,
        save(
                "basic",
                Map.of(
                    "version",
                    1,
                    "systemName",
                    "品牌",
                    "systemLogo",
                    "data:image/svg+xml;base64,AAAA",
                    "systemDescription",
                    "描述"))
            .getResponse()
            .getStatus());
    assertEquals(
        400,
        save(
                "login",
                Map.of(
                    "version",
                    1,
                    "captchaEnabled",
                    true,
                    "captchaLength",
                    3,
                    "captchaExpireSeconds",
                    10))
            .getResponse()
            .getStatus());
  }

  @Test
  void registrationToggleCannotBeBypassedByDirectRequest() throws Exception {
    assertEquals(
        200,
        save(
                "registration",
                Map.of(
                    "version", 0, "registrationEnabled", false, "registrationCaptchaEnabled", true))
            .getResponse()
            .getStatus());
    assertEquals(
        400, postJson("/auth/register", registration("new_user"), null).getResponse().getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_account_request WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(
        200,
        save(
                "registration",
                Map.of(
                    "version", 1, "registrationEnabled", true, "registrationCaptchaEnabled", true))
            .getResponse()
            .getStatus());
    assertEquals(
        400, postJson("/auth/register", registration("new_user"), null).getResponse().getStatus());
    var body = new HashMap<String, Object>(registration("new_user"));
    body.put("captchaId", seed(code, "register", "AB234"));
    body.put("captchaCode", "ab234");
    assertEquals(202, postJson("/auth/register", body, null).getResponse().getStatus());
  }

  @Test
  void captchaIssuanceRequiresCsrfAndReturnsOnlyImageChallenge() throws Exception {
    assertEquals(200, save("login", loginSettings(0, true)).getResponse().getStatus());
    assertEquals(
        403,
        mvc.perform(
                post("/api/v1/auth/captcha")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(Map.of("tenantCode", code, "purpose", "login"))))
            .andReturn()
            .getResponse()
            .getStatus());
    var result = postJson("/auth/captcha", Map.of("tenantCode", code, "purpose", "login"), null);
    assertEquals(200, result.getResponse().getStatus());
    var d = data(result);
    assertTrue(d.get("enabled").asBoolean());
    assertEquals(90, d.get("expiresIn").asInt());
    assertTrue(d.get("image").asText().startsWith("data:image/png;base64,"));
    assertFalse(d.has("answer"));
    var req = new org.springframework.mock.web.MockHttpServletRequest();
    req.setCookies(cookie);
    redis.delete(captchas.key(code, "login", d.get("captchaId").asText(), req));
    redis.delete("star:captcha-limit:" + AuthService.hash(remote));
  }

  @Test
  void loginCaptchaRejectsMissingWrongExpiredReplayAndOtherBindings() throws Exception {
    assertEquals(200, save("login", loginSettings(0, true)).getResponse().getStatus());
    assertEquals(400, postJson("/auth/login", loginBody("", ""), null).getResponse().getStatus());
    String id = seed(code, "login", "AB234");
    assertEquals(
        400, postJson("/auth/login", loginBody(id, "XXXXX"), null).getResponse().getStatus());
    assertEquals(
        400, postJson("/auth/login", loginBody(id, "AB234"), null).getResponse().getStatus());
    assertEquals(
        400,
        postJson("/auth/login", loginBody(AuthService.random(), "AB234"), null)
            .getResponse()
            .getStatus());
    String expired = seed(code, "login", "AB234");
    var expiredReq = new org.springframework.mock.web.MockHttpServletRequest();
    expiredReq.setCookies(cookie);
    redis.expire(captchas.key(code, "login", expired, expiredReq), java.time.Duration.ofMillis(1));
    Thread.sleep(20);
    assertEquals(
        400, postJson("/auth/login", loginBody(expired, "AB234"), null).getResponse().getStatus());
    String register = seed(code, "register", "AB234");
    assertEquals(
        400, postJson("/auth/login", loginBody(register, "AB234"), null).getResponse().getStatus());
    String other = seed("other_" + code, "login", "AB234");
    assertEquals(
        400, postJson("/auth/login", loginBody(other, "AB234"), null).getResponse().getStatus());
    String valid = seed(code, "login", "AB234");
    var ok = postJson("/auth/login", loginBody(valid, "ab234"), null);
    assertEquals(200, ok.getResponse().getStatus());
    adminToken = data(ok).get("accessToken").asText();
    assertEquals(
        400, postJson("/auth/login", loginBody(valid, "AB234"), null).getResponse().getStatus());
    var req = new org.springframework.mock.web.MockHttpServletRequest();
    req.setCookies(cookie);
    redis.delete(captchas.key(code, "register", register, req));
    redis.delete(captchas.key("other_" + code, "login", other, req));
    // 页面绑定不同 Cookie 时不能消费本页面的验证码。
    String bound = seed(code, "login", "AB234");
    var otherReq = new org.springframework.mock.web.MockHttpServletRequest();
    otherReq.setCookies(new Cookie("star_csrf", "other-page"));
    assertThrows(
        com.star.common.BusinessException.class,
        () -> captchas.verify(code, "login", bound, "AB234", otherReq));
    captchas.verify(code, "login", bound, "AB234", req);
  }

  @Test
  void delegatedSavePermissionStillRequiresTenantAdministrator() throws Exception {
    String user = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_user(id,tenant_id,name,username,password_hash,builtin)"
            + " VALUES(?,?,'普通用户','member',?,0)",
        user,
        tenant,
        encoder.encode(password));
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
        tenant,
        user,
        normalRole);
    jdbc.update(
        "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT ?,?,id FROM sys_menu WHERE"
            + " tenant_id=? AND permission LIKE 'system:config:%'",
        tenant, normalRole, tenant);
    String token = login("member", password);
    var result =
        mvc.perform(
                put("/api/v1/system/config/login")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(loginSettings(0, true))))
            .andReturn();
    assertEquals(403, result.getResponse().getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_system_config WHERE tenant_id=?", Integer.class, tenant));
    postJson("/auth/logout", Map.of(), token);
  }
}
