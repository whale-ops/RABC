package com.star.system.entity;


import com.baomidou.mybatisplus.annotation.TableName;

// 包职责：映射系统管理数据库实体，承载租户、状态、版本和业务字段。
// 模块联系：由 system.mapper 持久化，供 system.service、auth 和 framework.security 处理用户及权限数据。

/**
 * 映射 sys_role 权限角色表，保存角色编码、数据范围和内置标识。
 *
 * <p>关联模块：PermissionService 合并角色权限和数据范围；SystemService 维护角色，用户通过关联表绑定角色。
 */
@TableName("sys_role")
public class Role extends BaseRecord {
  public String code;

  /** 角色对用户数据的可见范围，由 PermissionService 按当前操作合并。 */
  public String scope;

  public String remark;

  /** 内置角色不可通过普通用户创建或随意分配。 */
  public Boolean builtin;
}
