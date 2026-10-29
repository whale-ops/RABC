package com.star.storage.factory;

import com.star.storage.config.StorageProperties;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.strategy.StorageStrategy;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 包职责：自动注册并选择对象存储策略，隔离业务层对厂商实现的依赖。
// 模块联系：读取 storage.config，向 storage.service 提供 storage.strategy，兼顾默认配置和历史文件厂商。

/**
 * 保留旧环境配置的延迟入口，仅为未迁移且没有连接 ID 的历史文件提供兼容读取。
 *
 * <p>关联模块：StorageRouter 只在历史文件没有配置 ID 时调用 get；新上传和已迁移文件完全使用数据库存储连接。
 */
@Component
public class StorageStrategyFactory {
  private static final Logger log = LoggerFactory.getLogger(StorageStrategyFactory.class);
  private final StorageProperties properties;
  private final Map<StorageProvider, StorageStrategyDefinition> definitions =
      new EnumMap<>(StorageProvider.class);
  private final Map<StorageProvider, StorageStrategy> strategies = new ConcurrentHashMap<>();

  public StorageStrategyFactory(
      StorageProperties properties, List<StorageStrategyDefinition> implementations) {
    this.properties = properties;
    for (var strategy : implementations)
      if (definitions.putIfAbsent(strategy.provider(), strategy) != null)
        throw new IllegalStateException("存储策略重复注册：" + strategy.provider());
  }

  /** 注册旧配置兼容入口，不创建策略或连接对象存储。 */
  @PostConstruct
  public void initializeDefault() {
    if (properties.enabled()) {
      log.info("存储策略已注册：上传使用后台配置，旧环境配置仅在访问未迁移历史文件时加载");
    }
  }

  public StorageStrategy current() {
    return get(properties.type());
  }

  public StorageStrategy get(StorageProvider provider) {
    StorageFailure.require(properties.enabled() && provider != null);
    StorageFailure.require(definitions.containsKey(provider));
    return strategies.computeIfAbsent(
        provider,
        key -> {
          try {
            var strategy = definitions.get(key).loader().get();
            StorageFailure.require(strategy != null && strategy.provider() == key);
            return strategy;
          } catch (RuntimeException failure) {
            // 配置绑定异常可能携带原始属性值，转为安全异常，不回显凭据。
            throw new StorageFailure();
          }
        });
  }
}
