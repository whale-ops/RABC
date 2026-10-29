-- 日志表结构：分别保存 AOP 登录事件与操作事件的身份、请求、结果和耗时快照。
-- 关联模块：AuditLogWriter 写入，AuditLogService 查询；用户删除流程会匿名化其历史身份信息。

-- 日志模块建表脚本（MySQL 8）。先执行 schema.sql；可重复执行，不清空已有数据。
-- 两种日志独立存储；不保存密码、令牌、请求正文或响应正文。

CREATE TABLE IF NOT EXISTS sys_login_log (
    id BIGINT PRIMARY KEY COMMENT '日志ID',
    tenant_id BIGINT NULL COMMENT '所属租户；未知租户登录为空',
    tenant_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '租户编码快照',
    user_id BIGINT NULL COMMENT '历史用户ID，不设用户外键',
    username VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户账号快照',
    display_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作人姓名快照',
    module VARCHAR(64) NOT NULL DEFAULT '' COMMENT '所属模块',
    action VARCHAR(40) NOT NULL DEFAULT '' COMMENT '操作类型',
    request_method VARCHAR(10) NOT NULL DEFAULT '' COMMENT 'HTTP请求方法',
    request_path VARCHAR(300) NOT NULL DEFAULT '' COMMENT '请求路径，不含查询参数',
    ip VARCHAR(64) NOT NULL DEFAULT '' COMMENT '直连客户端IP，不信任转发头',
    browser VARCHAR(64) NOT NULL DEFAULT '' COMMENT '浏览器类型',
    os VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作系统',
    user_agent VARCHAR(512) NOT NULL DEFAULT '' COMMENT '客户端标识，截断控制字符',
    status VARCHAR(8) NOT NULL COMMENT '成功或失败',
    http_status INT NOT NULL COMMENT 'HTTP状态码',
    error_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '安全错误码，不保存异常堆栈或原始错误',
    duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '执行耗时，毫秒',
    trace_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '请求追踪编号',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录时间，UTC',
    KEY ix_log_tenant_time(tenant_id,created_at,id),
    KEY ix_log_tenant_user(tenant_id,username,created_at),
    KEY ix_log_tenant_result(tenant_id,status,created_at),
    FOREIGN KEY(tenant_id) REFERENCES sys_tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录日志';

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGINT PRIMARY KEY COMMENT '日志ID',
    tenant_id BIGINT NULL COMMENT '所属租户；未知租户登录为空',
    tenant_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '租户编码快照',
    user_id BIGINT NULL COMMENT '历史用户ID，不设用户外键',
    username VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户账号快照',
    display_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作人姓名快照',
    module VARCHAR(64) NOT NULL DEFAULT '' COMMENT '所属模块',
    action VARCHAR(40) NOT NULL DEFAULT '' COMMENT '操作类型',
    request_method VARCHAR(10) NOT NULL DEFAULT '' COMMENT 'HTTP请求方法',
    request_path VARCHAR(300) NOT NULL DEFAULT '' COMMENT '请求路径，不含查询参数',
    ip VARCHAR(64) NOT NULL DEFAULT '' COMMENT '直连客户端IP，不信任转发头',
    browser VARCHAR(64) NOT NULL DEFAULT '' COMMENT '浏览器类型',
    os VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作系统',
    user_agent VARCHAR(512) NOT NULL DEFAULT '' COMMENT '客户端标识，截断控制字符',
    status VARCHAR(8) NOT NULL COMMENT '成功或失败',
    http_status INT NOT NULL COMMENT 'HTTP状态码',
    error_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '安全错误码，不保存异常堆栈或原始错误',
    duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '执行耗时，毫秒',
    trace_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '请求追踪编号',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录时间，UTC',
    KEY ix_log_tenant_time(tenant_id,created_at,id),
    KEY ix_log_tenant_user(tenant_id,username,created_at),
    KEY ix_log_tenant_result(tenant_id,status,created_at),
    FOREIGN KEY(tenant_id) REFERENCES sys_tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志';

