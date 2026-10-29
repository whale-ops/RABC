package com.star.storage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.storage.config.StorageProperties;
import com.star.storage.enums.StorageProvider;
import com.star.storage.factory.StorageRouter;
import com.star.storage.strategy.StorageStrategy;
import com.star.system.entity.User;
import java.io.InputStream;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** 验证远端上传与本地提交之间的补偿，尤其覆盖数据库提交失败的非原子边界。 */
class StorageFileServiceTest {
  @AfterEach
  void clearActor() {
    Actor.clear();
  }

  @Test
  void commitFailureDeletesOnlyTheNewlyUploadedObject() {
    var jdbc = mock(JdbcTemplate.class);
    var permissions = mock(PermissionService.class);
    var factory = mock(StorageRouter.class);
    var strategy = mock(StorageStrategy.class);
    var manager = mock(PlatformTransactionManager.class);
    var properties = new StorageProperties(true, StorageProvider.ALIYUN, 20_971_520);
    var service =
        new StorageFileService(
            jdbc,
            permissions,
            factory,
            properties,
            new TransactionTemplate(manager),
            mock(StorageMediaService.class));
    var user = new User();
    user.id = "100";
    user.tenantId = "200";
    Actor.set(user);
    when(factory.current()).thenReturn(new StorageRouter.Access(null, strategy, false));
    when(strategy.provider()).thenReturn(StorageProvider.ALIYUN);
    when(strategy.bucket()).thenReturn("test-bucket");
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var failure = new IllegalStateException("commit failed");
    doThrow(failure).when(manager).commit(any());
    var file = new MockMultipartFile("file", "../../report.txt", "text/plain", new byte[] {1});
    assertSame(failure, assertThrows(IllegalStateException.class, () -> service.upload(file)));
    var key = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(strategy)
        .put(
            eq("test-bucket"),
            key.capture(),
            any(InputStream.class),
            eq(1L),
            eq("application/octet-stream"));
    assertTrue(key.getValue().startsWith("tenants/200/"));
    assertFalse(key.getValue().contains("report"));
    verify(strategy).delete("test-bucket", key.getValue());
  }

  @Test
  void sanitizesNamesWithoutUsingClientPathsAsObjectKeys() {
    assertEquals("报告.txt", StorageFileService.filename("C:\\fakepath\\报告.txt"));
    assertEquals("report.txt", StorageFileService.filename("../../report\r\n.txt"));
    assertThrows(BusinessException.class, () -> StorageFileService.filename("../"));
    assertThrows(BusinessException.class, () -> StorageFileService.filename("a".repeat(256)));
  }
}
