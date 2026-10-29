package com.star.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.star.system.entity.Role;

// 包职责：提供系统管理实体的 MyBatis-Plus 数据访问接口。
// 模块联系：映射 system.entity，由 system.service 和 auth 服务调用；租户、权限及版本条件由服务层提供。

/**
 * 提供角色实体的 MyBatis-Plus 基础数据库读写接口。
 *
 * <p>关联模块：映射 Role 实体，由 SystemService 等业务服务调用；租户、权限和版本条件由服务层显式提供。
 */
public interface RoleMapper extends BaseMapper<Role> {}
