-- 文件索引表：关联租户与上传用户，由 StorageFileService 维护；文件二进制保存到 OSS / MinIO。
-- 不保存 AccessKey 或永久访问 URL。可重复执行，不改动既有表和业务数据。

-- 可视化存储配置：连接位置保留历史快照，凭据以 AES-GCM 密文保存，不写入公开系统配置。
CREATE TABLE IF NOT EXISTS sys_storage_profile (
 id BIGINT PRIMARY KEY COMMENT '连接位置ID', tenant_id BIGINT NOT NULL COMMENT '所属租户',
 provider VARCHAR(16) NOT NULL COMMENT '存储协议标识', endpoint VARCHAR(255) NOT NULL COMMENT '存储API地址或服务器目录',
 region VARCHAR(64) NOT NULL COMMENT '区域', bucket VARCHAR(63) NOT NULL COMMENT 'Bucket',
 access_cipher TEXT NOT NULL COMMENT '加密访问ID', secret_cipher TEXT NOT NULL COMMENT '加密访问密钥',
 credential_version BIGINT NOT NULL DEFAULT 0 COMMENT '凭据版本',
 UNIQUE KEY uk_storage_profile_tenant(tenant_id,id),
 UNIQUE KEY uk_storage_profile_location(tenant_id,provider,endpoint,region,bucket),
 FOREIGN KEY(tenant_id) REFERENCES sys_tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户存储连接位置及加密凭据';
CREATE TABLE IF NOT EXISTS sys_storage_setting (
 tenant_id BIGINT PRIMARY KEY COMMENT '所属租户', enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否允许新上传',
 provider VARCHAR(16) NOT NULL DEFAULT 'ALIYUN' COMMENT '当前上传厂商',
 aliyun_profile_id BIGINT NULL COMMENT '当前OSS连接', minio_profile_id BIGINT NULL COMMENT '当前MinIO连接',
 active_entry_id BIGINT NULL COMMENT '当前命名存储配置ID', catalog_ready BOOLEAN NOT NULL DEFAULT FALSE COMMENT '已迁移命名配置列表',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
 FOREIGN KEY(tenant_id) REFERENCES sys_tenant(id),
 FOREIGN KEY(tenant_id,aliyun_profile_id) REFERENCES sys_storage_profile(tenant_id,id),
 FOREIGN KEY(tenant_id,minio_profile_id) REFERENCES sys_storage_profile(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动态上传存储选择';

-- 命名配置与历史位置分离；修改地址仅更新入口，已有文件继续绑定原 profile。
CREATE TABLE IF NOT EXISTS sys_storage_entry (
 id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(60) NOT NULL,
 profile_id BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '仅归档配置入口，保留历史文件连接',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_storage_entry_tenant(tenant_id,id),
 KEY ix_storage_entry_tenant(tenant_id,deleted),
 FOREIGN KEY(tenant_id,profile_id) REFERENCES sys_storage_profile(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='命名存储配置列表';

CREATE TABLE IF NOT EXISTS sys_storage_file (
    id BIGINT PRIMARY KEY COMMENT '文件ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    name VARCHAR(255) NOT NULL COMMENT '清洗后的原文件名',
    size BIGINT NOT NULL COMMENT '文件大小，字节',
    provider VARCHAR(16) NOT NULL COMMENT '上传时的存储协议标识',
    bucket VARCHAR(100) NOT NULL COMMENT '上传时的Bucket，切换配置后仍读取原Bucket',
    object_key VARCHAR(512) NOT NULL COMMENT '含租户前缀的随机对象键',
    created_by BIGINT NOT NULL COMMENT '上传用户ID，删除用户不级联删除文件',
    storage_config_id BIGINT NULL COMMENT '上传时的存储连接位置，旧数据可为空',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '上传时间，UTC',
    UNIQUE KEY uk_storage_object(provider,bucket,object_key),
    KEY ix_storage_tenant_time(tenant_id,created_at,id),
    KEY ix_storage_tenant_owner(tenant_id,created_by,created_at),
    FOREIGN KEY(tenant_id) REFERENCES sys_tenant(id),
    CONSTRAINT fk_storage_file_profile FOREIGN KEY(tenant_id,storage_config_id) REFERENCES sys_storage_profile(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对象存储文件索引';
