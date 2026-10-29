package com.star.storage.strategy.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.star.storage.exception.StorageFailure;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 验证本地目录的真实流读写、目录隔离与不完整上传清理，不依赖外部对象存储。
class LocalStorageStrategyTest {
  @TempDir Path temporary;

  @Test
  void localStreamsRoundTripAndRejectTraversal() throws Exception {
    Path root = temporary.resolve("files");
    var strategy = new LocalStorageStrategy(root.toString());
    byte[] payload = new byte[150000];
    new java.security.SecureRandom().nextBytes(payload);
    String key = "tenants/123/2026/10/24/example.txt";
    strategy.put("local", key, new ByteArrayInputStream(payload), payload.length, "text/plain");
    try (var stream = strategy.open("local", key)) {
      assertArrayEquals(payload, stream.readAllBytes());
    }
    assertThrows(StorageFailure.class, () -> strategy.open("local", "tenants/../../outside"));
    assertThrows(StorageFailure.class, () -> strategy.delete("other", key));
    strategy.delete("local", key);
    strategy.delete("local", key);
    assertFalse(Files.exists(root.resolve(key)));
    assertThrows(RuntimeException.class, () -> new LocalStorageStrategy("relative/files"));
  }

  @Test
  void partialUploadDoesNotLeaveFileOrTemporaryData() throws Exception {
    Path root = temporary.resolve("files");
    var strategy = new LocalStorageStrategy(root.toString());
    assertThrows(
        StorageFailure.class,
        () ->
            strategy.put(
                "local",
                "tenants/1/incomplete.txt",
                new ByteArrayInputStream(new byte[] {1}),
                10,
                "text/plain"));
    try (var files = Files.walk(root)) {
      assertEquals(0, files.filter(Files::isRegularFile).count());
    }
  }
}
