-- 首页访问日统计：按租户、北京时间日期、账号摘要聚合；不保存 URL、IP、姓名或令牌。
-- 前端路由上报后更新 PV，UV 根据账号摘要去重。重复执行不清空已有统计。
CREATE TABLE IF NOT EXISTS sys_visit_daily (
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    stat_date DATE NOT NULL COMMENT '统计日期，Asia/Shanghai',
    visitor_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户与账号ID的SHA256摘要，不返回前端',
    pv BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '页面浏览次数',
    PRIMARY KEY(tenant_id,stat_date,visitor_hash),
    FOREIGN KEY(tenant_id) REFERENCES sys_tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='首页PV和UV日统计';
