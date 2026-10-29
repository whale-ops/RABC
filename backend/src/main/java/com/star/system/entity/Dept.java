package com.star.system.entity;


import com.baomidou.mybatisplus.annotation.TableName;

// 包职责：映射系统管理数据库实体，承载租户、状态、版本和业务字段。
// 模块联系：由 system.mapper 持久化，供 system.service、auth 和 framework.security 处理用户及权限数据。

/**
 * 映射 sys_dept 部门树，保存上级部门、负责人、联系方式和排序信息。
 *
 * <p>关联模块：SystemService 维护组织结构；PermissionService 根据部门树计算用户数据范围。
 */
@TableName("sys_dept")
public class Dept extends BaseRecord {
  @com.baomidou.mybatisplus.annotation.TableField(
      updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
  public String parentId;

  public String leader;
  public String phone;
  public Integer sort;
  public String remark;
}
