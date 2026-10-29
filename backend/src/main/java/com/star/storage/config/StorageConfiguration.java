package com.star.storage.config;

import com.star.storage.enums.StorageProvider;
import com.star.storage.factory.StorageStrategyDefinition;
import com.star.storage.strategy.impl.AliyunOssStorageStrategy;
import com.star.storage.strategy.impl.MinioStorageStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

// 包职责：仅为尚未迁移的历史文件按需绑定旧环境配置并管理策略生命周期。
// 模块联系：日常上传使用 StorageRouter 的数据库配置，旧环境工厂只在历史记录没有连接 ID 时使用。
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
  @Bean
  @Lazy
  public AliyunOssStorageStrategy aliyunStorageStrategy(Environment environment) {
    var settings =
        Binder.get(environment)
            .bind("star.storage.aliyun", StorageProperties.Aliyun.class)
            .orElse(null);
    return new AliyunOssStorageStrategy(settings);
  }

  @Bean
  @Lazy
  public MinioStorageStrategy minioStorageStrategy(Environment environment) {
    var settings =
        Binder.get(environment)
            .bind("star.storage.minio", StorageProperties.Minio.class)
            .orElse(null);
    return new MinioStorageStrategy(settings);
  }

  @Bean
  public StorageStrategyDefinition aliyunDefinition(
      ObjectProvider<AliyunOssStorageStrategy> strategy) {
    return new StorageStrategyDefinition(StorageProvider.ALIYUN, strategy::getObject);
  }

  @Bean
  public StorageStrategyDefinition minioDefinition(ObjectProvider<MinioStorageStrategy> strategy) {
    return new StorageStrategyDefinition(StorageProvider.MINIO, strategy::getObject);
  }
}
