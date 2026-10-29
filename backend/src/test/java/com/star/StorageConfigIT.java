package com.star;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import com.star.storage.enums.StorageProvider;
import com.star.storage.factory.*;
import com.star.storage.service.StorageConfigService;
import com.star.storage.strategy.StorageStrategy;
import jakarta.servlet.http.Cookie;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

/**
 * 使用真实 MySQL、Redis 和 MockMvc 验证账号申请、导入、授权与用户删除流程。
 *
 * <p>关联模块：覆盖认证和系统管理模块；每项测试使用独立临时租户，并在结束后清理其数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageConfigIT {
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
            "storage:file:list",
            "storage:file:upload",
            "storage:file:download",
            "storage:file:delete",
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
                "sys_storage_file",
                "sys_storage_setting",
                "sys_storage_entry",
                "sys_storage_profile",
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

  @MockitoBean StorageClientFactory clients;
  @Autowired StorageConfigService configService;
  @Autowired List<StorageBackendAdapter> adapters;
  final Map<String, byte[]> objects = new HashMap<>();
  final List<StorageConfigService.Connection> connections = new ArrayList<>();

  @BeforeEach
  void clients() {
    when(clients.types()).thenReturn(adapters.stream().map(StorageBackendAdapter::type).toList());
    when(clients.adapter(anyString()))
        .thenAnswer(
            a ->
                adapters.stream()
                    .filter(t -> t.type().value().equals(a.getArgument(0)))
                    .findFirst()
                    .orElseThrow(() -> com.star.common.BusinessException.bad("unsupported")));
    when(clients.create(any()))
        .thenAnswer(
            call -> {
              StorageConfigService.Connection c = call.getArgument(0);
              connections.add(c);
              var strategy = mock(StorageStrategy.class);
              when(strategy.provider()).thenReturn(c.provider());
              when(strategy.bucket()).thenReturn(c.bucket());
              doAnswer(
                      a -> {
                        objects.put(
                            c.endpoint() + "/" + a.getArgument(0) + "/" + a.getArgument(1),
                            ((InputStream) a.getArgument(2)).readAllBytes());
                        return null;
                      })
                  .when(strategy)
                  .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
              when(strategy.open(anyString(), anyString()))
                  .thenAnswer(
                      a ->
                          new ByteArrayInputStream(
                              objects.get(
                                  c.endpoint() + "/" + a.getArgument(0) + "/" + a.getArgument(1))));
              doAnswer(
                      a -> {
                        objects.remove(
                            c.endpoint() + "/" + a.getArgument(0) + "/" + a.getArgument(1));
                        return null;
                      })
                  .when(strategy)
                  .delete(anyString(), anyString());
              return strategy;
            });
  }

  Map<String, Object> provider(String endpoint, String bucket, String access, String secret) {
    return Map.of(
        "endpoint",
        endpoint,
        "bucket",
        bucket,
        "region",
        "us-east-1",
        "accessKey",
        access,
        "secretKey",
        secret);
  }

  Map<String, Object> blank() {
    return provider("", "", "", "");
  }

  Map<String, Object> settings(
      long version,
      boolean enabled,
      String selected,
      Map<String, Object> oss,
      Map<String, Object> minio) {
    return Map.of(
        "version",
        version,
        "enabled",
        enabled,
        "provider",
        selected,
        "aliyun",
        oss,
        "minio",
        minio);
  }

  Map<String, Object> oss() {
    return provider(
        "https://oss-cn-beijing.aliyuncs.com",
        "oss-dynamic-test",
        "test-access-a",
        "test-secret-a");
  }

  Map<String, Object> minio() {
    return provider(
        "http://127.0.0.1:9000", "minio-dynamic-test", "test-access-m", "test-secret-m");
  }

  MvcResult save(Object body) throws Exception {
    return mvc.perform(
            put("/api/v1/system/config/storage")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(json.writeValueAsBytes(body)))
        .andReturn();
  }

  String uploadFile(String name, byte value) throws Exception {
    var result =
        mvc.perform(
                multipart("/api/v1/storage/files")
                    .file(new MockMultipartFile("file", name, "text/plain", new byte[] {value}))
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, result.getResponse().getStatus());
    return data(result).get("id").asText();
  }

  String location(String id) {
    return jdbc.queryForObject(
        "SELECT CAST(storage_config_id AS CHAR) FROM sys_storage_file WHERE id=?",
        String.class,
        id);
  }

  byte[] download(String id) throws Exception {
    var result =
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/content")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, result.getResponse().getStatus());
    return result.getResponse().getContentAsByteArray();
  }

  @Test
  void encryptedSettingsAreWriteOnlyVersionedAndOnlyAdminCanManage() throws Exception {
    assertEquals(
        401,
        mvc.perform(get("/api/v1/system/config/storage")).andReturn().getResponse().getStatus());
    var first = save(settings(0, true, "aliyun", oss(), minio()));
    assertEquals(200, first.getResponse().getStatus());
    var view = data(first);
    assertTrue(view.at("/aliyun/accessKeySet").asBoolean());
    assertFalse(view.get("aliyun").has("accessKey"));
    assertFalse(first.getResponse().getContentAsString().contains("test-secret"));
    assertEquals(409, save(settings(0, true, "minio", oss(), minio())).getResponse().getStatus());
    var row =
        jdbc.queryForMap(
            "SELECT access_cipher,secret_cipher FROM sys_storage_profile WHERE tenant_id=? AND"
                + " provider='ALIYUN'",
            tenant);
    assertNotEquals("test-access-a", row.get("access_cipher"));
    assertNotEquals("test-secret-a", row.get("secret_cipher"));
    String member = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_user(id,tenant_id,name,username,password_hash)"
            + " VALUES(?,?,'普通用户','member',?)",
        member,
        tenant,
        encoder.encode(password));
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
        tenant,
        member,
        normalRole);
    jdbc.update(
        "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT ?,?,id FROM sys_menu WHERE"
            + " tenant_id=? AND permission LIKE 'system:config:%'",
        tenant, normalRole, tenant);
    String token = login("member", password);
    assertEquals(
        403,
        mvc.perform(get("/api/v1/system/config/storage").header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        403,
        mvc.perform(
                put("/api/v1/system/config/storage")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(settings(1, false, "minio", oss(), minio()))))
            .andReturn()
            .getResponse()
            .getStatus());
    postJson("/auth/logout", Map.of(), token);
  }

  @Test
  void switchingProviderAndEndpointKeepsHistoryOnOriginalLocationAndDisableOnlyStopsUploads()
      throws Exception {
    assertEquals(200, save(settings(0, true, "aliyun", oss(), minio())).getResponse().getStatus());
    String a = uploadFile("oss.txt", (byte) 1);
    assertEquals(StorageProvider.ALIYUN, connections.get(connections.size() - 1).provider());
    assertEquals(200, save(settings(1, true, "minio", oss(), minio())).getResponse().getStatus());
    String m = uploadFile("minio.txt", (byte) 2), old = location(m);
    assertEquals(StorageProvider.MINIO, connections.get(connections.size() - 1).provider());
    var changed = provider("http://127.0.0.1:9002", "minio-next", "", "");
    assertEquals(200, save(settings(2, true, "minio", oss(), changed)).getResponse().getStatus());
    String next = uploadFile("next.txt", (byte) 3);
    assertNotEquals(old, location(next));
    assertEquals("http://127.0.0.1:9002", connections.get(connections.size() - 1).endpoint());
    assertArrayEquals(new byte[] {2}, download(m));
    assertEquals("http://127.0.0.1:9000", connections.get(connections.size() - 1).endpoint());
    assertArrayEquals(new byte[] {1}, download(a));
    assertEquals(StorageProvider.ALIYUN, connections.get(connections.size() - 1).provider());
    assertEquals(200, save(settings(3, false, "minio", oss(), changed)).getResponse().getStatus());
    assertEquals(
        400,
        mvc.perform(
                multipart("/api/v1/storage/files")
                    .file(
                        new MockMultipartFile("file", "blocked.txt", "text/plain", new byte[] {4}))
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn()
            .getResponse()
            .getStatus());
    assertArrayEquals(new byte[] {3}, download(next));
    assertEquals(
        200,
        mvc.perform(
                delete("/api/v1/storage/files/" + m)
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals("http://127.0.0.1:9000", connections.get(connections.size() - 1).endpoint());
  }

  @Test
  void rotatingCredentialsAtSameLocationAppliesToHistoricalFilesAndBlankFieldsRetainThem()
      throws Exception {
    assertEquals(200, save(settings(0, true, "minio", blank(), minio())).getResponse().getStatus());
    String id = uploadFile("history.txt", (byte) 7), profile = location(id);
    assertEquals(
        200,
        save(settings(
                1,
                true,
                "minio",
                blank(),
                provider(
                    "http://127.0.0.1:9000", "minio-dynamic-test", "new-access", "new-secret")))
            .getResponse()
            .getStatus());
    assertEquals(profile, configService.head(tenant, false).minioId());
    assertArrayEquals(new byte[] {7}, download(id));
    assertEquals("new-secret", connections.get(connections.size() - 1).secretKey());
    assertEquals(
        200,
        save(settings(
                2,
                true,
                "minio",
                blank(),
                provider("http://127.0.0.1:9000", "minio-dynamic-test", "", "")))
            .getResponse()
            .getStatus());
    assertEquals("new-secret", configService.connection(tenant, profile).secretKey());
    assertFalse(configService.connection(tenant, profile).toString().contains("new-secret"));
    jdbc.update(
        "UPDATE sys_storage_profile SET secret_cipher='corrupted-test-cipher' WHERE id=?", profile);
    assertEquals(
        200,
        save(settings(
                3,
                true,
                "minio",
                blank(),
                provider(
                    "http://127.0.0.1:9000",
                    "minio-dynamic-test",
                    "repaired-access",
                    "repaired-secret")))
            .getResponse()
            .getStatus());
    assertArrayEquals(new byte[] {7}, download(id));
    assertEquals("repaired-secret", connections.get(connections.size() - 1).secretKey());
    clearInvocations(clients);
    assertEquals(
        200,
        mvc.perform(
                delete("/api/v1/storage/files/" + id + "/record")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn()
            .getResponse()
            .getStatus());
    verifyNoInteractions(clients);
  }

  @Test
  void invalidInputsRollbackAndUnconfiguredTenantCannotUpload() throws Exception {
    for (String endpoint :
        List.of(
            "ftp://example.com",
            "http://name:pass@example.com",
            "http://example.com/path",
            "http://example.com?key=x",
            "http://example.com:99999"))
      assertEquals(
          400,
          save(settings(
                  0, true, "minio", blank(), provider(endpoint, "valid-bucket", "id", "secret")))
              .getResponse()
              .getStatus());
    assertEquals(
        400, save(settings(0, true, "aliyun", blank(), blank())).getResponse().getStatus());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_setting WHERE tenant_id=?", Long.class, tenant));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_profile WHERE tenant_id=?", Long.class, tenant));
    assertEquals(
        400,
        mvc.perform(
                multipart("/api/v1/storage/files")
                    .file(
                        new MockMultipartFile(
                            "file", "unconfigured.txt", "text/plain", new byte[] {1}))
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn()
            .getResponse()
            .getStatus());
    verifyNoInteractions(clients);
  }

  @Test
  void profilesAndFilesAreTenantIsolatedAndCiphertextCannotBeMoved() throws Exception {
    assertEquals(200, save(settings(0, true, "minio", blank(), minio())).getResponse().getStatus());
    String id = uploadFile("isolated.txt", (byte) 5), profile = location(id);
    String other = IdWorker.getIdStr();
    fixtureTenants.add(other);
    jdbc.update(
        "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'另一个租户')", other, "other_" + other);
    assertFalse(configService.head(other, false).enabled());
    assertThrows(
        com.star.common.BusinessException.class, () -> configService.connection(other, profile));
    String copied = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO"
            + " sys_storage_profile(id,tenant_id,provider,endpoint,region,bucket,access_cipher,secret_cipher)"
            + " SELECT ?,?,provider,endpoint,region,bucket,access_cipher,secret_cipher FROM"
            + " sys_storage_profile WHERE id=?",
        copied,
        other,
        profile);
    assertThrows(
        com.star.common.BusinessException.class, () -> configService.connection(other, copied));
    assertEquals(profile, location(id));
  }

  MvcResult catalogCall(String method, String suffix, Object body) throws Exception {
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method),
                "/api/v1/system/config/storage/catalog" + suffix)
            .header("Authorization", "Bearer " + adminToken);
    if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
    return mvc.perform(request).andReturn();
  }

  JsonNode catalog() throws Exception {
    var r = catalogCall("GET", "", null);
    assertEquals(200, r.getResponse().getStatus());
    return data(r);
  }

  JsonNode catalogSave(String id, String name, String type, boolean enabled, Map<String, ?> values)
      throws Exception {
    long version = catalog().get("version").asLong();
    var r =
        catalogCall(
            id == null ? "POST" : "PUT",
            id == null ? "" : "/" + id,
            Map.of(
                "version", version, "name", name, "type", type, "enabled", enabled, "values",
                values));
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return data(r);
  }

  MvcResult choose(String id, boolean enabled, long version) throws Exception {
    var body = new HashMap<String, Object>();
    body.put("version", version);
    body.put("entryId", id);
    body.put("enabled", enabled);
    return catalogCall("PUT", "/selection", body);
  }

  @Test
  void catalogMigratesOldSelectionAndSupportsMultipleOtherServersWithoutBreakingHistory()
      throws Exception {
    assertEquals(200, save(settings(0, true, "aliyun", oss(), minio())).getResponse().getStatus());
    String old = uploadFile("legacy.txt", (byte) 1);
    var first = catalog();
    assertEquals(2, first.get("items").size());
    assertTrue(first.get("enabled").asBoolean());
    assertEquals(5, first.get("types").size());
    var a =
        catalogSave(
            null,
            "公司其他服务器",
            "s3",
            true,
            provider("http://127.0.0.1:9100", "third-party", "new-s3-access", "new-s3-secret"));
    String id = a.get("items").get(2).get("id").asText();
    assertFalse(a.toString().contains("new-s3-secret"));
    assertTrue(a.get("items").get(2).get("secretsSet").toString().contains("secretKey"));
    assertEquals(200, choose(id, true, a.get("version").asLong()).getResponse().getStatus());
    String file = uploadFile("third.txt", (byte) 2), location = location(file);
    assertEquals(StorageProvider.S3, connections.get(connections.size() - 1).provider());
    var b =
        catalogSave(
            null,
            "备用 S3 服务器",
            "s3",
            true,
            provider("http://127.0.0.1:9200", "backup-bucket", "backup-access", "backup-secret"));
    assertEquals(4, b.get("items").size());
    var changed =
        catalogSave(
            id, "公司其他服务器", "s3", true, provider("http://127.0.0.1:9300", "moved-bucket", "", ""));
    String next = uploadFile("next.txt", (byte) 3);
    assertNotEquals(location, location(next));
    assertEquals("new-s3-secret", connections.get(connections.size() - 1).secretKey());
    assertArrayEquals(new byte[] {2}, download(file));
    assertEquals("http://127.0.0.1:9100", connections.get(connections.size() - 1).endpoint());
    assertArrayEquals(new byte[] {1}, download(old));
    assertEquals(
        400,
        catalogCall("DELETE", "/" + id + "?version=" + changed.get("version").asLong(), null)
            .getResponse()
            .getStatus());
    assertEquals(200, choose(id, false, changed.get("version").asLong()).getResponse().getStatus());
    assertEquals(
        200,
        catalogCall("DELETE", "/" + id + "?version=" + catalog().get("version").asLong(), null)
            .getResponse()
            .getStatus());
    assertArrayEquals(new byte[] {2}, download(file));
    assertArrayEquals(new byte[] {3}, download(next));
    assertTrue(catalog().get("entryId").isNull());
  }

  @Test
  void catalogRejectsUnsupportedProtocolsStaleEditsAndForeignEntries() throws Exception {
    var initial = catalog();
    long version = initial.get("version").asLong();
    assertEquals(
        400,
        catalogCall(
                "POST",
                "",
                Map.of(
                    "version",
                    version,
                    "name",
                    "网页",
                    "type",
                    "http",
                    "enabled",
                    true,
                    "values",
                    Map.of("endpoint", "https://example.com")))
            .getResponse()
            .getStatus());
    var saved =
        catalogSave(
            null,
            "测试服务器",
            "s3",
            true,
            provider("https://s3.example.test", "test-bucket", "access", "secret"));
    String id = saved.get("items").get(0).get("id").asText();
    assertEquals(409, choose(id, true, version).getResponse().getStatus());
    assertEquals(
        404,
        choose(IdWorker.getIdStr(), true, saved.get("version").asLong()).getResponse().getStatus());
    assertEquals(
        400,
        catalogCall(
                "POST",
                "",
                Map.of(
                    "version",
                    saved.get("version").asLong(),
                    "name",
                    "invalid",
                    "type",
                    "s3",
                    "enabled",
                    true,
                    "values",
                    Map.of("command", "run")))
            .getResponse()
            .getStatus());
    var disabled =
        catalogSave(
            id, "测试服务器", "s3", false, provider("https://s3.example.test", "test-bucket", "", ""));
    assertEquals(400, choose(id, true, disabled.get("version").asLong()).getResponse().getStatus());
    assertEquals(1, catalog().get("items").size());
    assertEquals(
        401,
        mvc.perform(get("/api/v1/system/config/storage/catalog"))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void localVideoTicketsSupportRangesAndStopWorkingWhenFileRecordIsRemoved(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    var saved =
        catalogSave(
            null, "本地目录", "local", true, Map.of("endpoint", root.resolve("files").toString()));
    String entry = saved.get("items").get(0).get("id").asText();
    assertTrue(saved.get("items").get(0).get("secretsSet").isEmpty());
    assertEquals(200, choose(entry, true, saved.get("version").asLong()).getResponse().getStatus());
    String file = uploadFile("video.mp4", (byte) 42);
    var playback =
        mvc.perform(
                get("/api/v1/storage/files/" + file + "/playback")
                    .header("Authorization", "Bearer " + adminToken))
            .andReturn();
    assertEquals(200, playback.getResponse().getStatus());
    String url = data(playback).get("url").asText();
    String token = url.substring(url.lastIndexOf('/') + 1);
    try {
      var range = mvc.perform(get(url).header("Range", "bytes=0-0")).andReturn();
      assertEquals(206, range.getResponse().getStatus());
      assertEquals("bytes 0-0/1", range.getResponse().getHeader("Content-Range"));
      assertArrayEquals(new byte[] {42}, range.getResponse().getContentAsByteArray());
      assertEquals("video/mp4", range.getResponse().getContentType());
      assertEquals(
          416,
          mvc.perform(get(url).header("Range", "bytes=5-")).andReturn().getResponse().getStatus());
      assertEquals(
          206,
          mvc.perform(head(url).header("Range", "bytes=-1")).andReturn().getResponse().getStatus());
      assertEquals(
          200,
          mvc.perform(
                  delete("/api/v1/storage/files/" + file + "/record")
                      .header("Authorization", "Bearer " + adminToken))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(404, mvc.perform(get(url)).andReturn().getResponse().getStatus());
      assertEquals(
          404,
          mvc.perform(get("/api/v1/storage/media/" + "0".repeat(64)))
              .andReturn()
              .getResponse()
              .getStatus());
    } finally {
      redis.delete("star:storage:media:" + AuthService.hash(token));
    }
  }
}
