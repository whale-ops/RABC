package com.star.storage.config;

import com.star.storage.enums.StorageProvider;
import com.star.storage.model.StorageConfigInput;
import com.star.storage.service.StorageConfigService;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 包职责：第一次升级时把旧环境配置迁入指定租户，后续启动不再覆盖数据库配置。
// 模块联系：只读取本地配置并加密入库，不连接对象存储；已有文件记录绑定原存储位置。
@Component
public class StorageConfigBootstrap implements ApplicationRunner {
  private final StorageProperties properties;
  private final Environment environment;
  private final JdbcTemplate jdbc;
  private final StorageConfigService service;

  public StorageConfigBootstrap(
      StorageProperties properties,
      Environment environment,
      JdbcTemplate jdbc,
      StorageConfigService service) {
    this.properties = properties;
    this.environment = environment;
    this.jdbc = jdbc;
    this.service = service;
  }

  public void run(ApplicationArguments args) {
    String code = environment.getProperty("star.storage.legacy-tenant-code", "star");
    var tenants =
        jdbc.queryForList(
            "SELECT CAST(id AS CHAR) FROM sys_tenant WHERE code=?", String.class, code);
    if (tenants.isEmpty() || !properties.enabled()) return;
    String tenant = tenants.get(0);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_setting WHERE tenant_id=?", Long.class, tenant)
        > 0) return;
    var all = new EnumMap<StorageProvider, StorageConfigInput.Provider>(StorageProvider.class);
    try {
      var c =
          Binder.get(environment)
              .bind("star.storage.aliyun", StorageProperties.Aliyun.class)
              .orElse(null);
      if (c != null && filled(c.endpoint(), c.bucket(), c.accessKeyId(), c.accessKeySecret()))
        all.put(
            StorageProvider.ALIYUN,
            new StorageConfigInput.Provider(
                c.endpoint(), c.region(), c.bucket(), c.accessKeyId(), c.accessKeySecret()));
    } catch (RuntimeException ignored) {
    }
    try {
      var c =
          Binder.get(environment)
              .bind("star.storage.minio", StorageProperties.Minio.class)
              .orElse(null);
      if (c != null && filled(c.endpoint(), c.bucket(), c.accessKey(), c.secretKey()))
        all.put(
            StorageProvider.MINIO,
            new StorageConfigInput.Provider(
                c.endpoint(), c.region(), c.bucket(), c.accessKey(), c.secretKey()));
    } catch (RuntimeException ignored) {
    }
    service.importLegacy(tenant, properties.enabled(), properties.type(), all);
  }

  private boolean filled(String... v) {
    return Arrays.stream(v).allMatch(x -> x != null && !x.isBlank());
  }
}
