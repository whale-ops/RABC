package com.star;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import com.star.tool.excel.ExcelFiles;
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
 * 使用真实 MySQL、Redis 和 MockMvc 验证账号申请、导入、授权与用户删除流程。
 *
 * <p>关联模块：覆盖认证和系统管理模块；每项测试使用独立临时租户，并在结束后清理其数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountRequestsIT {
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

  @Test
  void registrationReviewRecoveryAndOptionalPasswordChange() throws Exception {
    // 初始密码提示标记不再阻断正常业务接口。
    assertEquals(200, getStatus("/auth/menus", adminToken));
    assertEquals(200, getStatus("/dashboard/overview", adminToken));
    assertEquals(
        202, postJson("/auth/register", registration("member"), null).getResponse().getStatus());
    String id = requestId("member");
    String hash =
        jdbc.queryForObject(
            "SELECT password_hash FROM sys_account_request WHERE id=?", String.class, id);
    assertTrue(encoder.matches(password, hash));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND username='member'",
            Integer.class,
            tenant));
    var listed =
        mvc.perform(
                get("/api/v1/system/account-requests")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, listed.getResponse().getStatus());
    assertFalse(listed.getResponse().getContentAsString().contains(hash));
    assertEquals(
        403,
        postJson(
                "/system/account-requests/" + id + "/review",
                Map.of(
                    "decision",
                    "approve",
                    "deptId",
                    dept,
                    "roleIds",
                    List.of(builtinRole),
                    "note",
                    "已核实"),
                adminToken)
            .getResponse()
            .getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND username='member'",
            Integer.class,
            tenant));
    assertEquals(
        204,
        postJson(
                "/system/account-requests/" + id + "/review",
                Map.of(
                    "decision",
                    "approve",
                    "deptId",
                    dept,
                    "roleIds",
                    List.of(normalRole),
                    "note",
                    "已通过内部渠道核实"),
                adminToken)
            .getResponse()
            .getStatus());
    assertNull(
        jdbc.queryForObject(
            "SELECT password_hash FROM sys_account_request WHERE id=?", String.class, id));
    assertEquals(
        409,
        postJson(
                "/system/account-requests/" + id + "/review",
                Map.of("decision", "reject", "note", "重复"),
                adminToken)
            .getResponse()
            .getStatus());
    String token = login("member", password);
    assertEquals(403, getStatus("/system/account-requests", token));
    var wrong =
        Map.of(
            "tenantCode",
            code,
            "username",
            "member",
            "email",
            "wrong@example.test",
            "phone",
            "13800138000");
    assertEquals(202, postJson("/auth/forgot-password", wrong, null).getResponse().getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_account_request WHERE tenant_id=? AND kind='reset'",
            Integer.class,
            tenant));
    assertEquals(
        202,
        postJson(
                "/auth/forgot-password",
                Map.of(
                    "tenantCode",
                    code,
                    "username",
                    "member",
                    "email",
                    "member@example.test",
                    "phone",
                    "13800138000"),
                null)
            .getResponse()
            .getStatus());
    String reset = requestId("member"), next = "ResetTestPass2026";
    assertEquals(
        204,
        postJson(
                "/system/account-requests/" + reset + "/review",
                Map.of("decision", "approve", "newPassword", next, "note", "已回拨企业登记电话核实身份"),
                adminToken)
            .getResponse()
            .getStatus());
    assertEquals(401, getStatus("/auth/me", token));
    token = login("member", next);
    assertEquals(200, getStatus("/auth/menus", token));
    assertEquals(200, getStatus("/dashboard/overview", token));
    var changed =
        mvc.perform(
                patch("/api/v1/auth/password")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("oldPassword", next, "newPassword", "PersonalTestPass2026"))))
            .andReturn();
    assertEquals(204, changed.getResponse().getStatus());
    assertEquals(401, getStatus("/auth/me", token));
    postJson("/auth/logout", Map.of(), login("member", "PersonalTestPass2026"));
  }

  @Test
  void registrationReturnsStoredReceiptAndRejectsInvalidTenantAndExistingAccount()
      throws Exception {
    var invalid = new HashMap<>(registration("new_member"));
    invalid.put("tenantCode", "missing_" + tenant);
    assertEquals(400, postJson("/auth/register", invalid, null).getResponse().getStatus());
    assertEquals(
        409, postJson("/auth/register", registration("admin"), null).getResponse().getStatus());
    var first = postJson("/auth/register", registration("new_member"), null);
    assertEquals(202, first.getResponse().getStatus());
    String receipt = data(first).get("requestId").asText();
    assertEquals(requestId("new_member"), receipt);
    assertEquals("pending", data(first).get("status").asText());
    var duplicate = postJson("/auth/register", registration("new_member"), null);
    assertEquals(202, duplicate.getResponse().getStatus());
    assertEquals(receipt, data(duplicate).get("requestId").asText());
    var queue =
        mvc.perform(
                get("/api/v1/system/account-requests")
                    .param("size", "1")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(1, data(queue).get("total").asInt());
    assertEquals(receipt, data(queue).at("/records/0/id").asText());
  }

  Map<String, Object> importRow(String username) {
    return Map.of(
        "username",
        username,
        "name",
        "导入成员",
        "initialPassword",
        password,
        "deptId",
        dept,
        "roleIds",
        List.of(normalRole),
        "postIds",
        List.of(),
        "email",
        "",
        "phone",
        "",
        "status",
        "启用",
        "remark",
        "");
  }

  @Test
  void importIsAtomicAndExportAndTemplateAreRealXlsx() throws Exception {
    var imported =
        postJson(
            "/system/users/import",
            Map.of("rows", List.of(importRow("import_one"), importRow("import_two"))),
            adminToken);
    assertEquals(
        200, imported.getResponse().getStatus(), imported.getResponse().getContentAsString());
    assertEquals(2, data(imported).get("imported").asInt());
    String hash =
        jdbc.queryForObject(
            "SELECT password_hash FROM sys_user WHERE tenant_id=? AND username='import_one'",
            String.class,
            tenant);
    assertTrue(encoder.matches(password, hash));
    var failed =
        postJson(
            "/system/users/import",
            Map.of("rows", List.of(importRow("rollback_user"), importRow("admin"))),
            adminToken);
    assertEquals(409, failed.getResponse().getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND username='rollback_user'",
            Integer.class,
            tenant));
    var builtin = new HashMap<>(importRow("builtin_denied"));
    builtin.put("roleIds", List.of(builtinRole));
    assertEquals(
        403,
        postJson("/system/users/import", Map.of("rows", List.of(builtin)), adminToken)
            .getResponse()
            .getStatus());
    String memberToken = login("import_one", password);
    assertEquals(
        403,
        postJson(
                "/system/users/import",
                Map.of("rows", List.of(importRow("unauthorized"))),
                memberToken)
            .getResponse()
            .getStatus());
    postJson("/auth/logout", Map.of(), memberToken);
    var template =
        mvc.perform(
                get("/api/v1/system/users/import/template")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, template.getResponse().getStatus());
    try (var book =
        org.apache.poi.ss.usermodel.WorkbookFactory.create(
            new java.io.ByteArrayInputStream(template.getResponse().getContentAsByteArray()))) {
      assertEquals("用户账号", book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
    }
    var bytes =
        ExcelFiles.write(
            "导入",
            List.of("用户账号", "用户姓名", "初始密码"),
            List.of(List.of("preview_only", "预览成员", password)));
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file",
            "users.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes);
    var preview =
        mvc.perform(
                multipart("/api/v1/system/users/import/preview")
                    .file(file)
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, preview.getResponse().getStatus());
    assertEquals("preview_only", data(preview).at("/matrix/1/0").asText());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND username='preview_only'",
            Integer.class,
            tenant));
    var exported =
        mvc.perform(
                get("/api/v1/system/users/export").header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, exported.getResponse().getStatus());
    assertTrue(exported.getResponse().getHeader("Content-Disposition").endsWith(".xlsx\""));
    try (var book =
        org.apache.poi.ss.usermodel.WorkbookFactory.create(
            new java.io.ByteArrayInputStream(exported.getResponse().getContentAsByteArray()))) {
      assertEquals("用户账号", book.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
      assertEquals(3, book.getSheetAt(0).getLastRowNum());
      for (var row : book.getSheetAt(0))
        for (var cell : row) {
          assertNotEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, cell.getCellType());
          assertFalse(cell.toString().contains("$2a$"));
        }
    }
  }

  Map<String, Object> roleImportRow(String name, String code) {
    return Map.of("name", name, "code", code, "scope", "仅本人", "status", "启用", "remark", "");
  }

  @Test
  void roleImportCreatesOrdinaryRolesAndRollsBackDuplicatesAndInvalidScopes() throws Exception {
    var result =
        postJson(
            "/system/roles/import",
            Map.of(
                "rows",
                List.of(
                    roleImportRow("导入角色甲", "import_role_a"),
                    roleImportRow("导入角色乙", "import_role_b"))),
            adminToken);
    assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    assertEquals(2, data(result).get("imported").asInt());
    String role =
        jdbc.queryForObject(
            "SELECT CAST(id AS CHAR) FROM sys_role WHERE tenant_id=? AND code='import_role_a'",
            String.class,
            tenant);
    assertFalse(
        jdbc.queryForObject(
            "SELECT builtin FROM sys_role WHERE tenant_id=? AND id=?",
            Boolean.class,
            tenant,
            role));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role_menu WHERE tenant_id=? AND role_id=?",
            Integer.class,
            tenant,
            role));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND role_id=?",
            Integer.class,
            tenant,
            role));
    var collision =
        postJson(
            "/system/roles/import",
            Map.of(
                "rows",
                List.of(roleImportRow("应回滚角色", "rollback_role"), roleImportRow("编码冲突", "admin"))),
            adminToken);
    assertEquals(409, collision.getResponse().getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND code='rollback_role'",
            Integer.class,
            tenant));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND id=? AND builtin=1",
            Integer.class,
            tenant,
            builtinRole));
    var repeated =
        postJson(
            "/system/roles/import",
            Map.of(
                "rows",
                List.of(roleImportRow("重复甲", "same_code"), roleImportRow("重复乙", "SAME_CODE"))),
            adminToken);
    assertEquals(400, repeated.getResponse().getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND code='same_code'",
            Integer.class,
            tenant));
    var custom = new HashMap<>(roleImportRow("自定义范围", "custom_scope"));
    custom.put("scope", "自定义部门");
    assertEquals(
        400,
        postJson(
                "/system/roles/import",
                Map.of("rows", List.of(roleImportRow("需回滚范围", "scope_rollback"), custom)),
                adminToken)
            .getResponse()
            .getStatus());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND code IN"
                + " ('scope_rollback','custom_scope')",
            Integer.class,
            tenant));
    var injection = new HashMap<>(roleImportRow("禁止内置", "cannot_builtin"));
    injection.put("builtin", true);
    assertEquals(
        400,
        postJson("/system/roles/import", Map.of("rows", List.of(injection)), adminToken)
            .getResponse()
            .getStatus());
    assertEquals(
        400,
        postJson(
                "/system/roles/import",
                Map.of("rows", Collections.nCopies(101, roleImportRow("超限角色", "too_many"))),
                adminToken)
            .getResponse()
            .getStatus());
  }

  @Test
  void roleTemplateAndPreviewAreAuthorizedAndPreviewDoesNotWrite() throws Exception {
    var template =
        mvc.perform(
                get("/api/v1/system/roles/import/template")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, template.getResponse().getStatus());
    try (var book =
        org.apache.poi.ss.usermodel.WorkbookFactory.create(
            new java.io.ByteArrayInputStream(template.getResponse().getContentAsByteArray()))) {
      assertEquals("角色名称", book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
      assertEquals("角色编码", book.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
      assertEquals(5, book.getSheetAt(0).getRow(0).getLastCellNum());
    }
    var bytes =
        ExcelFiles.write("角色导入", List.of("角色名称", "角色编码"), List.of(List.of("角色预览", "preview_role")));
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file",
            "roles.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes);
    var preview =
        mvc.perform(
                multipart("/api/v1/system/roles/import/preview")
                    .file(file)
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, preview.getResponse().getStatus());
    assertEquals("preview_role", data(preview).at("/matrix/1/1").asText());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND code='preview_role'",
            Integer.class,
            tenant));
    assertEquals(
        200,
        postJson(
                "/system/users/import",
                Map.of("rows", List.of(importRow("role_import_member"))),
                adminToken)
            .getResponse()
            .getStatus());
    String token = login("role_import_member", password);
    assertEquals(403, getStatus("/system/roles/import/template", token));
    assertEquals(
        403,
        postJson(
                "/system/roles/import",
                Map.of("rows", List.of(roleImportRow("越权", "unauthorized"))),
                token)
            .getResponse()
            .getStatus());
    assertEquals(
        403,
        mvc.perform(
                multipart("/api/v1/system/roles/import/preview")
                    .file(file)
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
    postJson("/auth/logout", Map.of(), token);
  }

  @Test
  void deletingOneUserErasesProfileAndRequestsButPreservesPeersAndRoles() throws Exception {
    assertEquals(
        200,
        postJson(
                "/system/users/import",
                Map.of("rows", List.of(importRow("manager"), importRow("peer"))),
                adminToken)
            .getResponse()
            .getStatus());
    String manager =
        jdbc.queryForObject(
            "SELECT CAST(id AS CHAR) FROM sys_user WHERE tenant_id=? AND username='manager'",
            String.class,
            tenant);
    String post = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_post(id,tenant_id,name,code) VALUES(?,?,'共同岗位','shared')", post, tenant);
    jdbc.update(
        "INSERT INTO sys_user_post(tenant_id,user_id,post_id) SELECT tenant_id,id,? FROM sys_user"
            + " WHERE tenant_id=? AND username IN ('manager','peer')",
        post,
        tenant);
    jdbc.update(
        "INSERT INTO"
            + " sys_account_request(id,tenant_id,kind,username,name,email,phone,user_id,status)"
            + " VALUES(?,?,'register','old_manager','经理','m@example.test','13800138000',?,'approved'),(?,?,'reset','manager','经理','m@example.test','13800138000',NULL,'pending'),(?,?,'register','peer','同事','p@example.test','13800138001',NULL,'pending')",
        IdWorker.getIdStr(),
        tenant,
        manager,
        IdWorker.getIdStr(),
        tenant,
        IdWorker.getIdStr(),
        tenant);
    String managerToken = login("manager", password), peerToken = login("peer", password);
    var result =
        postJson(
            "/system/users/batch-delete",
            Map.of("items", List.of(Map.of("id", manager, "version", 0))),
            adminToken);
    assertEquals(204, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND id=?",
            Integer.class,
            tenant,
            manager));
    for (String table : List.of("sys_user_role", "sys_user_post"))
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND user_id=?",
              Integer.class,
              tenant,
              manager));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_account_request WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(
        "peer",
        jdbc.queryForObject(
            "SELECT username FROM sys_account_request WHERE tenant_id=?", String.class, tenant));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user_post WHERE tenant_id=? AND post_id=?",
            Integer.class,
            tenant,
            post));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_audit WHERE tenant_id=? AND action='delete' AND"
                + " resource='users' AND resource_id=?",
            Integer.class,
            tenant,
            manager));
    assertEquals(401, getStatus("/auth/me", managerToken));
    assertEquals(200, getStatus("/auth/me", peerToken));
    assertEquals(200, getStatus("/auth/me", adminToken));
    assertEquals(
        401,
        postJson(
                "/auth/login",
                Map.of("tenantCode", code, "username", "manager", "password", password),
                null)
            .getResponse()
            .getStatus());
    postJson("/auth/logout", Map.of(), peerToken);
  }

  @Test
  void userDeletionProtectsBuiltinAndStaleBatchesWithoutClearingPersonalData() throws Exception {
    assertEquals(
        200,
        postJson(
                "/system/users/import",
                Map.of("rows", List.of(importRow("protected_peer"))),
                adminToken)
            .getResponse()
            .getStatus());
    String member =
        jdbc.queryForObject(
            "SELECT CAST(id AS CHAR) FROM sys_user WHERE tenant_id=? AND username='protected_peer'",
            String.class,
            tenant);
    jdbc.update(
        "INSERT INTO sys_account_request(id,tenant_id,kind,username,name,email,phone,user_id)"
            + " VALUES(?,?,'reset','protected_peer','同事','p@example.test','13800138001',?)",
        IdWorker.getIdStr(),
        tenant,
        member);
    for (var items :
        List.of(
            List.of(Map.of("id", member, "version", 0), Map.of("id", admin, "version", 0)),
            List.of(Map.of("id", member, "version", 99)))) {
      var result = postJson("/system/users/batch-delete", Map.of("items", items), adminToken);
      assertEquals(409, result.getResponse().getStatus());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND id=?",
              Integer.class,
              tenant,
              member));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_account_request WHERE tenant_id=? AND user_id=?",
              Integer.class,
              tenant,
              member));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND user_id=?",
              Integer.class,
              tenant,
              member));
    }
  }

  @Test
  void deletingRoleOnlyUnbindsItAndPreservesEveryAccountAndOtherRoles() throws Exception {
    assertEquals(
        200,
        postJson(
                "/system/users/import",
                Map.of("rows", List.of(importRow("role_member"))),
                adminToken)
            .getResponse()
            .getStatus());
    String member =
        jdbc.queryForObject(
            "SELECT CAST(id AS CHAR) FROM sys_user WHERE tenant_id=? AND username='role_member'",
            String.class,
            tenant);
    String second = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_role(id,tenant_id,name,code,scope) VALUES(?,?,'保留角色','remaining','仅本人')",
        second,
        tenant);
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?),(?,?,?)",
        tenant,
        member,
        second,
        tenant,
        admin,
        normalRole);
    jdbc.update(
        "UPDATE sys_menu SET status='停用' WHERE tenant_id=? AND permission='system:user:delete'",
        tenant);
    String token = login("role_member", password);
    var result =
        mvc.perform(
                delete("/api/v1/system/roles/" + normalRole)
                    .param("version", "0")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(204, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND role_id=?",
            Integer.class,
            tenant,
            normalRole));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND user_id=? AND role_id=?",
            Integer.class,
            tenant,
            member,
            second));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND id=?",
            Integer.class,
            tenant,
            second));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_audit WHERE tenant_id=? AND action='delete' AND"
                + " resource='users'",
            Integer.class,
            tenant));
    assertEquals(200, getStatus("/auth/me", token));
    assertEquals(200, getStatus("/auth/me", adminToken));
    postJson("/auth/logout", Map.of(), token);
  }

  @Test
  void roleDeletionBatchProtectsBuiltinAndVersionConflictsWithoutUnbinding() throws Exception {
    assertEquals(
        200,
        postJson(
                "/system/users/import",
                Map.of("rows", List.of(importRow("bound_user"))),
                adminToken)
            .getResponse()
            .getStatus());
    var protectedBatch =
        postJson(
            "/system/roles/batch-delete",
            Map.of(
                "items",
                List.of(
                    Map.of("id", normalRole, "version", 0),
                    Map.of("id", builtinRole, "version", 0))),
            adminToken);
    assertEquals(409, protectedBatch.getResponse().getStatus());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND id=?",
            Integer.class,
            tenant,
            normalRole));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND role_id=?",
            Integer.class,
            tenant,
            normalRole));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT auth_version FROM sys_user WHERE tenant_id=? AND username='bound_user'",
            Integer.class,
            tenant));
    var stale =
        mvc.perform(
                delete("/api/v1/system/roles/" + normalRole)
                    .param("version", "99")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(409, stale.getResponse().getStatus());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND role_id=?",
            Integer.class,
            tenant,
            normalRole));
    String token = login("bound_user", password);
    var denied =
        mvc.perform(
                delete("/api/v1/system/roles/" + normalRole)
                    .param("version", "0")
                    .header("Authorization", "Bearer " + token))
            .andReturn();
    assertEquals(403, denied.getResponse().getStatus());
    postJson("/auth/logout", Map.of(), token);
  }

  @Test
  void rejectionTenantIsolationCsrfAndThrottling() throws Exception {
    var noCsrf =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(registration("unverified"))))
            .andReturn();
    assertEquals(403, noCsrf.getResponse().getStatus());
    assertEquals(
        202, postJson("/auth/register", registration("rejected"), null).getResponse().getStatus());
    String id = requestId("rejected");
    String foreign = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'其他租户')", foreign, "foreign_" + foreign);
    fixtureTenants.add(foreign);
    String otherId = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_account_request(id,tenant_id,kind,username,name,email,phone)"
            + " VALUES(?,?,'register','foreign','其他申请人','a@example.test','13800138001')",
        otherId,
        foreign);
    assertEquals(
        404,
        postJson(
                "/system/account-requests/" + otherId + "/review",
                Map.of("decision", "reject", "note", "越权"),
                adminToken)
            .getResponse()
            .getStatus());
    assertEquals(
        204,
        postJson(
                "/system/account-requests/" + id + "/review",
                Map.of("decision", "reject", "note", "企业身份未核实"),
                adminToken)
            .getResponse()
            .getStatus());
    assertNull(
        jdbc.queryForObject(
            "SELECT password_hash FROM sys_account_request WHERE id=?", String.class, id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND username='rejected'",
            Integer.class,
            tenant));
    for (int i = 0; i < 4; i++)
      assertEquals(
          202, postJson("/auth/register", registration("pending"), null).getResponse().getStatus());
    assertEquals(
        429, postJson("/auth/register", registration("limited"), null).getResponse().getStatus());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_account_request WHERE tenant_id=? AND username='pending'",
            Integer.class,
            tenant));
  }
}
