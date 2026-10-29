package com.star.system.service;

import com.star.auth.service.AuthService;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.system.dto.DashboardAnalytics;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 包职责：采集真实页面浏览，按租户、日期和账号摘要汇总 PV/UV 与操作分布。
// 模块联系：路由钩子向 DashboardController 上报，MySQL 保存日统计，Redis 去重重试事件。

/** 不用登录次数代替 PV，不补造上线前的访问历史；数据库不保存页面 URL、IP 或用户名。 */
@Service
public class DashboardAnalyticsService {
  private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final PermissionService permissions;
  public DashboardAnalyticsService(JdbcTemplate jdbc,StringRedisTemplate redis,PermissionService permissions){this.jdbc=jdbc;this.redis=redis;this.permissions=permissions;}
  private String visitor(){return AuthService.hash(Actor.tenant()+":"+Actor.user().id);}

  /** 身份取自后端会话，相同事件重试不重复计数；刷新页面是一个新的浏览事件。 */
  public void visit(String eventId){
    if(eventId==null||!eventId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw BusinessException.bad("访问事件编号不合法");
    String key="star:visit-event:"+Actor.tenant()+":"+eventId.toLowerCase(Locale.ROOT);
    if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,"1",Duration.ofDays(1))))return;
    try{
      jdbc.update("INSERT INTO sys_visit_daily(tenant_id,stat_date,visitor_hash,pv) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE pv=pv+1",Actor.tenant(),LocalDate.now(ZONE),visitor());
    }catch(RuntimeException failure){redis.delete(key);throw failure;}
  }

  public DashboardAnalytics analytics(int days){
    permissions.require("dashboard:read");
    if(!Set.of(7,30,90).contains(days))throw BusinessException.bad("统计范围仅支持 7、30、90 天");
    var grants=permissions.getPermissionList(Actor.user().id,"login");
    boolean visitsTenant=grants.contains("audit:login:list"),operationsTenant=grants.contains("audit:operation:list");
    LocalDate end=LocalDate.now(ZONE),start=end.minusDays(days-1);
    var args=new ArrayList<Object>(List.of(Actor.tenant(),start,end));
    String where=" WHERE tenant_id=? AND stat_date>=? AND stat_date<=?";
    if(!visitsTenant){where+=" AND visitor_hash=?";args.add(visitor());}
    var daily=jdbc.queryForList("SELECT stat_date day,SUM(pv) pv,COUNT(DISTINCT visitor_hash) uv FROM sys_visit_daily"+where+" GROUP BY stat_date",args.toArray());
    var byDay=new HashMap<String,Map<String,Object>>();for(var row:daily)byDay.put(row.get("day").toString(),row);
    var points=new ArrayList<DashboardAnalytics.Point>();long totalPv=0;
    for(int i=0;i<days;i++){String date=start.plusDays(i).toString();var row=byDay.get(date);long pv=row==null?0:((Number)row.get("pv")).longValue(),uv=row==null?0:((Number)row.get("uv")).longValue();points.add(new DashboardAnalytics.Point(date,pv,uv));totalPv+=pv;}
    long uv=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM sys_visit_daily"+where,Long.class,args.toArray()));
    LocalDateTime from=LocalDateTime.ofInstant(start.atStartOfDay(ZONE).toInstant(),ZoneOffset.UTC),until=LocalDateTime.ofInstant(end.plusDays(1).atStartOfDay(ZONE).toInstant(),ZoneOffset.UTC);
    var opArgs=new ArrayList<Object>(List.of(Actor.tenant(),from,until));
    String opWhere=" WHERE tenant_id=? AND created_at>=? AND created_at<?";
    if(!operationsTenant){opWhere+=" AND user_id=?";opArgs.add(Actor.user().id);}
    var distribution=jdbc.query("SELECT COALESCE(NULLIF(module,''),'其他') name,COUNT(*) amount FROM sys_operation_log"+opWhere+" GROUP BY name ORDER BY amount DESC,name",(rs,n)->new DashboardAnalytics.Distribution(rs.getString("name"),rs.getLong("amount")),opArgs.toArray());
    var loginArgs=new ArrayList<Object>(List.of(Actor.tenant(),from,until));
    String loginWhere=" WHERE tenant_id=? AND created_at>=? AND created_at<? AND status='失败'";
    if(!visitsTenant){loginWhere+=" AND user_id=?";loginArgs.add(Actor.user().id);}
    long failures=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sys_login_log"+loginWhere,Long.class,loginArgs.toArray()));
    return new DashboardAnalytics(days,ZONE.getId(),visitsTenant?"tenant":"self",operationsTenant?"tenant":"self",totalPv,uv,failures,List.copyOf(points),distribution,distribution.stream().mapToLong(DashboardAnalytics.Distribution::value).sum());
  }
}
