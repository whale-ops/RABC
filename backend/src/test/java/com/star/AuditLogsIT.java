package com.star;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import com.star.monitor.model.LogEvent;
import com.star.monitor.service.AuditLogWriter;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;

/**
 * 验证 AOP 日志、失败操作回滚、租户隔离、查询导出权限及用户删除后的匿名化。
 *
 * <p>关联模块：连接真实数据库和 Redis，调用认证、系统管理与日志接口；临时租户在测试后清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditLogsIT {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired BCryptPasswordEncoder encoder;
  @Autowired StringRedisTemplate redis;
  @Autowired AuditLogWriter writer;
  String tenant, code, admin, dept, role, memberRole, token, csrf;
  Cookie cookie;
  List<String> tenants = new ArrayList<>();
  final String password = "AuditTestPass2026";

  @BeforeEach
  void setup() throws Exception {
    tenant = IdWorker.getIdStr();
    code = "audit_it_" + tenant;
    admin = IdWorker.getIdStr();
    dept = IdWorker.getIdStr();
    role = IdWorker.getIdStr();
    memberRole = IdWorker.getIdStr();
    tenants.add(tenant);
    jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'日志隔离测试')", tenant, code);
    jdbc.update("INSERT INTO sys_dept(id,tenant_id,name) VALUES(?,?,'日志测试部门')", dept, tenant);
    jdbc.update(
        "INSERT INTO sys_role(id,tenant_id,name,code,scope,builtin)"
            + " VALUES(?,?,'管理员','admin','全部数据',1),(?,?,'成员','member','仅本人',0)",
        role,
        tenant,
        memberRole,
        tenant);
    for (String permission :
        List.of(
            "system:post:create",
            "system:role:create",
            "system:user:delete",
            "audit:operation:list",
            "audit:operation:read",
            "audit:operation:export",
            "audit:login:list",
            "audit:login:read",
            "audit:login:export")) {
      jdbc.update(
          "INSERT INTO sys_menu(id,tenant_id,name,type,permission) VALUES(?,?,'测试权限','按钮',?)",
          IdWorker.getIdStr(),
          tenant,
          permission);
    }
    String member = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_user(id,tenant_id,username,name,password_hash,dept_id,builtin)"
            + " VALUES(?,?,'admin','测试管理员',?,?,1),(?,?,'member','普通成员',?,?,0)",
        admin,
        tenant,
        encoder.encode(password),
        dept,
        member,
        tenant,
        encoder.encode(password),
        dept);
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?),(?,?,?)",
        tenant,
        admin,
        role,
        tenant,
        member,
        memberRole);
    var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
    csrf = json.readTree(response.getContentAsString()).at("/data/token").asText();
    String header = response.getHeader("Set-Cookie");
    cookie =
        new Cookie("star_csrf", header.substring(header.indexOf('=') + 1, header.indexOf(';')));
    token = login("admin", password);
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      if (token != null) postJson("/auth/logout", Map.of(), token);
    } finally {
      for (String id : tenants) {
        for (String table :
            List.of(
                "sys_login_log",
                "sys_operation_log",
                "sys_user_post",
                "sys_user_role",
                "sys_role_menu",
                "sys_role_dept",
                "sys_account_request",
                "sys_audit",
                "sys_user",
                "sys_post",
                "sys_dept",
                "sys_role",
                "sys_menu")) jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", id);
        jdbc.update("DELETE FROM sys_tenant WHERE id=?", id);
      }
      if (cookie != null) redis.delete("star:csrf:" + AuthService.hash(cookie.getValue()));
    }
  }

  MvcResult postJson(String path, Object body, String bearer) throws Exception {
    var req =
        post("/api/v1" + path)
            .contentType("application/json")
            .content(json.writeValueAsBytes(body))
            .cookie(cookie)
            .header("X-CSRF-Token", csrf)
            .header("Origin", "http://127.0.0.1:5173")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Edg/130.0")
            .header("X-Forwarded-For", "203.0.113.99");
    if (bearer != null) req.header("Authorization", "Bearer " + bearer);
    return mvc.perform(req).andReturn();
  }

  String login(String username, String value) throws Exception {
    var response =
        postJson(
                "/auth/login",
                Map.of("tenantCode", code, "username", username, "password", value),
                null)
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsString()).at("/data/accessToken").asText();
  }

  long count(String table) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, tenant);
  }

  MvcResult query(String path, String bearer) throws Exception {
    return mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + bearer))
        .andReturn();
  }

  @Test
  void aopCapturesSuccessfulAndFailedLoginWithoutCredentialsOrSpoofedIp() throws Exception {
    var failed =
        postJson(
            "/auth/login",
            Map.of("tenantCode", code, "username", "admin", "password", "WrongSecret2026"),
            null);
    assertEquals(401, failed.getResponse().getStatus());
    assertEquals(2, count("sys_login_log"));
    assertEquals(0, count("sys_operation_log"));
    var logs =
        jdbc.queryForList(
            "SELECT * FROM sys_login_log WHERE tenant_id=? ORDER BY created_at", tenant);
    assertEquals("成功", logs.get(0).get("status"));
    assertEquals("失败", logs.get(1).get("status"));
    assertEquals("INVALID_CREDENTIALS", logs.get(1).get("error_code"));
    assertEquals("Edge", logs.get(1).get("browser"));
    assertEquals("Windows", logs.get(1).get("os"));
    assertNotEquals("203.0.113.99", logs.get(1).get("ip"));
    assertFalse(logs.toString().contains(password));
    assertFalse(logs.toString().contains("WrongSecret2026"));
    assertFalse(logs.toString().contains(token));
    assertEquals(failed.getResponse().getHeader("X-Request-Id"), logs.get(1).get("trace_id"));
    assertTrue(((Number) logs.get(1).get("duration_ms")).longValue() >= 0);
  }

  @Test
  void aopRecordsMutationsAndFailedRollbackOncePerRequest() throws Exception {
    Map<String, Object> post =
        Map.of(
            "name",
            "日志岗位",
            "code",
            "audit_post",
            "status",
            "启用",
            "sort",
            0,
            "remark",
            "private-payload-must-not-be-logged");
    assertEquals(201, postJson("/system/posts", post, token).getResponse().getStatus());
    assertEquals(409, postJson("/system/posts", post, token).getResponse().getStatus());
    assertEquals(1, count("sys_post"));
    assertEquals(2, count("sys_operation_log"));
    var importResult =
        postJson(
            "/system/roles/import",
            Map.of(
                "rows",
                List.of(
                    Map.of("name", "应当回滚", "code", "rollback_role", "scope", "仅本人", "status", "启用"),
                    Map.of("name", "重复编码", "code", "member", "scope", "仅本人", "status", "启用"))),
            token);
    assertEquals(409, importResult.getResponse().getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND code='rollback_role'",
            Long.class,
            tenant));
    assertEquals(3, count("sys_operation_log"));
    var logs =
        jdbc.queryForList(
            "SELECT * FROM sys_operation_log WHERE tenant_id=? ORDER BY created_at", tenant);
    assertEquals("成功", logs.get(0).get("status"));
    assertEquals("失败", logs.get(1).get("status"));
    assertEquals("导入", logs.get(2).get("action"));
    assertEquals("失败", logs.get(2).get("status"));
    assertFalse(logs.toString().contains("private-payload-must-not-be-logged"));
    assertFalse(logs.toString().contains(token));
  }

  @Test
  void deletingUserAnonymizesLogsAndLateEventsCannotRestorePersonalData() throws Exception {
    String memberToken = login("member", password);
    String member =
        jdbc.queryForObject(
            "SELECT CAST(id AS CHAR) FROM sys_user WHERE tenant_id=? AND username='member'",
            String.class,
            tenant);
    var result =
        postJson(
            "/system/users/batch-delete",
            Map.of("items", List.of(Map.of("id", member, "version", 0))),
            token);
    assertEquals(204, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    writer.write(
        false,
        new LogEvent(
            tenant,
            code,
            member,
            "member",
            "普通成员",
            "用户管理",
            "修改",
            "PATCH",
            "/api/v1/auth/profile",
            "192.0.2.1",
            "Edge",
            "Windows",
            "stale-client",
            "成功",
            200,
            "",
            1,
            "late-event"));
    for (String table : List.of("sys_login_log", "sys_operation_log")) {
      var logs =
          jdbc.queryForList(
              "SELECT username,display_name,ip,user_agent FROM "
                  + table
                  + " WHERE tenant_id=? AND user_id=?",
              tenant,
              member);
      assertFalse(logs.isEmpty());
      for (var row : logs) {
        assertEquals("已删除", row.get("username"));
        assertEquals("已删除", row.get("display_name"));
        assertEquals("", row.get("ip"));
        assertEquals("", row.get("user_agent"));
      }
    }
    assertEquals(401, query("/auth/me", memberToken).getResponse().getStatus());
  }

  @Test
  void listDetailFiltersExportAndPermissionsAreTenantScopedAndReadOnly() throws Exception {
    String foreign = IdWorker.getIdStr(), foreignLog = IdWorker.getIdStr();
    tenants.add(foreign);
    jdbc.update(
        "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'其他租户')",
        foreign,
        "audit_other_" + foreign);
    jdbc.update(
        "INSERT INTO sys_login_log(id,tenant_id,tenant_code,username,status,http_status)"
            + " VALUES(?,?,?,'foreign-user','成功',200)",
        foreignLog,
        foreign,
        "other");
    var response = query("/audit/logins", token).getResponse();
    assertEquals(200, response.getStatus());
    var data = json.readTree(response.getContentAsString()).get("data");
    assertEquals(1, data.get("total").asInt());
    String id = data.get("records").get(0).get("id").asText();
    assertTrue(data.get("records").get(0).get("createdAt").asText().endsWith("Z"));
    assertEquals(200, query("/audit/logins/" + id, token).getResponse().getStatus());
    assertEquals(404, query("/audit/logins/" + foreignLog, token).getResponse().getStatus());
    assertEquals(0, count("sys_operation_log"));
    var filtered =
        mvc.perform(
                get("/api/v1/audit/logins")
                    .param("keyword", "%_")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse();
    assertEquals(0, json.readTree(filtered.getContentAsString()).at("/data/total").asInt());
    assertEquals(400, query("/audit/logins?size=101", token).getResponse().getStatus());
    assertEquals(
        400,
        query("/audit/logins?start=2026-10-21T00:00:00Z&end=2026-10-20T00:00:00Z", token)
            .getResponse()
            .getStatus());
    var exported = query("/audit/logins/export", token).getResponse();
    assertEquals(200, exported.getStatus());
    assertTrue(exported.getContentType().contains("spreadsheetml"));
    assertEquals('P', exported.getContentAsByteArray()[0]);
    assertEquals('K', exported.getContentAsByteArray()[1]);
    assertEquals(1, count("sys_operation_log"));
    String member = login("member", password);
    for (String path :
        List.of(
            "/audit/logins", "/audit/operations", "/audit/logins/" + id, "/audit/logins/export"))
      assertEquals(403, query(path, member).getResponse().getStatus());
    postJson("/auth/logout", Map.of(), member);
    assertEquals(
        405,
        mvc.perform(delete("/api/v1/audit/logins/" + id).header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
  }
}
