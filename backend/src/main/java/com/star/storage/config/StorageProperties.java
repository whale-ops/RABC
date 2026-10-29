package com.star.storage.config;

import com.star.storage.enums.StorageProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 包职责：绑定存储公共开关与默认厂商，声明按需绑定的厂商配置结构。
// 模块联系：读取 application.yml 和环境变量，供 storage.factory、storage.service 与策略实现使用。

/**
 * 启动时只绑定公共配置；厂商连接配置由 StorageConfiguration 在策略实例化时单独读取。
 *
 * <p>关联模块：读取 application.yml 与本地环境变量；StorageStrategyFactory 选择默认策略，OSS 和 MinIO 实现读取各自配置。
 */
@ConfigurationProperties("star.storage")
public record StorageProperties(boolean enabled, StorageProvider type, long maxFileSize) {
  public record Aliyun(
      String endpoint, String region, String bucket, String accessKeyId, String accessKeySecret) {}

  public record Minio(
      String endpoint, String region, String bucket, String accessKey, String secretKey) {}
}
