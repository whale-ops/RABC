package com.star.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.auth.service.AuthService;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.monitor.model.OnlineSessionPage;
import com.star.system.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// 包职责：按租户跟踪真实活跃会话，提供分页及会话撤销，不把登录凭据发送给浏览器。
// 模块联系：AuthService 在登录、认证和退出时维护；MonitorController 结合 RBAC 查询和强退。

/** Redis 保存十五分钟活跃窗口；家族摘要作为公开会话编号，原始家族只用于服务端撤销。 */
@Service
public class OnlineSessionRegistry {
  private static final long WINDOW_MS = 900_000;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private record Stored(String userId, int authVersion, String family, long loginAt,
      long lastSeen, String ip, String browser, String os) {}

  public OnlineSessionRegistry(StringRedisTemplate redis, ObjectMapper json, JdbcTemplate jdbc,
      PermissionService permissions) {
    this.redis=redis; this.json=json; this.jdbc=jdbc; this.permissions=permissions;
  }
  private String index(String tenant) { return "star:online-index:"+tenant; }
  private String key(String tenant, String id) { return "star:online:"+tenant+":"+id; }
  private String throttle(String tenant, String id) { return "star:online-touch:"+tenant+":"+id; }

  /** 每会话最多十五秒写入一次，刷新访问令牌不会重复创建一行在线用户。 */
  public void touch(User user, String family) {
    if(family==null || family.isEmpty()) return;
    String id=AuthService.hash(family), tenant=user.tenantId;
    if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(throttle(tenant,id),"1",Duration.ofSeconds(15)))) return;
    Stored old=read(redis.opsForValue().get(key(tenant,id)));
    var attributes=RequestContextHolder.getRequestAttributes();
    HttpServletRequest request=attributes instanceof ServletRequestAttributes a ? a.getRequest():null;
    String agent=request==null ? "" : Objects.toString(request.getHeader("User-Agent"),"");
    String ip=request==null ? "" : request.getRemoteAddr();
    long now=System.currentTimeMillis();
    Stored stored=new Stored(user.id,user.authVersion,family,old==null?now:old.loginAt,now,
        ip.length()>64?ip.substring(0,64):ip,browser(agent),os(agent));
    try {
      redis.opsForValue().set(key(tenant,id),json.writeValueAsString(stored),Duration.ofSeconds(900));
      redis.opsForZSet().add(index(tenant),id,now);
      redis.expire(index(tenant),Duration.ofMinutes(30));
    } catch(JsonProcessingException invalid) {throw new IllegalStateException("在线会话序列化失败");}
  }
  public void remove(String tenant, String family) {
    if(family==null) return;
    String id=AuthService.hash(family);
    redis.delete(List.of(key(tenant,id),throttle(tenant,id)));
    redis.opsForZSet().remove(index(tenant),id);
  }

  /** 只扫描本租户的有界在线索引，不使用 Redis KEYS，不读取其他租户会话。 */
  public OnlineSessionPage list(String keyword,int page,int size,String currentFamily) {
    permissions.require("monitor:session:list");
    if(page<1 || size<1 || size>100 || keyword.length()>100) throw BusinessException.bad("分页或查询条件不合法");
    String tenant=Actor.tenant(); long cutoff=System.currentTimeMillis()-WINDOW_MS;
    redis.opsForZSet().removeRangeByScore(index(tenant),0,cutoff);
    Long cardinality=redis.opsForZSet().zCard(index(tenant));
    var ids=redis.opsForZSet().reverseRangeByScore(index(tenant),cutoff,Double.POSITIVE_INFINITY,0,2000);
    if(ids==null || ids.isEmpty()) return new OnlineSessionPage(List.of(),0,false);
    var ordered=new ArrayList<>(ids);
    var values=redis.opsForValue().multiGet(ordered.stream().map(id->key(tenant,id)).toList());
    var stored=new LinkedHashMap<String,Stored>();
    if(values!=null) for(int i=0;i<values.size();i++) {var s=read(values.get(i));if(s!=null && s.lastSeen>=cutoff)stored.put(ordered.get(i),s);}
    if(stored.isEmpty())return new OnlineSessionPage(List.of(),0,false);
    var userIds=stored.values().stream().map(Stored::userId).distinct().toList();
    var args=new ArrayList<Object>();args.add(tenant);args.addAll(userIds);
    var users=jdbc.queryForList("SELECT CAST(id AS CHAR) id,username,name,auth_version,builtin FROM sys_user WHERE tenant_id=? AND status='启用' AND id IN ("
        +String.join(",",Collections.nCopies(userIds.size(),"?"))+")",args.toArray());
    var byId=new HashMap<String,Map<String,Object>>();for(var u:users)byId.put((String)u.get("id"),u);
    var sessions=new ArrayList<>(stored.values());
    var revoked=redis.opsForValue().multiGet(sessions.stream().map(s->"star:revoked:"+s.family).toList());
    String query=keyword.trim().toLowerCase(Locale.ROOT);String current=currentFamily==null?"":AuthService.hash(currentFamily);
    var result=new ArrayList<OnlineSessionPage.Item>();int i=0;
    for(var entry:stored.entrySet()) {
      var s=entry.getValue();var u=byId.get(s.userId);boolean isRevoked=revoked!=null && revoked.get(i)!=null;i++;
      if(u==null || isRevoked || ((Number)u.get("auth_version")).intValue()!=s.authVersion)continue;
      String username=(String)u.get("username"),name=(String)u.get("name");
      if(!(username+" "+name+" "+s.ip).toLowerCase(Locale.ROOT).contains(query))continue;
      boolean builtin=Boolean.TRUE.equals(u.get("builtin")) || "1".equals(String.valueOf(u.get("builtin")));
      result.add(new OnlineSessionPage.Item(entry.getKey(),username,name,s.ip,s.browser,s.os,s.loginAt,s.lastSeen,entry.getKey().equals(current),builtin));
    }
    int from=(int)Math.min(result.size(),((long)page-1)*size),to=Math.min(result.size(),from+size);
    return new OnlineSessionPage(List.copyOf(result.subList(from,to)),result.size(),cardinality!=null&&cardinality>2000);
  }

  /** 强退只撤销选定刷新家族，不删除账号；保护当前会话和内置管理员。 */
  public void kick(String id,String currentFamily) {
    permissions.require("monitor:session:kick");permissions.requireAdmin();
    if(id==null || !id.matches("[a-f0-9]{64}"))throw BusinessException.missing();
    if(currentFamily!=null && id.equals(AuthService.hash(currentFamily)))throw BusinessException.conflict("当前会话请使用退出登录");
    String tenant=Actor.tenant();var stored=read(redis.opsForValue().get(key(tenant,id)));
    if(stored==null)throw BusinessException.missing();
    var users=jdbc.queryForList("SELECT builtin FROM sys_user WHERE tenant_id=? AND id=?",tenant,stored.userId);
    if(users.isEmpty())throw BusinessException.missing();
    Object builtin=users.get(0).get("builtin");
    if(Boolean.TRUE.equals(builtin)||"1".equals(String.valueOf(builtin)))throw BusinessException.conflict("不能强退内置管理员");
    redis.opsForValue().set("star:revoked:"+stored.family,"1",Duration.ofDays(7));
    remove(tenant,stored.family);
  }
  private Stored read(String value) {if(value==null)return null;try{return json.readValue(value,Stored.class);}catch(JsonProcessingException invalid){return null;}}
  private static String browser(String agent) {
    if(agent.contains("Edg/"))return "Edge";if(agent.contains("Chrome/"))return "Chrome";
    if(agent.contains("Firefox/"))return "Firefox";if(agent.contains("Safari/"))return "Safari";return "其他";
  }
  private static String os(String agent) {
    if(agent.contains("Windows"))return "Windows";if(agent.contains("Android"))return "Android";
    if(agent.contains("iPhone")||agent.contains("iPad"))return "iOS";if(agent.contains("Mac OS"))return "macOS";
    if(agent.contains("Linux"))return "Linux";return "其他";
  }
}
