package com.star;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.factory.StorageStrategyFactory;
import com.star.storage.strategy.StorageStrategy;
import jakarta.servlet.http.Cookie;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

/** 真实 MySQL / Redis 下验证文件接口、AOP、租户和用户隔离以及历史策略路由。 关联模块：存储工厂替换为可控策略桩；不会向真实 OSS / MinIO 上传或删除任何文件。 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageFilesIT {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired BCryptPasswordEncoder encoder;
  @Autowired StringRedisTemplate redis;
  @MockitoBean StorageStrategyFactory factory;
  @MockitoBean com.star.storage.factory.StorageRouter router;
  StorageStrategy aliyun, minio;
  Map<String, byte[]> objects;
  String tenant, otherTenant, code, admin, member, memberRole, token, csrf;
  Cookie cookie;

  @BeforeEach
  void setup() throws Exception {
    tenant = IdWorker.getIdStr();
    otherTenant = IdWorker.getIdStr();
    code = "storage_it_" + tenant;
    admin = IdWorker.getIdStr();
    member = IdWorker.getIdStr();
    memberRole = IdWorker.getIdStr();
    String role = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'存储测试'),(?,?,'隔离租户')",
        tenant,
        code,
        otherTenant,
        "storage_it_" + otherTenant);
    jdbc.update(
        "INSERT INTO sys_role(id,tenant_id,name,code,scope,builtin)"
            + " VALUES(?,?,'管理员','admin','全部数据',1),(?,?,'成员','member','仅本人',0)",
        role,
        tenant,
        memberRole,
        tenant);
    for (String action : List.of("list", "upload", "download", "delete"))
      jdbc.update(
          "INSERT INTO sys_menu(id,tenant_id,name,type,permission) VALUES(?,?,'测试权限','按钮',?)",
          IdWorker.getIdStr(),
          tenant,
          "storage:file:" + action);
    String hash = encoder.encode("StorageTest2026");
    jdbc.update(
        "INSERT INTO sys_user(id,tenant_id,username,name,password_hash,builtin)"
            + " VALUES(?,?,'admin','测试管理员',?,1),(?,?,'member','测试成员',?,0)",
        admin,
        tenant,
        hash,
        member,
        tenant,
        hash);
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
    token = login("admin");
    objects = new HashMap<>();
    aliyun = strategy(StorageProvider.ALIYUN, "original-bucket");
    minio = strategy(StorageProvider.MINIO, "new-bucket");
    when(factory.current()).thenReturn(aliyun);
    when(factory.get(StorageProvider.ALIYUN)).thenReturn(aliyun);
    when(factory.get(StorageProvider.MINIO)).thenReturn(minio);
    when(router.current()).thenAnswer(call -> new com.star.storage.factory.StorageRouter.Access(null,factory.current(),false));
    when(router.forFile(anyMap())).thenAnswer(call -> { Map<String,Object> row=call.getArgument(0);return new com.star.storage.factory.StorageRouter.Access(null,factory.get(StorageProvider.valueOf((String)row.get("provider"))),false); });
  }

  StorageStrategy strategy(StorageProvider provider, String bucket) {
    var result = mock(StorageStrategy.class);
    when(result.provider()).thenReturn(provider);
    when(result.bucket()).thenReturn(bucket);
    when(result.playbackUrl(anyString(), anyString(), anyString(), anyInt()))
        .thenReturn("https://storage.example/video?temporary-signature=test");
    doAnswer(
            call -> {
              objects.put(
                  call.getArgument(0) + "/" + call.getArgument(1),
                  ((InputStream) call.getArgument(2)).readAllBytes());
              return null;
            })
        .when(result)
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
    when(result.open(anyString(), anyString()))
        .thenAnswer(
            call ->
                new ByteArrayInputStream(
                    objects.get(call.getArgument(0) + "/" + call.getArgument(1))));
    doAnswer(
            call -> {
              objects.remove(call.getArgument(0) + "/" + call.getArgument(1));
              return null;
            })
        .when(result)
        .delete(anyString(), anyString());
    return result;
  }

  String login(String username) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "tenantCode",
                                code,
                                "username",
                                username,
                                "password",
                                "StorageTest2026")))
                    .cookie(cookie)
                    .header("X-CSRF-Token", csrf)
                    .header("Origin", "http://127.0.0.1:5173"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsString()).at("/data/accessToken").asText();
  }

  MvcResult upload(String bearer, String name, byte[] bytes) throws Exception {
    return mvc.perform(
            multipart("/api/v1/storage/files")
                .file(new MockMultipartFile("file", name, "text/plain", bytes))
                .header("Authorization", "Bearer " + bearer))
        .andReturn();
  }

  String uploaded() throws Exception {
    var response = upload(token, "文件.txt", new byte[] {1, 2, 3}).getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsString()).at("/data/id").asText();
  }

  int remove(String id, String bearer) throws Exception {
    return mvc.perform(
            delete("/api/v1/storage/files/" + id).header("Authorization", "Bearer " + bearer))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  int removeRecord(String id, String bearer) throws Exception {
    var request = delete("/api/v1/storage/files/" + id + "/record");
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    return mvc.perform(request).andReturn().getResponse().getStatus();
  }

  @Test
  void explicitlyRemovingRecordSkipsUnavailableMinioConfigurationAndAuditsDistinctAction()
      throws Exception {
    String id = uploaded();
    // 历史记录仍指向已失效的 MinIO，普通删除失败不能静默丢失索引。
    jdbc.update(
        "UPDATE sys_storage_file SET provider='MINIO',bucket='retired-minio' WHERE id=?", id);
    when(factory.get(StorageProvider.MINIO)).thenThrow(new StorageFailure());
    assertEquals(503, remove(id, token));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_file WHERE tenant_id=?", Long.class, tenant));
    clearInvocations(factory, aliyun, minio);
    assertEquals(200, removeRecord(id, token));
    verifyNoInteractions(factory, aliyun, minio);
    assertEquals(
        1, objects.size(), "Only the local index is removed; remote content was not deleted");
    var listed = categoryList(token, "all", "", 12);
    assertEquals(0, listed.path("total").asInt());
    assertEquals(0, listed.path("totalSize").asInt());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id=? AND action='移除文件记录' AND"
                + " status='成功'",
            Long.class,
            tenant));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id=? AND action='删除' AND"
                + " status='失败' AND error_code='STORAGE_UNAVAILABLE'",
            Long.class,
            tenant));
    assertEquals(404, removeRecord(id, token));
    verifyNoInteractions(factory, aliyun, minio);
  }

  @Test
  void recordOnlyRemovalRetainsDeleteGrantTenantAndOwnerBoundaries() throws Exception {
    String adminFile = uploaded(), memberToken = login("member");
    assertEquals(401, removeRecord(adminFile, null));
    assertEquals(403, removeRecord(adminFile, memberToken));
    jdbc.update(
        "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT tenant_id,?,id FROM sys_menu"
            + " WHERE tenant_id=?",
        memberRole,
        tenant);
    assertEquals(404, removeRecord(adminFile, memberToken));
    String ownFile =
        json.readTree(
                upload(memberToken, "个人文件.txt", new byte[] {8}).getResponse().getContentAsString())
            .at("/data/id")
            .asText();
    clearInvocations(factory, aliyun, minio);
    assertEquals(200, removeRecord(ownFile, memberToken));
    verifyNoInteractions(factory, aliyun, minio);
    jdbc.update("UPDATE sys_storage_file SET tenant_id=? WHERE id=?", otherTenant, adminFile);
    assertEquals(404, removeRecord(adminFile, token));
    assertEquals(404, removeRecord("not-a-file-id", token));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_file WHERE tenant_id=? AND id=?",
            Long.class,
            otherTenant,
            adminFile));
    verifyNoInteractions(factory, aliyun, minio);
  }

  @AfterEach
  void cleanup() {
    for (String id : List.of(tenant, otherTenant)) {
      for (String table :
          List.of(
              "sys_storage_file",
              "sys_login_log",
              "sys_operation_log",
              "sys_user_role",
              "sys_role_menu",
              "sys_user",
              "sys_role",
              "sys_menu")) jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", id);
      jdbc.update("DELETE FROM sys_tenant WHERE id=?", id);
    }
    redis.delete("star:csrf:" + AuthService.hash(cookie.getValue()));
  }

  @Test
  void uploadDownloadDeleteAndAuditUseRealIndexAndExactContent() throws Exception {
    String id = uploaded();
    assertTrue(objects.keySet().iterator().next().contains("/tenants/" + tenant + "/"));
    var downloaded =
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/content")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse();
    assertEquals(200, downloaded.getStatus());
    assertArrayEquals(new byte[] {1, 2, 3}, downloaded.getContentAsByteArray());
    assertTrue(downloaded.getHeader("Content-Disposition").startsWith("attachment;"));
    var list =
        mvc.perform(get("/api/v1/storage/files").header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse();
    assertEquals(1, json.readTree(list.getContentAsString()).at("/data/total").asInt());
    assertFalse(list.getContentAsString().contains("object_key"));
    assertEquals(200, remove(id, token));
    assertTrue(objects.isEmpty());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_file WHERE tenant_id=?", Long.class, tenant));
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id=? AND module='文件管理' AND"
                + " status='成功'",
            Long.class,
            tenant));
  }

  @Test
  void switchingDefaultDoesNotRerouteExistingFilesAndCrossTenantIsHidden() throws Exception {
    String id = uploaded();
    when(factory.current()).thenReturn(minio);
    assertEquals(
        200,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/content")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
    verify(aliyun).open(eq("original-bucket"), anyString());
    verify(minio, never()).open(anyString(), anyString());
    jdbc.update("UPDATE sys_storage_file SET tenant_id=? WHERE id=?", otherTenant, id);
    assertEquals(
        404,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/content")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(404, remove(id, token));
  }

  @Test
  void permissionAndOwnerChecksPreventAccessToOtherUsersFiles() throws Exception {
    String id = uploaded();
    String memberToken = login("member");
    assertEquals(403, upload(memberToken, "test.txt", new byte[] {1}).getResponse().getStatus());
    jdbc.update(
        "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT tenant_id,?,id FROM sys_menu"
            + " WHERE tenant_id=?",
        memberRole,
        tenant);
    assertEquals(
        404,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/content")
                    .header("Authorization", "Bearer " + memberToken))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(404, remove(id, memberToken));
    var list =
        mvc.perform(get("/api/v1/storage/files").header("Authorization", "Bearer " + memberToken))
            .andReturn()
            .getResponse();
    assertEquals(0, json.readTree(list.getContentAsString()).at("/data/total").asInt());
    assertEquals(200, upload(memberToken, "own.txt", new byte[] {5}).getResponse().getStatus());
    assertEquals(
        401, mvc.perform(get("/api/v1/storage/files")).andReturn().getResponse().getStatus());
  }

  @Test
  void playbackRequiresDownloadGrantAndOwnerAndDoesNotOpenWholeObject() throws Exception {
    var id =
        json.readTree(
                upload(token, "培训.mp4", new byte[] {1, 2, 3}).getResponse().getContentAsString())
            .at("/data/id")
            .asText();
    var response =
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/playback")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    assertEquals("no-store", response.getHeader("Cache-Control"));
    assertTrue(
        json.readTree(response.getContentAsString())
            .at("/data/url")
            .asText()
            .startsWith("https://"));
    verify(aliyun, never()).open(anyString(), anyString());
    String memberToken = login("member");
    assertEquals(
        403,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/playback")
                    .header("Authorization", "Bearer " + memberToken))
            .andReturn()
            .getResponse()
            .getStatus());
    jdbc.update(
        "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT tenant_id,?,id FROM sys_menu"
            + " WHERE tenant_id=? AND permission='storage:file:download'",
        memberRole,
        tenant);
    assertEquals(
        404,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/playback")
                    .header("Authorization", "Bearer " + memberToken))
            .andReturn()
            .getResponse()
            .getStatus());
    var textId =
        json.readTree(upload(token, "plain.txt", new byte[] {1}).getResponse().getContentAsString())
            .at("/data/id")
            .asText();
    assertEquals(
        400,
        mvc.perform(
                get("/api/v1/storage/files/" + textId + "/playback")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void categoryQueriesFilterHistoricalNamesBeforePagingAndRespectOwnerScope() throws Exception {
    upload(token, "历史照片.JPG", new byte[] {1, 2, 3, 4});
    upload(token, "报告.pdf", new byte[] {5, 6, 7});
    upload(token, "图标.svg", new byte[] {8, 9});
    upload(token, "培训录像.MP4", new byte[] {1, 2, 3});
    assertEquals(1, categoryList(token, "videos", "培训", 12).path("total").asInt());
    assertEquals(3, categoryList(token, "videos", "", 12).path("totalSize").asInt());
    var images = categoryList(token, "images", "", 1);
    assertEquals(1, images.path("total").asInt());
    assertEquals(4, images.path("totalSize").asInt());
    assertEquals("历史照片.JPG", images.at("/items/0/name").asText());
    var attachments = categoryList(token, "attachments", "", 1);
    assertEquals(2, attachments.path("total").asInt());
    assertEquals(5, attachments.path("totalSize").asInt());
    assertEquals(1, attachments.path("items").size());
    assertEquals(1, categoryList(token, "attachments", "报告", 12).path("total").asInt());
    assertEquals(0, categoryList(token, "images", "报告", 12).path("total").asInt());
    jdbc.update(
        "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT tenant_id,?,id FROM sys_menu"
            + " WHERE tenant_id=?",
        memberRole,
        tenant);
    String memberToken = login("member");
    assertEquals(0, categoryList(memberToken, "images", "", 12).path("total").asInt());
    assertEquals(0, categoryList(memberToken, "videos", "", 12).path("total").asInt());
    upload(memberToken, "个人.png", new byte[] {10});
    assertEquals(1, categoryList(memberToken, "images", "", 12).path("total").asInt());
    assertEquals(2, categoryList(token, "images", "", 12).path("total").asInt());
  }

  JsonNode categoryList(String bearer, String category, String keyword, int pageSize)
      throws Exception {
    var response =
        mvc.perform(
                get("/api/v1/storage/files")
                    .param("category", category)
                    .param("keyword", keyword)
                    .param("pageSize", String.valueOf(pageSize))
                    .header("Authorization", "Bearer " + bearer))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
    return json.readTree(response.getContentAsString()).path("data");
  }

  @Test
  void wrongUploadCategoryIsRejectedBeforeStorageAndUnknownFiltersAreInvalid() throws Exception {
    for (var entry :
        Map.of(
                "report.txt",
                "images",
                "photo.PNG",
                "attachments",
                "clip.mp4",
                "attachments",
                "paper.pdf",
                "videos")
            .entrySet()) {
      var response =
          mvc.perform(
                  multipart("/api/v1/storage/files")
                      .file(
                          new MockMultipartFile(
                              "file", entry.getKey(), "application/octet-stream", new byte[] {1}))
                      .param("category", entry.getValue())
                      .header("Authorization", "Bearer " + token))
              .andReturn()
              .getResponse();
      assertEquals(400, response.getStatus());
    }
    verify(aliyun, never())
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
    assertEquals(0, objects.size());
    var good =
        mvc.perform(
                multipart("/api/v1/storage/files")
                    .file(new MockMultipartFile("file", "photo.PNG", "image/png", new byte[] {1}))
                    .param("category", "images")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse();
    assertEquals(200, good.getStatus());
    assertEquals(
        400,
        mvc.perform(
                get("/api/v1/storage/files")
                    .param("category", "unknown")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void officePreviewUsesDownloadAuthorizationAndIsReadOnly() throws Exception {
    byte[] workbook =
        com.star.tool.excel.ExcelFiles.write(
            "员工信息", List.of("姓名", "部门"), List.of(List.of("李灿", "研发部")));
    var uploaded = upload(token, "员工信息.xlsx", workbook).getResponse();
    assertEquals(200, uploaded.getStatus());
    String id = json.readTree(uploaded.getContentAsString()).at("/data/id").asText();
    var preview =
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/preview")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse();
    assertEquals(200, preview.getStatus(), preview.getContentAsString());
    assertEquals("no-store", preview.getHeader("Cache-Control"));
    assertEquals(
        "spreadsheet", json.readTree(preview.getContentAsString()).at("/data/kind").asText());
    assertTrue(preview.getContentAsString().contains("李灿"));
    String memberToken = login("member");
    assertEquals(
        403,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/preview")
                    .header("Authorization", "Bearer " + memberToken))
            .andReturn()
            .getResponse()
            .getStatus());
    jdbc.update(
        "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT tenant_id,?,id FROM sys_menu"
            + " WHERE tenant_id=? AND permission='storage:file:download'",
        memberRole,
        tenant);
    assertEquals(
        404,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/preview")
                    .header("Authorization", "Bearer " + memberToken))
            .andReturn()
            .getResponse()
            .getStatus());
    jdbc.update("UPDATE sys_storage_file SET created_by=? WHERE id=?", member, id);
    assertEquals(
        200,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/preview")
                    .header("Authorization", "Bearer " + memberToken))
            .andReturn()
            .getResponse()
            .getStatus());
    jdbc.update("UPDATE sys_storage_file SET tenant_id=? WHERE id=?", otherTenant, id);
    assertEquals(
        404,
        mvc.perform(
                get("/api/v1/storage/files/" + id + "/preview")
                    .header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id=?", Long.class, tenant));
  }

  @Test
  void failedStorageDoesNotCreateIndexOrLoseExistingIndex() throws Exception {
    doThrow(new StorageFailure())
        .when(aliyun)
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
    assertEquals(503, upload(token, "test.txt", new byte[] {1}).getResponse().getStatus());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_file WHERE tenant_id=?", Long.class, tenant));
    assertEquals(400, upload(token, "empty.txt", new byte[] {}).getResponse().getStatus());
    doAnswer(call -> null)
        .when(aliyun)
        .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
    String id = uploaded();
    doThrow(new StorageFailure()).when(aliyun).delete(anyString(), anyString());
    assertEquals(503, remove(id, token));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_file WHERE tenant_id=?", Long.class, tenant));
  }
}
