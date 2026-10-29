package com.star.storage.config;

import static org.junit.jupiter.api.Assertions.*;

import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.factory.StorageStrategyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/** 启动不加载旧环境厂商；未迁移的历史文件按需创建原厂商策略且复用单例。 */
class StorageConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(StorageConfiguration.class, StorageStrategyFactory.class)
          .withPropertyValues("star.storage.enabled=true", "star.storage.max-file-size=20971520");

  @Test
  void aliyunStartupDoesNotLoadMinioAndHistoryLoadsItOnDemand() {
    runner
        .withPropertyValues(
            "star.storage.type=aliyun",
            "star.storage.aliyun.bucket=oss-bucket",
            "star.storage.minio.bucket=minio-bucket")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var beans = context.getSourceApplicationContext().getBeanFactory();
              assertFalse(beans.containsSingleton("aliyunStorageStrategy"));
              assertFalse(beans.containsSingleton("minioStorageStrategy"));
              var factory = context.getBean(StorageStrategyFactory.class);
              assertEquals("oss-bucket", factory.current().bucket());
              assertNull(ReflectionTestUtils.getField(factory.current(), "client"));
              var historical = factory.get(StorageProvider.MINIO);
              assertTrue(beans.containsSingleton("minioStorageStrategy"));
              assertEquals("minio-bucket", historical.bucket());
              assertSame(historical, factory.get(StorageProvider.MINIO));
              assertNull(ReflectionTestUtils.getField(historical, "client"));
              assertNull(ReflectionTestUtils.getField(historical, "http"));
            });
  }

  @Test
  void minioStartupLeavesUnselectedConfigurationUnbound() {
    runner
        .withPropertyValues(
            "star.storage.type=minio",
            "star.storage.minio.bucket=minio-bucket",
            "star.storage.aliyun.endpoint=${UNUSED_OSS_CONFIG}")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var beans = context.getSourceApplicationContext().getBeanFactory();
              assertFalse(beans.containsSingleton("minioStorageStrategy"));
              assertFalse(beans.containsSingleton("aliyunStorageStrategy"));
              assertEquals(
                  StorageProvider.MINIO,
                  context.getBean(StorageStrategyFactory.class).current().provider());
            });
  }

  @Test
  void disabledStorageCreatesNeitherStrategy() {
    runner
        .withPropertyValues("star.storage.enabled=false", "star.storage.type=aliyun")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var beans = context.getSourceApplicationContext().getBeanFactory();
              assertFalse(beans.containsSingleton("aliyunStorageStrategy"));
              assertFalse(beans.containsSingleton("minioStorageStrategy"));
              assertThrows(
                  StorageFailure.class,
                  () -> context.getBean(StorageStrategyFactory.class).current());
            });
  }

  @Test
  void unavailableHistoricProviderDoesNotBreakDefaultStorage() {
    runner
        .withPropertyValues("star.storage.type=aliyun", "star.storage.aliyun.bucket=oss-bucket")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var factory = context.getBean(StorageStrategyFactory.class);
              assertThrows(StorageFailure.class, () -> factory.get(StorageProvider.MINIO).bucket());
              assertEquals("oss-bucket", factory.current().bucket());
            });
  }
}
