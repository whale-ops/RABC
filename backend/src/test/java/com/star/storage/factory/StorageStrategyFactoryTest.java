package com.star.storage.factory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.star.storage.config.StorageProperties;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.strategy.StorageStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证策略注册、默认策略切换和禁用行为，防止默认厂商变化影响旧对象。 */
class StorageStrategyFactoryTest {
  StorageProperties properties(boolean enabled, StorageProvider provider) {
    return new StorageProperties(enabled, provider, 20 * 1024 * 1024);
  }

  StorageStrategyDefinition strategy(StorageProvider provider) {
    var strategy = mock(StorageStrategy.class);
    when(strategy.provider()).thenReturn(provider);
    return new StorageStrategyDefinition(provider, () -> strategy);
  }

  @Test
  void routesNewAndHistoricalObjectsIndependently() {
    var aliyun = strategy(StorageProvider.ALIYUN);
    var minio = strategy(StorageProvider.MINIO);
    var factory =
        new StorageStrategyFactory(properties(true, StorageProvider.MINIO), List.of(aliyun, minio));
    assertSame(minio.loader().get(), factory.current());
    assertSame(aliyun.loader().get(), factory.get(StorageProvider.ALIYUN));
  }

  @Test
  void rejectsDisabledMissingAndDuplicateStrategies() {
    var aliyun = strategy(StorageProvider.ALIYUN);
    assertThrows(
        StorageFailure.class,
        () ->
            new StorageStrategyFactory(properties(false, StorageProvider.ALIYUN), List.of(aliyun))
                .current());
    assertThrows(
        StorageFailure.class,
        () ->
            new StorageStrategyFactory(properties(true, StorageProvider.MINIO), List.of(aliyun))
                .current());
    assertThrows(
        IllegalStateException.class,
        () ->
            new StorageStrategyFactory(
                properties(true, StorageProvider.ALIYUN), List.of(aliyun, aliyun)));
  }
}
