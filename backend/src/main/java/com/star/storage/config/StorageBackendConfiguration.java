package com.star.storage.config;

import com.star.storage.enums.StorageProvider;
import com.star.storage.factory.StorageBackendAdapter;
import com.star.storage.factory.StorageBackendAdapter.*;
import com.star.storage.service.StorageConfigService.Connection;
import com.star.storage.strategy.StorageStrategy;
import com.star.storage.strategy.impl.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.*;

// 包职责：声明内置协议适配器及配置字段；增加其他协议时注册独立适配器 Bean。
// 模块联系：通用 S3 复用已验证的 S3 客户端，可填写其他服务器 API 地址；不自动创建 Bucket。
@Configuration(proxyBeanMethods = false)
public class StorageBackendConfiguration {
  private StorageBackendAdapter adapter(
      StorageProvider p,
      String label,
      String description,
      List<Field> fields,
      Function<Connection, StorageStrategy> factory) {
    return new StorageBackendAdapter() {
      public StorageProvider provider() {
        return p;
      }

      public Type type() {
        return new Type(p.name().toLowerCase(Locale.ROOT), label, description, fields);
      }

      public StorageStrategy create(Connection c) {
        return factory.apply(c);
      }
    };
  }

  private List<Field> objectFields(String endpoint, String region) {
    return List.of(
        new Field("endpoint", "API 地址 Endpoint", endpoint, false, true),
        new Field("region", "区域 Region", region, false, true),
        new Field("bucket", "Bucket 名称", "已创建的存储桶", false, true),
        new Field("accessKey", "Access Key / 访问 ID", "首次配置必填", true, true),
        new Field("secretKey", "Secret Key / 访问密钥", "首次配置必填", true, true));
  }

  @Bean
  StorageBackendAdapter ossAdapter() {
    return adapter(
        StorageProvider.ALIYUN,
        "阿里云 OSS",
        "使用 OSS 官方 HTTPS 地域 API 地址。",
        objectFields("https://oss-cn-beijing.aliyuncs.com", "cn-beijing"),
        c ->
            new AliyunOssStorageStrategy(
                new StorageProperties.Aliyun(
                    c.endpoint(), c.region(), c.bucket(), c.accessKey(), c.secretKey())));
  }

  @Bean
  StorageBackendAdapter minioAdapter() {
    return adapter(
        StorageProvider.MINIO,
        "MinIO",
        "可连接任意 MinIO 服务器，填写 S3 API 端口，通常为 9000。",
        objectFields("http://你的服务器:9000", "us-east-1"),
        c ->
            new MinioStorageStrategy(
                new StorageProperties.Minio(
                    c.endpoint(), c.region(), c.bucket(), c.accessKey(), c.secretKey())));
  }

  @Bean
  StorageBackendAdapter s3Adapter() {
    return adapter(
        StorageProvider.S3,
        "通用 S3 兼容存储",
        "适用于 AWS S3 及提供 S3 API 的其他云或自建存储；填写该服务实际的 API 地址和区域。",
        objectFields("https://你的存储 API 地址", "us-east-1"),
        c ->
            new MinioStorageStrategy(
                new StorageProperties.Minio(
                    c.endpoint(), c.region(), c.bucket(), c.accessKey(), c.secretKey()),
                StorageProvider.S3));
  }

  @Bean
  StorageBackendAdapter localAdapter() {
    return adapter(
        StorageProvider.LOCAL,
        "服务器本地目录",
        "文件保存到运行 Java 后端的服务器目录，也可使用已挂载的 NAS 目录；不是浏览器所在电脑的目录。",
        List.of(new Field("endpoint", "服务器绝对目录", "/data/star-files 或 D:\\star-files", false, true)),
        c -> new LocalStorageStrategy(c.endpoint()));
  }

  @Bean
  StorageBackendAdapter webdavAdapter() {
    return adapter(
        StorageProvider.WEBDAV,
        "WebDAV 文件服务器",
        "连接提供 WebDAV 的文件服务器或 NAS，填写已存在的 WebDAV 目录 URL 和账号密码。",
        List.of(
            new Field(
                "endpoint", "WebDAV 目录地址", "https://files.example.com/dav/star/", false, true),
            new Field("accessKey", "WebDAV 用户名", "服务器账号", true, true),
            new Field("secretKey", "WebDAV 密码", "服务器密码或应用密码", true, true)),
        c -> new WebDavStorageStrategy(c.endpoint(), c.accessKey(), c.secretKey()));
  }
}
