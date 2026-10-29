package com.star.storage.factory;

import com.star.storage.enums.StorageProvider;
import com.star.storage.service.StorageConfigService.Connection;
import com.star.storage.strategy.StorageStrategy;
import java.util.List;

// 包职责：声明存储类型、后台表单字段和客户端构造契约。
// 模块联系：Spring 自动注册适配器，管理页面从 types API 渲染字段，无需增加固定厂商表单。
public interface StorageBackendAdapter {
  record Field(String key, String label, String placeholder, boolean secret, boolean required) {}

  record Type(String value, String label, String description, List<Field> fields) {}

  StorageProvider provider();

  Type type();

  StorageStrategy create(Connection connection);
}
