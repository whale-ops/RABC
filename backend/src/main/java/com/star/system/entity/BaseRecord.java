package com.star.system.entity;


import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

// 包职责：映射系统管理数据库实体，承载租户、状态、版本和业务字段。
// 模块联系：由 system.mapper 持久化，供 system.service、auth 和 framework.security 处理用户及权限数据。

/**
 * 定义系统管理实体共有的主键、租户、状态、版本及创建更新时间。
 *
 * <p>关联模块：由用户、角色、菜单、部门和岗位实体继承；SystemService 按租户和版本执行数据库操作。
 */
public abstract class BaseRecord {
  @TableId(type = IdType.ASSIGN_ID)
  public String id;

  /** 所属租户：所有业务查询和写入必须带上这一隔离条件。 */
  public String tenantId;

  public String name;
  public String status;

  /** 乐观锁版本：更新和删除时与前端提交值比较，防止覆盖并发修改。 */
  public Integer version;

  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
}
