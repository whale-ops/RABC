package com.star.system.service;

import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.system.mapper.UserMapper;
import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 包职责：按当前租户、资源权限和日志可见范围组织首页统计与最近操作。
// 模块联系：DashboardController 提供接口，读取 system 资源表及 monitor 操作日志，前端 SystemHome 展示。

/** 日志列表权限持有者查看本租户，否则只返回本人记录，不混用旧版审计表。 */
@Service
public class DashboardService {
  private final PermissionService permission;
  private final UserMapper users;
  private final JdbcTemplate jdbc;
  public DashboardService(PermissionService permission,UserMapper users,JdbcTemplate jdbc){this.permission=permission;this.users=users;this.jdbc=jdbc;}
  public Map<String,Object> overview(){
    permission.require("dashboard:read");
    var grants=permission.getPermissionList(Actor.user().id,"login");
    var result=new LinkedHashMap<String,Object>();
    result.put("users",grants.contains("system:user:list")?users.selectCount(permission.userQuery("system:user:list")):null);
    for(var entry:Map.of("roles","role","departments","dept","posts","post").entrySet())
      result.put(entry.getKey(),grants.contains("system:"+entry.getValue()+":list")?jdbc.queryForObject("SELECT COUNT(*) FROM sys_"+entry.getValue()+" WHERE tenant_id=?",Long.class,Actor.tenant()):null);
    result.put("uptimeSeconds",ManagementFactory.getRuntimeMXBean().getUptime()/1000);
    result.put("activities",activities(1,5).get("records"));
    return result;
  }
  public Map<String,Object> activities(int page,int size){
    permission.require("dashboard:read");
    if(page<1||page>100000||size<1||size>20)throw BusinessException.bad("首页分页条件不合法");
    var args=new ArrayList<Object>();args.add(Actor.tenant());
    boolean tenant=permission.getPermissionList(Actor.user().id,"login").contains("audit:operation:list");
    String where=" WHERE tenant_id=?";
    if(!tenant){where+=" AND user_id=?";args.add(Actor.user().id);}
    long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log"+where,Long.class,args.toArray()));
    var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add(((long)page-1)*size);
    var records=jdbc.queryForList("SELECT CAST(id AS CHAR) id,display_name operator,action,module resource,status,ip,created_at createdAt FROM sys_operation_log"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",pageArgs.toArray());
    for(var row:records){var date=row.get("createdAt");if(date instanceof Timestamp t)row.put("createdAt",t.toInstant().toString());else if(date instanceof LocalDateTime t)row.put("createdAt",t.toInstant(ZoneOffset.UTC).toString());}
    return Map.of("records",records,"total",total,"scope",tenant?"tenant":"self");
  }
}
