package com.star.storage.factory;

import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.service.StorageConfigService;
import com.star.storage.strategy.StorageStrategy;
import java.util.*;
import org.springframework.stereotype.Component;

// 包职责：上传按后台选择路由，历史文件按原连接位置路由；每次请求独立管理 SDK 生命周期。
// 模块联系：StorageFileService 在鉴权后调用，数据库配置立即生效，不关闭正在使用的旧客户端。
@Component
public class StorageRouter {
  private final StorageConfigService config;
  private final StorageClientFactory clients;
  private final StorageStrategyFactory legacy;

  public StorageRouter(
      StorageConfigService config, StorageClientFactory clients, StorageStrategyFactory legacy) {
    this.config = config;
    this.clients = clients;
    this.legacy = legacy;
  }

  public record Access(String profileId, StorageStrategy strategy, boolean owned)
      implements AutoCloseable {
    public void close() {
      if (owned) strategy.close();
    }
  }

  public Access current() {
    var h = config.head(Actor.tenant(), false);
    if (!h.enabled()) throw BusinessException.bad("文件上传未开启，请在系统配置的存储配置中设置");
    String id = config.currentProfile(Actor.tenant());
    if (id == null) throw BusinessException.bad("请先完整配置当前上传存储服务");
    var connection = config.connection(Actor.tenant(), id);
    return new Access(id, clients.create(connection), true);
  }

  public Access forFile(Map<String, Object> row) {
    var provider = StorageProvider.valueOf((String) row.get("provider"));
    Object id = row.get("storage_config_id");
    if (id == null) return new Access(null, legacy.get(provider), false);
    return configured(String.valueOf(id), provider);
  }

  private Access configured(String id, StorageProvider provider) {
    var connection = config.connection(Actor.tenant(), id);
    StorageFailure.require(connection.provider() == provider);
    return new Access(id, clients.create(connection), true);
  }
}
