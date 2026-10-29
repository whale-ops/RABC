package com.star.storage.model;

import jakarta.validation.constraints.*;
import java.util.Map;

// 包职责：接收可新增多条的存储配置和当前上传选择；使用统一版本避免并发覆盖。
// 模块联系：StorageCatalogService 按适配器字段白名单验证 values，空凭据保留当前配置原值。
public record StorageEntryInput(
    @Min(0) long version,
    @NotBlank @Size(max = 60) String name,
    @NotBlank @Size(max = 16) String type,
    boolean enabled,
    @NotNull @Size(max = 16) Map<String, @Size(max = 512) String> values) {
  public record Selection(
      @Min(0) long version, boolean enabled, @Pattern(regexp = "[0-9]{1,20}") String entryId) {}
}
