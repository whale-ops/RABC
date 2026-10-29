package com.star.business.service;

import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 包职责：统一业务记录序列化、分页和角色订单数据范围。
// 模块联系：产品与订单服务复用此组件，权限来源为 framework，数据仅来自当前会话租户。
@Component
public class BusinessSupport {
  public final JdbcTemplate jdbc;
  public final PermissionService permission;

  public BusinessSupport(JdbcTemplate jdbc, PermissionService permission) {
    this.jdbc = jdbc;
    this.permission = permission;
  }

  public void page(int page, int size, String keyword) {
    if (page < 1 || page > 100000 || size < 1 || size > 100 || keyword.length() > 120)
      throw BusinessException.bad("分页或搜索条件不合法");
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    if (rows.isEmpty()) throw BusinessException.missing();
    return rows.get(0);
  }

  public Map<String, Object> output(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    row.forEach(
        (key, value) -> {
          if (value instanceof BigDecimal money) value = money.toPlainString();
          else if (value instanceof Timestamp time)
            value = time.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
          else if (value instanceof LocalDateTime time)
            value = time.toInstant(ZoneOffset.UTC).toString();
          if (Set.of("id", "createdBy", "productId", "orderId").contains(key) && value != null)
            value = value.toString();
          out.put(key, value);
        });
    return out;
  }

  public void version(Map<String, Object> row, Long version) {
    if (version == null || ((Number) row.get("version")).longValue() != version)
      throw BusinessException.conflict("记录已被其他操作更新，请刷新后重试");
  }

  /** 按当前动作拥有权限的角色合并本人和部门范围；不能借其他动作扩大订单访问范围。 */
  public String orderScope(String grant, List<Object> args) {
    var scope = permission.scope(grant);
    if (scope.all()) return "";
    StringJoiner allowed = new StringJoiner(" OR ", " AND (", " )");
    allowed.add("1=0");
    if (scope.self()) {
      allowed.add("o.created_by=?");
      args.add(Actor.user().id);
    }
    if (!scope.depts().isEmpty()) {
      allowed.add(
          "o.created_dept_id IN ("
              + String.join(",", Collections.nCopies(scope.depts().size(), "?"))
              + ")");
      args.addAll(scope.depts());
    }
    return allowed.toString();
  }

  public String like(String keyword) {
    return "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }
}
