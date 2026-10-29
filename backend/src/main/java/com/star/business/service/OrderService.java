package com.star.business.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.auth.service.AuthService;
import com.star.business.dto.BusinessInputs;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：管理订单及价格快照，在事务中完成库存占用、取消归还和受控状态流转。
// 模块联系：产品目录提供可售商品，framework 提供租户、角色数据范围，控制器统一记录审计。
@Service
public class OrderService {
  private final BusinessSupport support;
  private static final Set<String> STATES = Set.of("待付款", "待发货", "已发货", "已完成", "已取消");
  private static final String FIELDS =
      "o.id,o.order_no orderNo,o.customer,o.phone,o.address,o.remark,o.total,o.status,o.tracking_no"
          + " trackingNo,o.version,o.created_by createdBy,o.created_at createdAt,o.updated_at"
          + " updatedAt,o.paid_at paidAt,o.shipped_at shippedAt,o.completed_at"
          + " completedAt,o.cancelled_at cancelledAt";

  public OrderService(BusinessSupport support) {
    this.support = support;
  }

  public Map<String, Object> list(int page, int size, String keyword, String status) {
    String grant = "business:order:list";
    support.permission.require(grant);
    support.page(page, size, keyword);
    if (!status.isBlank() && !STATES.contains(status)) throw BusinessException.bad("订单状态不合法");
    var args = new ArrayList<Object>(List.of(Actor.tenant()));
    String where = " WHERE o.tenant_id=?" + support.orderScope(grant, args);
    if (!keyword.isBlank()) {
      where +=
          " AND (o.order_no LIKE ? ESCAPE '!' OR o.customer LIKE ? ESCAPE '!' OR o.phone LIKE ?"
              + " ESCAPE '!')";
      for (int i = 0; i < 3; i++) args.add(support.like(keyword));
    }
    if (!status.isBlank()) {
      where += " AND o.status=?";
      args.add(status);
    }
    var summary =
        support.output(
            support.one(
                "SELECT COUNT(*) total,COALESCE(SUM(o.status='待付款'),0)"
                    + " unpaid,COALESCE(SUM(o.status='待发货'),0) toShip,COALESCE(SUM(CASE WHEN"
                    + " o.status<>'已取消' THEN o.total ELSE 0 END),0) amount FROM biz_order o"
                    + where,
                args.toArray()));
    var paged = new ArrayList<>(args);
    paged.add(size);
    paged.add(((long) page - 1) * size);
    var rows =
        support
            .jdbc
            .queryForList(
                "SELECT "
                    + FIELDS
                    + ",(SELECT COALESCE(SUM(quantity),0) FROM biz_order_item i WHERE"
                    + " i.tenant_id=o.tenant_id AND i.order_id=o.id) quantity FROM biz_order o"
                    + where
                    + " ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",
                paged.toArray())
            .stream()
            .map(support::output)
            .toList();
    return Map.of("records", rows, "total", summary.get("total"), "summary", summary);
  }

  private Map<String, Object> row(String id, String grant, boolean lock) {
    var args = new ArrayList<Object>(List.of(Actor.tenant(), id));
    String where = " WHERE o.tenant_id=? AND o.id=?" + support.orderScope(grant, args);
    return support.one(
        "SELECT " + FIELDS + " FROM biz_order o" + where + (lock ? " FOR UPDATE" : ""),
        args.toArray());
  }

  private Map<String, Object> output(Map<String, Object> row) {
    var result = support.output(row);
    result.put(
        "items",
        support
            .jdbc
            .queryForList(
                "SELECT id,product_id productId,sku,name,price,quantity,subtotal FROM"
                    + " biz_order_item WHERE tenant_id=? AND order_id=? ORDER BY id",
                Actor.tenant(),
                row.get("id"))
            .stream()
            .map(support::output)
            .toList());
    return result;
  }

  public Map<String, Object> detail(String id) {
    String grant = "business:order:read";
    support.permission.require(grant);
    return output(row(id, grant, false));
  }

  /** 按产品 ID 固定顺序加行锁，防止超卖；金额完全使用服务端价格和 BigDecimal 计算。 */
  @Transactional
  public Map<String, Object> create(BusinessInputs.CreateOrder input) {
    String grant = "business:order:create";
    support.permission.require(grant);
    var quantities = new TreeMap<String, Integer>(Comparator.comparing(BigInteger::new));
    for (var item : input.items())
      if (quantities.putIfAbsent(item.productId(), item.quantity()) != null)
        throw BusinessException.bad("同一产品请合并数量，不要重复添加");
    var products = new ArrayList<Map<String, Object>>();
    for (String id : quantities.keySet())
      products.add(
          support.one(
              "SELECT id,sku,name,price,stock,status FROM biz_product WHERE tenant_id=? AND id=?"
                  + " FOR UPDATE",
              Actor.tenant(),
              id));
    // 加锁后再次读取幂等编号；相同提交等待首个事务完成，不会再次扣库存。
    var previous =
        support.jdbc.queryForList(
            "SELECT id,request_hash FROM biz_order WHERE tenant_id=? AND created_by=? AND"
                + " request_id=?",
            Actor.tenant(),
            Actor.user().id,
            input.requestId().toLowerCase(Locale.ROOT));
    String hash = AuthService.hash(input.toString());
    if (!previous.isEmpty()) {
      if (!hash.equals(previous.get(0).get("request_hash")))
        throw BusinessException.conflict("提交编号已用于另一份订单，请重新创建");
      return Map.of("id", previous.get(0).get("id").toString());
    }
    BigDecimal total = BigDecimal.ZERO;
    for (var product : products) {
      int quantity = quantities.get(product.get("id").toString());
      if (!"上架".equals(product.get("status"))) throw BusinessException.conflict("产品已下架，请重新选择");
      if (((Number) product.get("stock")).intValue() < quantity)
        throw BusinessException.conflict("产品「" + product.get("name") + "」库存不足");
      total = total.add(((BigDecimal) product.get("price")).multiply(BigDecimal.valueOf(quantity)));
    }
    String id = IdWorker.getIdStr(), orderNo = "SO" + id;
    support.jdbc.update(
        "INSERT INTO"
            + " biz_order(id,tenant_id,order_no,request_id,request_hash,customer,phone,address,remark,total,created_by,created_dept_id)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        Actor.tenant(),
        orderNo,
        input.requestId().toLowerCase(Locale.ROOT),
        hash,
        input.customer().trim(),
        input.phone().trim(),
        input.address().trim(),
        input.remark().trim(),
        total,
        Actor.user().id,
        Actor.user().deptId);
    for (var product : products) {
      int quantity = quantities.get(product.get("id").toString());
      BigDecimal price = (BigDecimal) product.get("price");
      support.jdbc.update(
          "INSERT INTO"
              + " biz_order_item(id,tenant_id,order_id,product_id,sku,name,price,quantity,subtotal)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          IdWorker.getIdStr(),
          Actor.tenant(),
          id,
          product.get("id"),
          product.get("sku"),
          product.get("name"),
          price,
          quantity,
          price.multiply(BigDecimal.valueOf(quantity)));
      support.jdbc.update(
          "UPDATE biz_product SET stock=stock-?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE"
              + " tenant_id=? AND id=?",
          quantity,
          Actor.tenant(),
          product.get("id"));
    }
    return Map.of("id", id, "orderNo", orderNo);
  }

  @Transactional
  public void edit(String id, BusinessInputs.EditOrder input) {
    String grant = "business:order:update";
    support.permission.require(grant);
    var order = row(id, grant, true);
    support.version(order, input.version());
    requireState(order, "待付款");
    support.jdbc.update(
        "UPDATE biz_order SET"
            + " customer=?,phone=?,address=?,remark=?,version=version+1,updated_at=UTC_TIMESTAMP(6)"
            + " WHERE tenant_id=? AND id=?",
        input.customer().trim(),
        input.phone().trim(),
        input.address().trim(),
        input.remark().trim(),
        Actor.tenant(),
        id);
  }

  @Transactional
  public void transition(String id, String action, Long version, String trackingNo) {
    if (!Set.of("cancel", "pay", "ship", "complete").contains(action))
      throw BusinessException.bad("订单操作不合法");
    String grant = "business:order:" + action;
    support.permission.require(grant);
    var order = row(id, grant, true);
    support.version(order, version);
    String before =
        switch (action) {
          case "cancel", "pay" -> "待付款";
          case "ship" -> "待发货";
          default -> "已发货";
        };
    requireState(order, before);
    String after =
        switch (action) {
          case "cancel" -> "已取消";
          case "pay" -> "待发货";
          case "ship" -> "已发货";
          default -> "已完成";
        };
    if (action.equals("ship") && (trackingNo == null || trackingNo.isBlank()))
      throw BusinessException.bad("请填写物流单号");
    if (action.equals("cancel")) {
      var items =
          support.jdbc.queryForList(
              "SELECT product_id,quantity FROM biz_order_item WHERE tenant_id=? AND order_id=?"
                  + " ORDER BY product_id",
              Actor.tenant(),
              id);
      for (var item : items)
        support.jdbc.update(
            "UPDATE biz_product SET stock=stock+?,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                + " WHERE tenant_id=? AND id=?",
            item.get("quantity"),
            Actor.tenant(),
            item.get("product_id"));
    }
    String timeColumn =
        switch (action) {
          case "cancel" -> "cancelled_at";
          case "pay" -> "paid_at";
          case "ship" -> "shipped_at";
          default -> "completed_at";
        };
    support.jdbc.update(
        "UPDATE biz_order SET status=?,tracking_no=?,"
            + timeColumn
            + "=UTC_TIMESTAMP(6),version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=?"
            + " AND id=?",
        after,
        action.equals("ship") ? trackingNo.trim() : order.get("trackingNo"),
        Actor.tenant(),
        id);
  }

  private void requireState(Map<String, Object> order, String expected) {
    if (!expected.equals(order.get("status")))
      throw BusinessException.conflict("当前订单状态为「" + order.get("status") + "」，不能执行此操作");
  }
}
