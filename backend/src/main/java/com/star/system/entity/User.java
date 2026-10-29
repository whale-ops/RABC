package com.star.system.entity;


import com.baomidou.mybatisplus.annotation.TableName;

// 包职责：映射系统管理数据库实体，承载租户、状态、版本和业务字段。
// 模块联系：由 system.mapper 持久化，供 system.service、auth 和 framework.security 处理用户及权限数据。

/**
 * 映射 sys_user 用户账号表，保存认证资料、组织归属和账号状态。
 *
 * <p>关联模块：AuthService 校验密码和会话；SystemService 管理账号，AccountService 维护个人资料。
 */
@TableName("sys_user")
public class User extends BaseRecord {
  public String username;

  /** 仅保存 BCrypt 摘要；认证接口和实体展示转换均不得返回该字段。 */
  public String passwordHash;

  public String deptId;
  public String phone;
  public String email;
  public String remark;
  public String bio;

  /** 认证版本：改密或撤权等场景递增，用于拒绝旧会话。 */
  public Integer authVersion;

  /** 修改初始密码的提示标识，不用于强制拦截登录后的业务页面。 */
  public Boolean mustChangePassword;

  /** 内置账号标记，配合角色关系识别受保护管理员。 */
  public Boolean builtin;
}
