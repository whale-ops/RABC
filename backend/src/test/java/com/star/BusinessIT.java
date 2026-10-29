package com.star;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 真实 MySQL / Redis 下验证订单库存事务、并发超卖、幂等、金额快照与租户/角色隔离。 */
@SpringBootTest
@AutoConfigureMockMvc
class BusinessIT {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired BCryptPasswordEncoder encoder;
  @Autowired StringRedisTemplate redis;
  String tenant, other, adminId, memberId, admin, member, foreign;
  Cookie cookie;
  String csrf;
  final String base = "/api/v1/business";
  final List<String> grants =
      List.of(
          "business:product:list",
          "business:product:read",
          "business:product:create",
          "business:product:update",
          "business:product:status",
          "business:product:delete",
          "business:order:list",
          "business:order:read",
          "business:order:create",
          "business:order:update",
          "business:order:cancel",
          "business:order:pay",
          "business:order:ship",
          "business:order:complete");

  @BeforeEach
  void setup() throws Exception {
    tenant = IdWorker.getIdStr();
    other = IdWorker.getIdStr();
    String hash = encoder.encode("BusinessTest2026");
    for (String t : List.of(tenant, other)) {
      jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'业务测试')", t, "biz_it_" + t);
      String role = IdWorker.getIdStr(), user = IdWorker.getIdStr();
      if (t.equals(tenant)) adminId = user;
      jdbc.update(
          "INSERT INTO sys_role(id,tenant_id,name,code,scope,builtin)"
              + " VALUES(?,?,'管理员','admin','全部数据',1)",
          role,
          t);
      jdbc.update(
          "INSERT INTO sys_user(id,tenant_id,name,username,password_hash,builtin)"
              + " VALUES(?,?,'管理员','admin',?,1)",
          user,
          t,
          hash);
      jdbc.update(
          "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)", t, user, role);
      for (String grant : grants)
        jdbc.update(
            "INSERT INTO sys_menu(id,tenant_id,name,type,permission) VALUES(?,?,'测试','按钮',?)",
            IdWorker.getIdStr(),
            t,
            grant);
    }
    memberId = IdWorker.getIdStr();
    String role = IdWorker.getIdStr();
    jdbc.update(
        "INSERT INTO sys_role(id,tenant_id,name,code,scope) VALUES(?,?,'成员','member','仅本人')",
        role,
        tenant);
    jdbc.update(
        "INSERT INTO sys_user(id,tenant_id,name,username,password_hash)"
            + " VALUES(?,?,'成员','member',?)",
        memberId,
        tenant,
        hash);
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
        tenant,
        memberId,
        role);
    for (String grant :
        List.of(
            "business:product:list",
            "business:order:list",
            "business:order:read",
            "business:order:create",
            "business:order:update",
            "business:order:cancel"))
      jdbc.update(
          "INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT tenant_id,?,id FROM sys_menu"
              + " WHERE tenant_id=? AND permission=?",
          role,
          tenant,
          grant);
    var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
    csrf = json.readTree(response.getContentAsString()).at("/data/token").asText();
    String h = response.getHeader("Set-Cookie");
    cookie = new Cookie("star_csrf", h.substring(h.indexOf('=') + 1, h.indexOf(';')));
    admin = login(tenant, "admin");
    member = login(tenant, "member");
    foreign = login(other, "admin");
  }

  String login(String t, String username) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/auth/login")
                    .cookie(cookie)
                    .header("X-CSRF-Token", csrf)
                    .header("Origin", "http://127.0.0.1:5173")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "tenantCode",
                                "biz_it_" + t,
                                "username",
                                username,
                                "password",
                                "BusinessTest2026"))))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    return json.readTree(response.getContentAsString()).at("/data/accessToken").asText();
  }

  JsonNode call(String token, MockHttpServletRequestBuilder request, Object input, int expected)
      throws Exception {
    request.header("Authorization", "Bearer " + token);
    if (input != null)
      request.contentType("application/json").content(json.writeValueAsBytes(input));
    var response = mvc.perform(request).andReturn().getResponse();
    assertEquals(expected, response.getStatus(), response.getContentAsString());
    return response.getContentAsByteArray().length == 0
        ? json.nullNode()
        : json.readTree(response.getContentAsString()).path("data");
  }

  Map<String, Object> productInput(String sku, String price, int stock, Long version) {
    var input = new LinkedHashMap<String, Object>();
    input.put("sku", sku);
    input.put("name", "测试产品 " + sku);
    input.put("category", "办公");
    input.put("price", price);
    input.put("stock", stock);
    input.put("description", "仅用于隔离测试");
    if (version != null) input.put("version", version);
    return input;
  }

  JsonNode product(String sku, String price, int stock) throws Exception {
    var row = call(admin, post(base + "/products"), productInput(sku, price, stock, null), 201);
    return call(
        admin,
        patch(base + "/products/" + row.path("id").asText() + "/status"),
        Map.of("version", 0, "status", "上架"),
        200);
  }

  Map<String, Object> order(String product, int quantity) {
    return new LinkedHashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID().toString(),
            "customer",
            "隔离客户",
            "phone",
            "13800000000",
            "address",
            "测试地址",
            "remark",
            "测试备注",
            "items",
            List.of(Map.of("productId", product, "quantity", quantity))));
  }

  JsonNode detail(String id) throws Exception {
    return call(admin, get(base + "/orders/" + id), null, 200);
  }

  int stock(String id) {
    return jdbc.queryForObject(
        "SELECT stock FROM biz_product WHERE tenant_id=? AND id=?", Integer.class, tenant, id);
  }

  @Test
  void productValidationOptimisticVersionAndTenantIsolation() throws Exception {
    var product = product("SKU-A", "12.50", 7);
    String id = product.path("id").asText();
    call(admin, post(base + "/products"), productInput("sku-a", "1.00", 2, null), 409);
    call(admin, post(base + "/products"), productInput("BAD", "-1", 1, null), 400);
    call(admin, patch(base + "/products/" + id), productInput("SKU-A", "9.50", 7, 0L), 409);
    call(foreign, get(base + "/products/" + id), null, 404);
    call(member, post(base + "/products"), productInput("DENIED", "1", 1, null), 403);
    var changed =
        call(admin, patch(base + "/products/" + id), productInput("SKU-A", "9.50", 9, 1L), 200);
    assertEquals("9.50", changed.path("price").asText());
    assertEquals(9, stock(id));
    assertEquals(
        1,
        call(admin, get(base + "/products?status=上架&category=办公"), null, 200)
            .path("total")
            .asInt());
    call(
        admin,
        delete(base + "/products/" + id),
        Map.of("version", changed.path("version").asLong()),
        204);
    call(admin, get(base + "/products/" + id), null, 404);
  }

  @Test
  void pricesAreServerCalculatedAndRetryDoesNotDeductStockAgain() throws Exception {
    var p = product("PRICE", "0.10", 10);
    String pid = p.path("id").asText();
    var input = order(pid, 3);
    var result = call(admin, post(base + "/orders"), input, 201);
    String id = result.path("id").asText();
    assertEquals(id, call(admin, post(base + "/orders"), input, 201).path("id").asText());
    assertEquals(7, stock(pid));
    var snapshot = detail(id);
    assertEquals("0.30", snapshot.path("total").asText());
    assertEquals("0.10", snapshot.at("/items/0/price").asText());
    call(admin, patch(base + "/products/" + pid), productInput("PRICE", "9.99", 7, 2L), 200);
    assertEquals("0.30", detail(id).path("total").asText());
    input.put("customer", "另一客户");
    call(admin, post(base + "/orders"), input, 409);
    var forged = order(pid, 1);
    forged.put("total", "0.01");
    call(admin, post(base + "/orders"), forged, 400);
    call(admin, delete(base + "/products/" + pid), Map.of("version", 3), 409);
    assertTrue(
        jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id=? AND module='订单管理' AND"
                    + " action='新增' AND status='成功'",
                Integer.class,
                tenant)
            > 0);
  }

  @Test
  void transitionsRequireCurrentStateAndRecordTimestamps() throws Exception {
    String pid = product("FLOW", "10.25", 5).path("id").asText();
    String id = call(admin, post(base + "/orders"), order(pid, 2), 201).path("id").asText();
    call(
        admin,
        post(base + "/orders/" + id + "/ship"),
        Map.of("version", 0, "trackingNo", "TRACK-1"),
        409);
    call(admin, post(base + "/orders/" + id + "/pay"), Map.of("version", 0), 204);
    assertEquals("待发货", detail(id).path("status").asText());
    call(admin, post(base + "/orders/" + id + "/cancel"), Map.of("version", 1), 409);
    call(
        admin,
        post(base + "/orders/" + id + "/ship"),
        Map.of("version", 1, "trackingNo", " "),
        400);
    call(
        admin,
        post(base + "/orders/" + id + "/ship"),
        Map.of("version", 1, "trackingNo", "TRACK-1"),
        204);
    call(admin, post(base + "/orders/" + id + "/complete"), Map.of("version", 2), 204);
    var finalOrder = detail(id);
    assertEquals("已完成", finalOrder.path("status").asText());
    assertEquals("TRACK-1", finalOrder.path("trackingNo").asText());
    assertFalse(finalOrder.path("paidAt").isNull());
    assertFalse(finalOrder.path("shippedAt").isNull());
    assertFalse(finalOrder.path("completedAt").isNull());
    assertEquals(3, stock(pid));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id=? AND module='订单管理' AND"
                + " action='订单发货' AND status='成功'",
            Integer.class,
            tenant));
  }

  @Test
  void cancelReturnsInventoryOnceAndRejectsStaleEdits() throws Exception {
    String pid = product("CANCEL", "2.10", 10).path("id").asText();
    String id = call(admin, post(base + "/orders"), order(pid, 4), 201).path("id").asText();
    assertEquals(6, stock(pid));
    call(
        admin,
        patch(base + "/orders/" + id),
        Map.of("version", 0, "customer", "修改客户", "phone", "", "address", "", "remark", "已更新"),
        204);
    call(admin, post(base + "/orders/" + id + "/cancel"), Map.of("version", 0), 409);
    call(admin, post(base + "/orders/" + id + "/cancel"), Map.of("version", 1), 204);
    assertEquals(10, stock(pid));
    call(admin, post(base + "/orders/" + id + "/cancel"), Map.of("version", 2), 409);
    assertEquals(10, stock(pid));
    assertEquals("已取消", detail(id).path("status").asText());
    assertEquals(
        0,
        new java.math.BigDecimal(
                call(admin, get(base + "/orders"), null, 200).at("/summary/amount").asText())
            .signum());
  }

  @Test
  void concurrentOrdersCannotOversellAndFailedMultiItemOrderLeavesInventoryUnchanged()
      throws Exception {
    String pid = product("LAST", "1.01", 1).path("id").asText();
    var tasks = new ArrayList<CompletableFuture<Integer>>();
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 2; i++)
        tasks.add(
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return mvc.perform(
                            post(base + "/orders")
                                .header("Authorization", "Bearer " + admin)
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(order(pid, 1))))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                  } catch (Exception e) {
                    throw new CompletionException(e);
                  }
                },
                pool));
      var statuses = tasks.stream().map(CompletableFuture::join).sorted().toList();
      assertEquals(List.of(201, 409), statuses);
      assertEquals(0, stock(pid));
    } finally {
      pool.shutdownNow();
    }
    String otherProduct = product("ENOUGH", "3.00", 10).path("id").asText();
    var input = order(otherProduct, 2);
    input.put(
        "items",
        List.of(
            Map.of("productId", otherProduct, "quantity", 2),
            Map.of("productId", pid, "quantity", 1)));
    call(admin, post(base + "/orders"), input, 409);
    assertEquals(10, stock(otherProduct));
  }

  @Test
  void rolesSeeOnlyOwnOrdersAndCannotReadOrMutateAnotherTenant() throws Exception {
    String pid = product("SCOPED", "5.00", 10).path("id").asText();
    String adminOrder = call(admin, post(base + "/orders"), order(pid, 1), 201).path("id").asText();
    String memberOrder =
        call(member, post(base + "/orders"), order(pid, 1), 201).path("id").asText();
    assertEquals(1, call(member, get(base + "/orders"), null, 200).path("total").asInt());
    call(member, get(base + "/orders/" + adminOrder), null, 404);
    call(member, post(base + "/orders/" + adminOrder + "/cancel"), Map.of("version", 0), 404);
    call(member, post(base + "/orders/" + memberOrder + "/pay"), Map.of("version", 0), 403);
    call(foreign, get(base + "/orders/" + memberOrder), null, 404);
    call(foreign, post(base + "/orders"), order(pid, 1), 404);
    assertEquals(0, call(foreign, get(base + "/products/choices"), null, 200).size());
    var options = call(member, get(base + "/products/choices"), null, 200);
    assertEquals(1, options.size());
    assertFalse(options.toString().contains("description"));
    call(member, post(base + "/orders/" + memberOrder + "/cancel"), Map.of("version", 0), 204);
    jdbc.update("DELETE FROM sys_role_menu WHERE tenant_id=?", tenant);
    call(member, get(base + "/orders"), null, 403);
    assertEquals(401, mvc.perform(get(base + "/orders")).andReturn().getResponse().getStatus());
  }

  @Test
  void departmentScopeAndProductPaginationAreEnforced() throws Exception {
    String deptA = IdWorker.getIdStr(),
        deptB = IdWorker.getIdStr(),
        colleague = IdWorker.getIdStr();
    for (String dept : List.of(deptA, deptB))
      jdbc.update(
          "INSERT INTO sys_dept(id,tenant_id,name,sort) VALUES(?,?,?,0)", dept, tenant, "隔离部门"+dept);
    jdbc.update("UPDATE sys_user SET dept_id=? WHERE tenant_id=? AND id=?", deptA, tenant, adminId);
    jdbc.update(
        "UPDATE sys_user SET dept_id=? WHERE tenant_id=? AND id=?", deptB, tenant, memberId);
    jdbc.update("UPDATE sys_role SET scope='本部门' WHERE tenant_id=? AND code='member'", tenant);
    jdbc.update(
        "INSERT INTO sys_user(id,tenant_id,name,username,password_hash,dept_id) SELECT"
            + " ?,tenant_id,'同事','colleague',password_hash,? FROM sys_user WHERE tenant_id=? AND"
            + " id=?",
        colleague,
        deptB,
        tenant,
        memberId);
    jdbc.update(
        "INSERT INTO sys_user_role(tenant_id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE"
            + " tenant_id=? AND code='member'",
        tenant,
        colleague,
        tenant);
    String colleagueToken = login(tenant, "colleague");
    String pid = product("DEPT_ONE", "12.00", 10).path("id").asText();
    product("DEPT_TWO", "20.00", 3);
    product("DEPT_THREE", "30.00", 2);
    var first = call(admin, get(base + "/products?page=1&size=1"), null, 200);
    var second = call(admin, get(base + "/products?page=2&size=1"), null, 200);
    assertEquals(3, first.path("total").asInt());
    assertEquals(1, first.path("records").size());
    assertNotEquals(first.at("/records/0/id").asText(), second.at("/records/0/id").asText());
    assertEquals(
        1,
        call(admin, get(base + "/products").param("keyword", "DEPT_ONE"), null, 200)
            .path("total")
            .asInt());
    String otherDepartmentOrder =
        call(admin, post(base + "/orders"), order(pid, 1), 201).path("id").asText();
    call(member, post(base + "/orders"), order(pid, 1), 201);
    String colleagueOrder =
        call(colleagueToken, post(base + "/orders"), order(pid, 1), 201).path("id").asText();
    assertEquals(2, call(member, get(base + "/orders"), null, 200).path("total").asInt());
    call(member, get(base + "/orders/" + otherDepartmentOrder), null, 404);
    call(member, get(base + "/orders/" + colleagueOrder), null, 200);
    call(member, post(base + "/orders/" + colleagueOrder + "/cancel"), Map.of("version", 0), 204);
  }

  @AfterEach
  void cleanup() {
    for (String t : List.of(tenant, other)) {
      var ids = redis.opsForZSet().range("star:online-index:" + t, 0, -1);
      if (ids != null)
        for (String id : ids)
          redis.delete(List.of("star:online:" + t + ":" + id, "star:online-touch:" + t + ":" + id));
      redis.delete("star:online-index:" + t);
      for (String table :
          List.of(
              "biz_order_item",
              "biz_order",
              "biz_product",
              "sys_login_log",
              "sys_operation_log",
              "sys_user_role",
              "sys_role_menu",
              "sys_user",
              "sys_role",
              "sys_menu",
              "sys_dept")) jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", t);
      jdbc.update("DELETE FROM sys_tenant WHERE id=?", t);
    }
    if (cookie != null) redis.delete("star:csrf:" + AuthService.hash(cookie.getValue()));
  }
}
