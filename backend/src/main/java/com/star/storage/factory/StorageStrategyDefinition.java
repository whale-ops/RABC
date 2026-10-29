package com.star.storage.factory;

import com.star.storage.enums.StorageProvider;
import com.star.storage.strategy.StorageStrategy;
import java.util.function.Supplier;

// 包职责：登记厂商标识和延迟创建入口，不持有未使用厂商的连接配置或 SDK 客户端。
// 模块联系：StorageConfiguration 提供定义，StorageStrategyFactory 在选中厂商时调用。
public record StorageStrategyDefinition(
    StorageProvider provider, Supplier<StorageStrategy> loader) {}
