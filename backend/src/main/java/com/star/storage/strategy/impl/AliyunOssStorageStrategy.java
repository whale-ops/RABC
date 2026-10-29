package com.star.storage.strategy.impl;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ObjectMetadata;
import com.star.storage.config.StorageProperties;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.strategy.StorageStrategy;
import jakarta.annotation.PreDestroy;
import java.io.*;

// 包职责：实现 OSS 和 MinIO 存储适配，封装 SDK 生命周期与安全异常转换。
// 模块联系：由 storage.config 按需绑定 OSS 配置，storage.factory 通过延迟入口选择策略。

/**
 * 使用配置地域的 OSS V4 签名实现上传、读取与删除，封装 SDK 生命周期及安全异常转换。
 *
 * <p>关联模块：由 StorageConfiguration 按需创建，StorageStrategyFactory 选择；仅接收 OSS 配置。
 */
public class AliyunOssStorageStrategy implements StorageStrategy {
  private final StorageProperties.Aliyun settings;
  private OSS client;

  public AliyunOssStorageStrategy(StorageProperties.Aliyun settings) {
    this.settings = settings;
  }

  public StorageProvider provider() {
    return StorageProvider.ALIYUN;
  }

  public String bucket() {
    StorageFailure.require(settings != null && StorageFailure.filled(settings.bucket()));
    return settings.bucket();
  }

  /** 延迟初始化：未选择的厂商可以不配置；单例复用连接，应用关闭时统一释放。 */
  private synchronized OSS client() {
    if (client != null) return client;
    var c = settings;
    StorageFailure.require(
        c != null
            && StorageFailure.filled(c.endpoint())
            && c.endpoint().startsWith("https://")
            && StorageFailure.filled(c.region())
            && StorageFailure.filled(c.accessKeyId())
            && StorageFailure.filled(c.accessKeySecret()));
    try {
      var config = new ClientBuilderConfiguration();
      config.setSignatureVersion(SignVersion.V4);
      config.setConnectionTimeout(5000);
      config.setSocketTimeout(30000);
      config.setMaxErrorRetry(0);
      client =
          OSSClientBuilder.create()
              .endpoint(c.endpoint())
              .region(c.region())
              .credentialsProvider(
                  new DefaultCredentialProvider(c.accessKeyId(), c.accessKeySecret()))
              .clientConfiguration(config)
              .build();
      return client;
    } catch (RuntimeException failure) {
      throw new StorageFailure();
    }
  }

  public void put(String bucket, String key, InputStream data, long size, String contentType) {
    try {
      var metadata = new ObjectMetadata();
      metadata.setContentLength(size);
      metadata.setContentType(contentType);
      client().putObject(bucket, key, data, metadata);
    } catch (RuntimeException failure) {
      throw new StorageFailure();
    }
  }

  public InputStream open(String bucket, String key) {
    try {
      return client().getObject(bucket, key).getObjectContent();
    } catch (RuntimeException failure) {
      throw new StorageFailure();
    }
  }

  public void delete(String bucket, String key) {
    try {
      client().deleteObject(bucket, key);
    } catch (RuntimeException failure) {
      throw new StorageFailure();
    }
  }

  /** 历史 OSS 视频同样采用短期签名，支持存储端直接分段读取。 */
  public String playbackUrl(String bucket, String key, String contentType, int expiresSeconds) {
    try {
      var request =
          new com.aliyun.oss.model.GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
      request.setExpiration(
          new java.util.Date(System.currentTimeMillis() + expiresSeconds * 1000L));
      var headers = new com.aliyun.oss.model.ResponseHeaderOverrides();
      headers.setContentType(contentType);
      headers.setContentDisposition("inline");
      request.setResponseHeaders(headers);
      return client().generatePresignedUrl(request).toString();
    } catch (RuntimeException failure) {
      throw new StorageFailure();
    }
  }

  @PreDestroy
  public synchronized void close() {
    if (client != null) client.shutdown();
  }
}
