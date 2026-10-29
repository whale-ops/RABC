-- 系统管理表结构：认证、组织、权限、账号申请和原有业务审计共用的基础表。
-- 关联模块：system 实体与 Mapper、security 服务读取这些表；新日志表由 log-schema.sql 补充。

-- MySQL 8 / UTF-8。只负责建表；基础数据见 data.sql。
-- 可重复执行：表已存在时跳过建表，不删除、不覆盖已有表和数据。
-- 此脚本不会修改已存在表的字段、索引或外键。
-- 所有关联保留 tenant_id 复合外键，时间统一按 UTC 存储。

-- 租户
CREATE TABLE IF NOT EXISTS sys_tenant (
    id BIGINT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL UNIQUE COMMENT '编码',
    name VARCHAR(100) NOT NULL COMMENT '名称',
    status VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用或停用',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户';

-- 部门
CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGINT PRIMARY KEY COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    name VARCHAR(64) NOT NULL COMMENT '名称',
    status VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用或停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间，UTC',
    UNIQUE KEY uk_sys_dept_tenant_id(tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id),
    parent_id BIGINT NULL COMMENT '父节点，空表示顶级',
    leader VARCHAR(64) NOT NULL DEFAULT '' COMMENT '部门负责人',
    phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    UNIQUE KEY uk_dept_name(tenant_id,name),
    FOREIGN KEY (tenant_id,parent_id) REFERENCES sys_dept(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门';

-- 岗位
CREATE TABLE IF NOT EXISTS sys_post (
    id BIGINT PRIMARY KEY COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    name VARCHAR(64) NOT NULL COMMENT '名称',
    status VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用或停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间，UTC',
    UNIQUE KEY uk_sys_post_tenant_id(tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id),
    code VARCHAR(64) NOT NULL COMMENT '编码',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    UNIQUE KEY uk_post_code(tenant_id,code),
    UNIQUE KEY uk_post_name(tenant_id,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='岗位';

-- 角色
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    name VARCHAR(64) NOT NULL COMMENT '名称',
    status VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用或停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间，UTC',
    UNIQUE KEY uk_sys_role_tenant_id(tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id),
    code VARCHAR(64) NOT NULL COMMENT '编码',
    scope VARCHAR(32) NOT NULL DEFAULT '仅本人' COMMENT '用户数据范围',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    builtin BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否内置管理员',
    UNIQUE KEY uk_role_code(tenant_id,code),
    UNIQUE KEY uk_role_name(tenant_id,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

-- 菜单与按钮权限
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    name VARCHAR(64) NOT NULL COMMENT '名称',
    status VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用或停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间，UTC',
    UNIQUE KEY uk_sys_menu_tenant_id(tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id),
    parent_id BIGINT NULL COMMENT '父节点，空表示顶级',
    type VARCHAR(16) NOT NULL COMMENT '目录、菜单或按钮',
    path VARCHAR(200) NOT NULL DEFAULT '' COMMENT '前端路由',
    permission VARCHAR(100) NOT NULL DEFAULT '' COMMENT '权限标识',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号',
    icon VARCHAR(40) NOT NULL DEFAULT 'list-tree' COMMENT '图标名',
    FOREIGN KEY (tenant_id,parent_id) REFERENCES sys_menu(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单与按钮权限';

-- 用户账号
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    name VARCHAR(64) NOT NULL COMMENT '名称',
    status VARCHAR(16) NOT NULL DEFAULT '启用' COMMENT '启用或停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间，UTC',
    UNIQUE KEY uk_sys_user_tenant_id(tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id),
    username VARCHAR(64) NOT NULL COMMENT '登录账号',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码摘要',
    dept_id BIGINT NULL COMMENT '部门ID',
    phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话',
    email VARCHAR(128) NOT NULL DEFAULT '' COMMENT '邮箱',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    bio VARCHAR(500) NOT NULL DEFAULT '' COMMENT '个人简介',
    auth_version INT NOT NULL DEFAULT 0 COMMENT '认证版本，用于撤销旧会话',
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否建议修改初始密码，不阻断登录',
    builtin BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否内置管理员',
    UNIQUE KEY uk_username(tenant_id,username),
    FOREIGN KEY (tenant_id,dept_id) REFERENCES sys_dept(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账号';

-- 用户角色关联
CREATE TABLE IF NOT EXISTS sys_user_role (
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY(tenant_id,user_id,role_id),
    FOREIGN KEY(tenant_id,user_id) REFERENCES sys_user(tenant_id,id),
    FOREIGN KEY(tenant_id,role_id) REFERENCES sys_role(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联';

-- 用户岗位关联
CREATE TABLE IF NOT EXISTS sys_user_post (
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    post_id BIGINT NOT NULL COMMENT '岗位ID',
    PRIMARY KEY(tenant_id,user_id,post_id),
    FOREIGN KEY(tenant_id,user_id) REFERENCES sys_user(tenant_id,id),
    FOREIGN KEY(tenant_id,post_id) REFERENCES sys_post(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户岗位关联';

-- 角色权限关联
CREATE TABLE IF NOT EXISTS sys_role_menu (
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单权限ID',
    PRIMARY KEY(tenant_id,role_id,menu_id),
    FOREIGN KEY(tenant_id,role_id) REFERENCES sys_role(tenant_id,id),
    FOREIGN KEY(tenant_id,menu_id) REFERENCES sys_menu(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联';

-- 角色自定义部门范围
CREATE TABLE IF NOT EXISTS sys_role_dept (
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    dept_id BIGINT NOT NULL COMMENT '部门ID',
    PRIMARY KEY(tenant_id,role_id,dept_id),
    FOREIGN KEY(tenant_id,role_id) REFERENCES sys_role(tenant_id,id),
    FOREIGN KEY(tenant_id,dept_id) REFERENCES sys_dept(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色自定义部门范围';

-- 登录页提交的账号申请；密码只保存 BCrypt 摘要，处理后清除。
CREATE TABLE IF NOT EXISTS sys_account_request (
    id BIGINT PRIMARY KEY COMMENT '申请ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    kind VARCHAR(16) NOT NULL COMMENT 'register注册或reset找回密码',
    username VARCHAR(64) NOT NULL COMMENT '用户账号',
    name VARCHAR(64) NOT NULL COMMENT '申请人姓名',
    email VARCHAR(128) NOT NULL COMMENT '联系邮箱，需管理员核实',
    phone VARCHAR(32) NOT NULL COMMENT '联系电话，需管理员核实',
    password_hash VARCHAR(100) NULL COMMENT '注册密码BCrypt摘要，处理后清除',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending待处理/approved已通过/rejected已拒绝',
    note VARCHAR(500) NOT NULL DEFAULT '' COMMENT '处理说明',
    user_id BIGINT NULL COMMENT '关联用户ID，保留历史值',
    handled_by BIGINT NULL COMMENT '处理人ID',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '申请时间，UTC',
    handled_at DATETIME(6) NULL COMMENT '处理时间，UTC',
    KEY ix_account_request_queue(tenant_id,status,created_at),
    KEY ix_account_request_user(tenant_id,kind,username,status),
    FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注册与密码找回申请';

-- 操作审计
CREATE TABLE IF NOT EXISTS sys_audit (
    id BIGINT PRIMARY KEY COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    user_id BIGINT NULL COMMENT '用户ID',
    action VARCHAR(64) NOT NULL COMMENT '操作类型',
    resource VARCHAR(64) NOT NULL COMMENT '资源类型',
    resource_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '历史目标ID',
    trace_id VARCHAR(64) COMMENT '请求追踪编号',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    KEY ix_audit_tenant_time(tenant_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计';

-- 系统配置：每个租户独立保存品牌与认证开关；公开接口只返回指定白名单。
CREATE TABLE IF NOT EXISTS sys_system_config (
 tenant_id BIGINT PRIMARY KEY COMMENT '所属租户',
 system_name VARCHAR(40) NOT NULL DEFAULT 'StarAdmin' COMMENT '系统名称',
 system_logo MEDIUMTEXT NULL COMMENT '校验后的 Logo PNG Data URI；空值使用默认图标',
 system_description VARCHAR(120) NOT NULL DEFAULT '企业级快速开发平台' COMMENT '系统描述',
 captcha_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '登录验证码开关',
 captcha_length INT NOT NULL DEFAULT 4 COMMENT '验证码长度4至6',
 captcha_expire_seconds INT NOT NULL DEFAULT 120 COMMENT '验证码有效期60至300秒',
 registration_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否接受注册申请',
 registration_captcha_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '注册验证码开关',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间UTC',
 FOREIGN KEY(tenant_id) REFERENCES sys_tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户系统配置';
