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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** 使用独立临时租户验证真实 MySQL/Redis 监控、会话隔离与强退，不操作实际用户。 */
@SpringBootTest
@AutoConfigureMockMvc
class MonitorIT {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper json;
  @Autowired BCryptPasswordEncoder encoder;
  String tenant,other,code,member,memberRole;
  Cookie csrfCookie;String csrf;
  Login admin;
  private record Login(String token,Cookie refresh) {}

  @BeforeEach void setup() throws Exception {
    tenant=IdWorker.getIdStr();other=IdWorker.getIdStr();code="monitor_it_"+tenant;
    jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'监控测试'),(?,?,'隔离测试')",tenant,code,other,"monitor_it_"+other);
    String hash=encoder.encode("MonitorTest2026");
    for(String t:List.of(tenant,other)){
      String role=IdWorker.getIdStr(),user=IdWorker.getIdStr();
      jdbc.update("INSERT INTO sys_role(id,tenant_id,name,code,scope,builtin) VALUES(?,?,'管理员','admin','全部数据',1)",role,t);
      jdbc.update("INSERT INTO sys_user(id,tenant_id,username,name,password_hash,builtin) VALUES(?,?,'admin','测试管理员',?,1)",user,t,hash);
      jdbc.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",t,user,role);
      for(String permission:List.of("monitor:server:read","monitor:cache:read","monitor:session:list","monitor:session:kick"))
        jdbc.update("INSERT INTO sys_menu(id,tenant_id,name,type,permission) VALUES(?,?,'测试权限','按钮',?)",IdWorker.getIdStr(),t,permission);
    }
    member=IdWorker.getIdStr();memberRole=IdWorker.getIdStr();
    jdbc.update("INSERT INTO sys_role(id,tenant_id,name,code,scope) VALUES(?,?,'成员','member','仅本人')",memberRole,tenant);
    jdbc.update("INSERT INTO sys_user(id,tenant_id,username,name,password_hash) VALUES(?,?,'member','测试成员',?)",member,tenant,hash);
    jdbc.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",tenant,member,memberRole);
    var response=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
    csrf=json.readTree(response.getContentAsString()).at("/data/token").asText();
    csrfCookie=cookie(response,"star_csrf");admin=login(code,"admin");
  }
  private Cookie cookie(MockHttpServletResponse res,String name){
    for(String header:res.getHeaders("Set-Cookie"))if(header.startsWith(name+"="))return new Cookie(name,header.substring(name.length()+1,header.indexOf(';')));
    throw new AssertionError("Missing cookie");
  }
  private Login login(String tenantCode,String username)throws Exception{
    var res=mvc.perform(post("/api/v1/auth/login").cookie(csrfCookie).header("X-CSRF-Token",csrf)
        .header("Origin","http://127.0.0.1:5173").contentType("application/json")
        .content(json.writeValueAsBytes(Map.of("tenantCode",tenantCode,"username",username,"password","MonitorTest2026"))))
        .andReturn().getResponse();
    assertEquals(200,res.getStatus(),res.getContentAsString());
    return new Login(json.readTree(res.getContentAsString()).at("/data/accessToken").asText(),cookie(res,"star_refresh"));
  }
  private MockHttpServletResponse getMonitor(String path,String token)throws Exception{
    return mvc.perform(get("/api/v1/monitor/"+path).header("Authorization","Bearer "+token)).andReturn().getResponse();
  }
  private JsonNode list()throws Exception{return json.readTree(getMonitor("sessions",admin.token).getContentAsString()).path("data");}

  @Test void realMetricsRequirePermissionAndDoNotExposeSecrets()throws Exception{
    assertEquals(401,mvc.perform(get("/api/v1/monitor/server")).andReturn().getResponse().getStatus());
    var unprivileged=login(code,"member");
    for(String path:List.of("server","cache","sessions"))assertEquals(403,getMonitor(path,unprivileged.token).getStatus());
    var server=getMonitor("server",admin.token);assertEquals(200,server.getStatus(),server.getContentAsString());
    var data=json.readTree(server.getContentAsString()).path("data");
    assertTrue(data.path("cores").asInt()>0);assertTrue(data.at("/current/heapUsed").asLong()>0);
    assertTrue(data.path("history").size()>0);assertEquals("no-store",server.getHeader("Cache-Control"));
    var cache=getMonitor("cache",admin.token);assertEquals(200,cache.getStatus(),cache.getContentAsString());
    assertTrue(json.readTree(cache.getContentAsString()).at("/data/current/memoryUsed").asLong()>0);
    assertFalse((server.getContentAsString()+cache.getContentAsString()).contains("password"));
    assertEquals(400,getMonitor("sessions?page=0",admin.token).getStatus());
  }
  @Test void onlineSessionsAreTenantScopedAndKickRevokesOnlySelectedFamily()throws Exception{
    var first=login(code,"member");
    var firstRows=list().path("records");String target="";
    for(var row:firstRows)if(row.path("username").asText().equals("member"))target=row.path("id").asText();
    assertFalse(target.isEmpty());
    var second=login(code,"member");var otherLogin=login("monitor_it_"+other,"admin");
    var local=list();assertEquals(3,local.path("total").asInt());
    String serialized=local.toString();assertFalse(serialized.contains(first.token));assertFalse(serialized.contains("family"));
    var otherRows=json.readTree(getMonitor("sessions",otherLogin.token).getContentAsString()).at("/data/records");
    String foreign=otherRows.get(0).path("id").asText();
    assertEquals(404,mvc.perform(post("/api/v1/monitor/sessions/"+foreign+"/kick").header("Authorization","Bearer "+admin.token)).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(post("/api/v1/monitor/sessions/"+target+"/kick").header("Authorization","Bearer "+admin.token)).andReturn().getResponse().getStatus());
    assertEquals(401,getMonitor("sessions",first.token).getStatus());
    // 未获得监控权限的第二次登录仍有效，返回403而非会话失效401。
    assertEquals(403,getMonitor("sessions",second.token).getStatus());
    assertEquals(401,mvc.perform(post("/api/v1/auth/refresh").cookie(csrfCookie,first.refresh).header("X-CSRF-Token",csrf).header("Origin","http://127.0.0.1:5173")).andReturn().getResponse().getStatus());
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id=? AND action='强退会话' AND status='成功'",Integer.class,tenant));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND id=?",Integer.class,tenant,member));
  }
  @Test void logoutAndPasswordVersionChangesRemoveOnlineUsers()throws Exception{
    var memberLogin=login(code,"member");assertEquals(2,list().path("total").asInt());
    mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+memberLogin.token).cookie(csrfCookie,memberLogin.refresh).header("X-CSRF-Token",csrf).header("Origin","http://127.0.0.1:5173"));
    assertEquals(1,list().path("total").asInt());
    login(code,"member");assertEquals(2,list().path("total").asInt());
    jdbc.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE tenant_id=? AND id=?",tenant,member);
    assertEquals(1,list().path("total").asInt());
    String current=list().at("/records/0/id").asText();
    assertEquals(409,mvc.perform(post("/api/v1/monitor/sessions/"+current+"/kick").header("Authorization","Bearer "+admin.token)).andReturn().getResponse().getStatus());
  }

  @AfterEach void cleanup(){
    for(String t:List.of(tenant,other)){
      var ids=redis.opsForZSet().range("star:online-index:"+t,0,-1);
      if(ids!=null)for(String id:ids)redis.delete(List.of("star:online:"+t+":"+id,"star:online-touch:"+t+":"+id));
      redis.delete("star:online-index:"+t);
      for(String table:List.of("sys_login_log","sys_operation_log","sys_user_role","sys_role_menu","sys_user","sys_role","sys_menu"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",t);
      jdbc.update("DELETE FROM sys_tenant WHERE id=?",t);
    }
    if(csrfCookie!=null)redis.delete("star:csrf:"+AuthService.hash(csrfCookie.getValue()));
  }
}
