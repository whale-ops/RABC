package com.star.storage.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

// 包职责：定义后台存储配置分组输入；空白密钥表示保留原凭据。
// 模块联系：由系统配置存储控制器接收，StorageConfigService 校验管理员、版本和厂商字段。
public record StorageConfigInput(
    @Min(0) long version,
    boolean enabled,
    @NotNull @Pattern(regexp = "aliyun|minio") String provider,
    @Valid @NotNull Provider aliyun,
    @Valid @NotNull Provider minio) {
  public record Provider(
      @Size(max = 255) String endpoint,
      @Size(max = 64) String region,
      @Size(max = 63) String bucket,
      @Size(max = 256) String accessKey,
      @Size(max = 512) String secretKey) {}
}
