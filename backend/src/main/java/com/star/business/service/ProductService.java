package com.star.business.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.business.dto.BusinessInputs;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：维护租户共享的产品目录、可售库存与上下架状态。
// 模块联系：订单服务以数据库行锁读取产品并扣减库存；RBAC 按钮权限控制维护操作。
@Service
public class ProductService {
  private final BusinessSupport support;
  private static final String FIELDS =
      "id,sku,name,category,price,stock,status,description,version,created_by createdBy,created_at"
          + " createdAt,updated_at updatedAt";

  public ProductService(BusinessSupport support) {
    this.support = support;
  }

  public Map<String, Object> list(
      int page, int size, String keyword, String status, String category) {
    support.permission.require("business:product:list");
    support.page(page, size, keyword);
    if (!Set.of("", "上架", "下架").contains(status) || category.length() > 60)
      throw BusinessException.bad("产品筛选条件不合法");
    var args = new ArrayList<Object>(List.of(Actor.tenant()));
    String where = " WHERE tenant_id=?";
    if (!keyword.isBlank()) {
      where += " AND (name LIKE ? ESCAPE '!' OR sku LIKE ? ESCAPE '!')";
      args.add(support.like(keyword));
      args.add(support.like(keyword));
    }
    if (!status.isBlank()) {
      where += " AND status=?";
      args.add(status);
    }
    if (!category.isBlank()) {
      where += " AND category=?";
      args.add(category.trim());
    }
    var summary =
        support.output(
            support.one(
                "SELECT COUNT(*) total,COALESCE(SUM(status='上架'),0) onSale,COALESCE(SUM(stock),0)"
                    + " stock,COALESCE(SUM(price*stock),0) inventoryValue FROM biz_product"
                    + where,
                args.toArray()));
    var paged = new ArrayList<>(args);
    paged.add(size);
    paged.add(((long) page - 1) * size);
    var records =
        support
            .jdbc
            .queryForList(
                "SELECT "
                    + FIELDS
                    + " FROM biz_product"
                    + where
                    + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                paged.toArray())
            .stream()
            .map(support::output)
            .toList();
    var categories =
        support.jdbc.queryForList(
            "SELECT DISTINCT category FROM biz_product WHERE tenant_id=? AND category<>'' ORDER BY"
                + " category",
            String.class,
            Actor.tenant());
    return Map.of(
        "records",
        records,
        "total",
        summary.get("total"),
        "summary",
        summary,
        "categories",
        categories);
  }

  public Map<String, Object> detail(String id) {
    support.permission.require("business:product:read");
    return support.output(row(id, false));
  }

  private Map<String, Object> row(String id, boolean lock) {
    return support.one(
        "SELECT "
            + FIELDS
            + " FROM biz_product WHERE tenant_id=? AND id=?"
            + (lock ? " FOR UPDATE" : ""),
        Actor.tenant(),
        id);
  }

  @Transactional
  public Map<String, Object> save(String id, BusinessInputs.Product input) {
    support.permission.require("business:product:" + (id == null ? "create" : "update"));
    if (id != null) support.version(row(id, true), input.version());
    String sku = input.sku().trim().toUpperCase(Locale.ROOT);
    Long duplicate =
        support.jdbc.queryForObject(
            "SELECT COUNT(*) FROM biz_product WHERE tenant_id=? AND sku=? AND id<>?",
            Long.class,
            Actor.tenant(),
            sku,
            id == null ? "0" : id);
    if (duplicate != null && duplicate > 0) throw BusinessException.conflict("产品编码已存在");
    if (id == null) {
      id = IdWorker.getIdStr();
      support.jdbc.update(
          "INSERT INTO"
              + " biz_product(id,tenant_id,sku,name,category,price,stock,description,created_by)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          id,
          Actor.tenant(),
          sku,
          input.name().trim(),
          input.category().trim(),
          input.price(),
          input.stock(),
          input.description().trim(),
          Actor.user().id);
    } else
      support.jdbc.update(
          "UPDATE biz_product SET"
              + " sku=?,name=?,category=?,price=?,stock=?,description=?,version=version+1,updated_at=UTC_TIMESTAMP(6)"
              + " WHERE tenant_id=? AND id=?",
          sku,
          input.name().trim(),
          input.category().trim(),
          input.price(),
          input.stock(),
          input.description().trim(),
          Actor.tenant(),
          id);
    return support.output(row(id, false));
  }

  @Transactional
  public Map<String, Object> status(String id, BusinessInputs.ProductStatus input) {
    support.permission.require("business:product:status");
    support.version(row(id, true), input.version());
    support.jdbc.update(
        "UPDATE biz_product SET status=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE"
            + " tenant_id=? AND id=?",
        input.status(),
        Actor.tenant(),
        id);
    return support.output(row(id, false));
  }

  @Transactional
  public void delete(String id, BusinessInputs.Version input) {
    support.permission.require("business:product:delete");
    support.version(row(id, true), input.version());
    Long used =
        support.jdbc.queryForObject(
            "SELECT COUNT(*) FROM biz_order_item WHERE tenant_id=? AND product_id=?",
            Long.class,
            Actor.tenant(),
            id);
    if (used != null && used > 0) throw BusinessException.conflict("产品已有订单记录，请下架保留历史，不能删除");
    support.jdbc.update("DELETE FROM biz_product WHERE tenant_id=? AND id=?", Actor.tenant(), id);
  }

  /** 下单权限仅开放上架产品的必要选项，不借用产品维护或查询权限。 */
  public List<Map<String, Object>> choices(String keyword) {
    support.permission.require("business:order:create");
    support.page(1, 100, keyword);
    return support
        .jdbc
        .queryForList(
            "SELECT id,sku,name,price,stock FROM biz_product WHERE tenant_id=? AND status='上架' AND"
                + " stock>0 AND (name LIKE ? ESCAPE '!' OR sku LIKE ? ESCAPE '!') ORDER BY name,id"
                + " LIMIT 100",
            Actor.tenant(),
            support.like(keyword),
            support.like(keyword))
        .stream()
        .map(support::output)
        .toList();
  }
}
