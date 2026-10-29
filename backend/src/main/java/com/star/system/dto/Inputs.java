package com.star.system.dto;


import jakarta.validation.constraints.*;
import java.util.List;

// 包职责：定义用户、角色、菜单、部门、岗位的写入、导入及授权请求模型。
// 模块联系：由 system.controller 接收并校验字段，交给 system.service 执行业务规则与关联校验。

/**
 * 集中定义接口输入模型及字段校验规则，区分创建、更新、导入和授权请求；认证输入由 auth.dto.AuthInputs 定义。
 *
 * <p>关联模块：控制器接收这些模型；SystemService 与账号申请服务执行系统管理数据的后续业务校验。
 */
public final class Inputs {
  private Inputs() {}

  public record UserWrite(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,64}") String username,
      @NotBlank @Size(max = 64) String name,
      String deptId,
      @Size(max = 10) List<String> roleIds,
      @Size(max = 10) List<@NotBlank @Size(max = 64) String> roleNames,
      @Size(max = 20) List<String> postIds,
      @Email @Size(max = 128) String email,
      @Pattern(regexp = "^$|^1[0-9]{10}$") String phone,
      @Size(max = 500) String remark,
      @NotBlank @Pattern(regexp = "启用|停用") String status,
      @Size(max = 72) String initialPassword,
      Integer version) {}

  /** 用户批量导入请求；前端预校验不能替代后台校验及整批事务。 */
  public record UserImport(
      @NotEmpty @Size(max = 100) List<@jakarta.validation.Valid @NotNull UserWrite> rows) {}

  public record RoleWrite(
      @NotBlank @Size(max = 64) String name,
      @NotBlank @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9:_-]{1,63}") String code,
      @NotBlank @Pattern(regexp = "全部数据|本部门及下级|本部门|自定义部门|仅本人") String scope,
      @NotBlank @Pattern(regexp = "启用|停用") String status,
      @Size(max = 500) String remark,
      Integer version) {}

  /** 角色批量导入请求；只创建普通角色，权限和成员另行配置。 */
  public record RoleImport(
      @NotEmpty @Size(max = 100) List<@jakarta.validation.Valid @NotNull RoleWrite> rows) {}

  public record MenuWrite(
      @NotBlank @Size(max = 64) String name,
      String parentId,
      @NotBlank @Pattern(regexp = "目录|菜单|按钮") String type,
      @Size(max = 200) String path,
      @Size(max = 100) String permission,
      @Min(0) @Max(99999) Integer sort,
      @Size(max = 40) String icon,
      @NotBlank @Pattern(regexp = "启用|停用") String status,
      Integer version) {}

  public record DeptWrite(
      @NotBlank @Size(max = 64) String name,
      String parentId,
      @NotBlank @Size(max = 64) String leader,
      @Size(max = 32) String phone,
      @Min(0) @Max(99999) Integer sort,
      @NotBlank @Pattern(regexp = "启用|停用") String status,
      @Size(max = 500) String remark,
      Integer version) {}

  public record PostWrite(
      @NotBlank @Size(max = 64) String name,
      @NotBlank @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9:_-]{1,63}") String code,
      @Min(0) @Max(99999) Integer sort,
      @NotBlank @Pattern(regexp = "启用|停用") String status,
      @Size(max = 500) String remark,
      Integer version) {}

  public record Status(
      @NotBlank @Pattern(regexp = "启用|停用") String status, @NotNull Integer version) {}

  public record Version(
      @NotBlank @Pattern(regexp = "[0-9]{1,20}") String id, @NotNull Integer version) {}

  public record Delete(@NotEmpty @Size(max = 100) List<@jakarta.validation.Valid Version> items) {}

  /** 角色授权请求：同时提交菜单集合、数据范围和当前版本。 */
  public record Grant(
      @NotNull @Size(max = 500) List<String> menuIds,
      @NotBlank @Pattern(regexp = "全部数据|本部门及下级|本部门|自定义部门|仅本人") String scope,
      @NotNull @Size(max = 200) List<String> deptIds,
      @NotNull Integer version) {}

  public record Roles(@NotNull @Size(max = 10) List<String> roleIds, @NotNull Integer version) {}

  public record PasswordReset(
      @NotBlank @Size(max = 72) String newPassword, @NotNull Integer version) {}
}
