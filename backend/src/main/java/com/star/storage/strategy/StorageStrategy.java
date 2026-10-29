package com.star.storage.strategy;

import com.star.storage.enums.StorageProvider;
import java.io.InputStream;

// 包职责：定义统一的对象存储上传、读取和删除契约，屏蔽厂商 SDK 差异。
// 模块联系：由 storage.strategy.impl 实现，经 storage.factory 注册和选择，供 storage.service 调用。

/**
 * 定义对象存储流上传、读取和删除契约，屏蔽 OSS 与 MinIO SDK 差异。不在此层创建 Bucket、修改 ACL 或接收前端指定的服务地址和凭据。
 *
 * <p>关联模块：StorageStrategyFactory 注册和选择实现；StorageFileService 管理权限、对象键与数据库索引，仅通过本接口访问远端存储。
 */
public interface StorageStrategy extends AutoCloseable {
  default void close() {}

  StorageProvider provider();

  String bucket();

  void put(String bucket, String key, InputStream data, long size, String contentType);

  /** 返回的流由调用方关闭；不将私有对象改为公共可读。 */
  InputStream open(String bucket, String key);

  /** 为已授权视频生成限时只读地址，由对象存储直接响应浏览器的 Range 分段请求。 */
  String playbackUrl(String bucket, String key, String contentType, int expiresSeconds);

  /** 删除不存在的对象也视为成功，使数据库失败后的重试可以安全执行。 */
  void delete(String bucket, String key);
}
