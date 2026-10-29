package com.star.system.entity;


import com.baomidou.mybatisplus.annotation.TableName;

// 包职责：映射系统管理数据库实体，承载租户、状态、版本和业务字段。
// 模块联系：由 system.mapper 持久化，供 system.service、auth 和 framework.security 处理用户及权限数据。

/**
 * 映射 sys_post 岗位表，表示组织中的职位信息，不直接代表系统操作权限。
 *
 * <p>关联模块：SystemService 维护岗位，用户通过 sys_user_post 关联；与 Role 权限角色分开管理。
 */
@TableName("sys_post")
public class Post extends BaseRecord {
  public String code;
  public Integer sort;
  public String remark;
}
