package com.star.system.entity;


import com.baomidou.mybatisplus.annotation.TableName;

// 包职责：映射系统管理数据库实体，承载租户、状态、版本和业务字段。
// 模块联系：由 system.mapper 持久化，供 system.service、auth 和 framework.security 处理用户及权限数据。

/**
 * 映射 sys_menu 的目录、页面和按钮权限，支持父子层级及前端路由。
 *
 * <p>关联模块：PermissionService 读取权限码；SystemService 生成导航，前端 Shell.vue 与权限分配界面消费。
 */
@TableName("sys_menu")
public class Menu extends BaseRecord {
  @com.baomidou.mybatisplus.annotation.TableField(
      updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
  public String parentId;

  public String type;
  public String path;
  public String permission;
  public Integer sort;
  public String icon;
}
