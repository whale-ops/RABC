package com.star.storage.enums;

// 包职责：定义存储厂商枚举，保持配置值、策略标识与数据库记录一致。
// 模块联系：供 storage.config 绑定配置、storage.factory 注册策略和 storage.service 路由历史文件。

/**
 * 定义对象存储厂商标识，枚举名称与数据库保存的值保持一致。
 *
 * <p>关联模块：用于 StorageProperties 配置绑定和 StorageStrategyFactory 策略注册；StorageFileService
 * 按文件索引中的厂商读取历史文件。
 */
public enum StorageProvider {
  ALIYUN,
  MINIO,
  S3,
  LOCAL,
  WEBDAV
}
