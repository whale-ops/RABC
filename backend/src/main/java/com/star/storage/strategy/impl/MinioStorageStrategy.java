package com.star.storage.strategy.impl;

import com.star.storage.config.StorageProperties;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.strategy.StorageStrategy;
import io.minio.*;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

// 包职责：实现 OSS 和 MinIO 存储适配，封装 SDK 生命周期与安全异常转换。
// 模块联系：由 storage.config 按需绑定 MinIO 配置，storage.factory 通过延迟入口选择策略。

/**
 * 通过 MinIO S3 API 实现上传、读取与删除，封装 SDK 生命周期及安全异常转换；连接 S3 API 端口，通常为 9000。
 *
 * <p>关联模块：由 StorageConfiguration 按需创建并仅接收 MinIO 配置，工厂按当前或历史厂商选择。
 */
public class MinioStorageStrategy implements StorageStrategy {
  private final StorageProperties.Minio settings;
  private final StorageProvider provider;
  private MinioClient client;
  private OkHttpClient http;

  public MinioStorageStrategy(StorageProperties.Minio settings) {
    this(settings, StorageProvider.MINIO);
  }

  public MinioStorageStrategy(StorageProperties.Minio settings, StorageProvider provider) {
    this.settings = settings;
    this.provider = provider;
  }

  public StorageProvider provider() {
    return provider;
  }

  public String bucket() {
    var c = settings;
    StorageFailure.require(c != null && StorageFailure.filled(c.bucket()));
    return c.bucket();
  }

  private synchronized MinioClient client() {
    if (client != null) return client;
    var c = settings;
    StorageFailure.require(
        c != null
            && StorageFailure.filled(c.endpoint())
            && StorageFailure.filled(c.accessKey())
            && StorageFailure.filled(c.secretKey()));
    try {
      http =
          new OkHttpClient.Builder()
              .connectTimeout(5, TimeUnit.SECONDS)
              .readTimeout(30, TimeUnit.SECONDS)
              .writeTimeout(30, TimeUnit.SECONDS)
              .callTimeout(60, TimeUnit.SECONDS)
              .retryOnConnectionFailure(false)
              .build();
      var builder =
          MinioClient.builder()
              .endpoint(c.endpoint())
              .credentials(c.accessKey(), c.secretKey())
              .httpClient(http);
      if (StorageFailure.filled(c.region())) builder.region(c.region());
      return client = builder.build();
    } catch (RuntimeException failure) {
      throw new StorageFailure();
    }
  }

  public void put(String bucket, String key, InputStream data, long size, String contentType) {
    try {
      client()
          .putObject(
              PutObjectArgs.builder().bucket(bucket).object(key).stream(data, size, -1)
                  .contentType(contentType)
                  .build());
    } catch (Exception failure) {
      throw new StorageFailure();
    }
  }

  public InputStream open(String bucket, String key) {
    try {
      return client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception failure) {
      throw new StorageFailure();
    }
  }

  public void delete(String bucket, String key) {
    try {
      client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception failure) {
      throw new StorageFailure();
    }
  }

  /** 临时签名只允许读取指定对象，不暴露密钥、不修改 Bucket 权限。 */
  public String playbackUrl(String bucket, String key, String contentType, int expiresSeconds) {
    try {
      return client()
          .getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(io.minio.http.Method.GET)
                  .bucket(bucket)
                  .object(key)
                  .expiry(expiresSeconds)
                  .extraQueryParams(
                      java.util.Map.of(
                          "response-content-type",
                          contentType,
                          "response-content-disposition",
                          "inline"))
                  .build());
    } catch (Exception failure) {
      throw new StorageFailure();
    }
  }

  @PreDestroy
  public synchronized void close() {
    if (http != null) {
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
    }
  }
}
