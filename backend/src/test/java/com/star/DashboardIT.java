package com.star;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** 真实数据库下验证 PV 去重、UV 账号去重、时间区间、权限和跨租户隔离。 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardIT {
  @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper json;@Autowired BCryptPasswordEncoder encoder;
  String tenant,other,adminId,memberId,admin,member,foreign;
  Cookie cookie;String csrf;final List<String> events=new ArrayList<>();
  @BeforeEach void setup()throws Exception{
    tenant=IdWorker.getIdStr();other=IdWorker.getIdStr();String hash=encoder.encode("DashboardTest2026");
    for(String t:List.of(tenant,other)){
      jdbc.update("INSERT INTO sys_tenant(id,code,name) VALUES(?,?,'首页测试')",t,"home_it_"+t);
      String role=IdWorker.getIdStr(),user=IdWorker.getIdStr();if(t.equals(tenant))adminId=user;
      jdbc.update("INSERT INTO sys_role(id,tenant_id,name,code,scope,builtin) VALUES(?,?,'管理员','admin','全部数据',1)",role,t);
      jdbc.update("INSERT INTO sys_user(id,tenant_id,name,username,password_hash,builtin) VALUES(?,?,'管理员','admin',?,1)",user,t,hash);
      jdbc.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",t,user,role);
      for(String p:List.of("dashboard:read","audit:login:list","audit:operation:list"))jdbc.update("INSERT INTO sys_menu(id,tenant_id,name,type,permission) VALUES(?,?,'测试','按钮',?)",IdWorker.getIdStr(),t,p);
    }
    memberId=IdWorker.getIdStr();String role=IdWorker.getIdStr();
    jdbc.update("INSERT INTO sys_role(id,tenant_id,name,code,scope) VALUES(?,?,'成员','member','仅本人')",role,tenant);
    jdbc.update("INSERT INTO sys_user(id,tenant_id,name,username,password_hash) VALUES(?,?,'成员','member',?)",memberId,tenant,hash);
    jdbc.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",tenant,memberId,role);
    jdbc.update("INSERT INTO sys_role_menu(tenant_id,role_id,menu_id) SELECT tenant_id,?,id FROM sys_menu WHERE tenant_id=? AND permission='dashboard:read'",role,tenant);
    var res=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();csrf=json.readTree(res.getContentAsString()).at("/data/token").asText();String h=res.getHeader("Set-Cookie");cookie=new Cookie("star_csrf",h.substring(h.indexOf('=')+1,h.indexOf(';')));
    admin=login(tenant,"admin");member=login(tenant,"member");foreign=login(other,"admin");
  }
  String login(String t,String username)throws Exception{var r=mvc.perform(post("/api/v1/auth/login").cookie(cookie).header("X-CSRF-Token",csrf).header("Origin","http://127.0.0.1:5173").contentType("application/json").content(json.writeValueAsBytes(Map.of("tenantCode","home_it_"+t,"username",username,"password","DashboardTest2026")))).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());return json.readTree(r.getContentAsString()).at("/data/accessToken").asText();}
  void visit(String token,String event)throws Exception{events.add(event);var r=mvc.perform(post("/api/v1/dashboard/visit").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsBytes(Map.of("eventId",event)))).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());}
  JsonNode stats(String token,int days)throws Exception{var r=mvc.perform(get("/api/v1/dashboard/analytics").param("days",String.valueOf(days)).header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());return json.readTree(r.getContentAsString()).path("data");}
  @Test void pageViewsAreIdempotentAndUvUsesAccountsAcrossSessionsAndTenants()throws Exception{
    String event=UUID.randomUUID().toString();visit(admin,event);visit(admin,event);
    visit(admin,UUID.randomUUID().toString());visit(member,UUID.randomUUID().toString());
    String anotherSession=login(tenant,"member");visit(anotherSession,UUID.randomUUID().toString());
    visit(foreign,UUID.randomUUID().toString());
    var all=stats(admin,7);assertEquals(4,all.path("pv").asInt());assertEquals(2,all.path("uv").asInt());assertEquals(7,all.path("trend").size());assertEquals("Asia/Shanghai",all.path("timezone").asText());assertEquals("tenant",all.path("visitScope").asText());
    var self=stats(member,7);assertEquals(2,self.path("pv").asInt());assertEquals(1,self.path("uv").asInt());assertEquals("self",self.path("visitScope").asText());
    assertEquals(1,stats(foreign,7).path("pv").asInt());
    assertFalse(all.toString().contains("visitor_hash"));
  }
  @Test void rangesIncludeZeroDaysAndUvIsNotSummedPerDay()throws Exception{
    LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));String visitor=AuthService.hash(tenant+":"+adminId);
    jdbc.update("INSERT INTO sys_visit_daily(tenant_id,stat_date,visitor_hash,pv) VALUES(?,?,?,2),(?,?,?,3),(?,?,?,9)",tenant,today,visitor,tenant,today.minusDays(6),visitor,tenant,today.minusDays(7),visitor);
    var seven=stats(admin,7);assertEquals(5,seven.path("pv").asInt());assertEquals(1,seven.path("uv").asInt());
    assertEquals(today.minusDays(6).toString(),seven.at("/trend/0/date").asText());assertEquals(0,seven.at("/trend/1/pv").asInt());
    assertEquals(14,stats(admin,30).path("pv").asInt());assertEquals(90,stats(admin,90).path("trend").size());
  }
  @Test void activitiesAndDistributionRespectPermissionsAndInvalidRangesAreRejected()throws Exception{
    for(String user:List.of(adminId,memberId))jdbc.update("INSERT INTO sys_operation_log(id,tenant_id,user_id,display_name,module,action,status,http_status) VALUES(?,?,?,'测试操作人',?,'新增','成功',200)",IdWorker.getIdStr(),tenant,user,user.equals(adminId)?"系统管理":"文件管理");
    assertEquals(2,stats(admin,7).path("operationTotal").asInt());assertEquals(1,stats(member,7).path("operationTotal").asInt());
    var r=mvc.perform(get("/api/v1/dashboard/activities").header("Authorization","Bearer "+member)).andReturn().getResponse();assertEquals(200,r.getStatus());var result=json.readTree(r.getContentAsString()).path("data");assertEquals(1,result.path("total").asInt());assertEquals("文件管理",result.at("/records/0/resource").asText());
    assertEquals(400,mvc.perform(get("/api/v1/dashboard/analytics?days=8").header("Authorization","Bearer "+admin)).andReturn().getResponse().getStatus());
    assertEquals(401,mvc.perform(post("/api/v1/dashboard/visit").contentType("application/json").content("{\"eventId\":\"invalid\"}")).andReturn().getResponse().getStatus());
    assertEquals(400,mvc.perform(post("/api/v1/dashboard/visit").header("Authorization","Bearer "+admin).contentType("application/json").content("{\"eventId\":\"invalid\"}")).andReturn().getResponse().getStatus());
    jdbc.update("DELETE FROM sys_role_menu WHERE tenant_id=?",tenant);
    assertEquals(403,mvc.perform(get("/api/v1/dashboard/analytics").header("Authorization","Bearer "+member)).andReturn().getResponse().getStatus());
  }
  @AfterEach void cleanup(){for(String t:List.of(tenant,other)){for(String event:events)redis.delete("star:visit-event:"+t+":"+event);var ids=redis.opsForZSet().range("star:online-index:"+t,0,-1);if(ids!=null)for(String id:ids)redis.delete(List.of("star:online:"+t+":"+id,"star:online-touch:"+t+":"+id));redis.delete("star:online-index:"+t);for(String table:List.of("sys_visit_daily","sys_login_log","sys_operation_log","sys_user_role","sys_role_menu","sys_user","sys_role","sys_menu"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",t);jdbc.update("DELETE FROM sys_tenant WHERE id=?",t);}if(cookie!=null)redis.delete("star:csrf:"+AuthService.hash(cookie.getValue()));}
}
