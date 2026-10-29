package com.star.storage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.star.common.BusinessException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验证密钥跨重启复用、租户字段绑定及 IDE/脚本工作目录一致性；只在临时目录创建测试密钥。 */
class StorageSecretCodecTest {
  @TempDir Path directory;

  @Test
  void encryptedValuesRequireOriginalKeyAndBindingAndCanSurviveRestart() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_profile", Long.class))
        .thenReturn(0L);
    var key = directory.resolve("key.bin");
    var first = new StorageSecretCodec(key.toString(), jdbc);
    String encrypted = first.encrypt("test-only-secret", "tenant-a:1:secret");
    assertFalse(encrypted.contains("test-only-secret"));
    var restarted = new StorageSecretCodec(key.toString(), jdbc);
    assertEquals("test-only-secret", restarted.decrypt(encrypted, "tenant-a:1:secret"));
    assertThrows(BusinessException.class, () -> restarted.decrypt(encrypted, "tenant-b:1:secret"));
    assertThrows(BusinessException.class, () -> restarted.decrypt(encrypted, "tenant-a:1:access"));
    Files.delete(key);
    when(jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_profile", Long.class))
        .thenReturn(1L);
    var missing = new StorageSecretCodec(key.toString(), jdbc);
    assertThrows(BusinessException.class, () -> missing.encrypt("new", "binding"));
    assertFalse(Files.exists(key));
  }

  @Test
  void moduleAndWorkspaceStartupResolveTheSameKeyFile() throws Exception {
    Path module = directory.resolve("backend");
    Files.createDirectories(module);
    Files.writeString(module.resolve("pom.xml"), "test");
    assertEquals(
        StorageSecretCodec.resolveKeyPath(directory, ".runtime/storage-config.key"),
        StorageSecretCodec.resolveKeyPath(module, ".runtime/storage-config.key"));
  }
}
