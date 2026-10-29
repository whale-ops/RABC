package com.star.storage.strategy.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import com.star.storage.config.StorageProperties;
import com.star.storage.exception.StorageFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证两个厂商的流读写适配；MinIO 使用本地 S3 HTTP 协议桩，不访问真实云文件。 */
class StorageStrategiesTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = com.star.storage.enums.StorageProvider.class,
      names = {"MINIO", "S3"})
  void minioUsesSignedS3RequestsForUploadDownloadAndDelete(
      com.star.storage.enums.StorageProvider provider) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var payload = new java.util.concurrent.atomic.AtomicReference<byte[]>();
    var requests = new ArrayList<String>();
    server.createContext(
        "/",
        exchange -> {
          synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            requests.add(exchange.getRequestHeaders().getFirst("Authorization"));
          }
          if (exchange.getRequestMethod().equals("PUT")) {
            payload.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
            exchange.sendResponseHeaders(200, -1);
          } else if (exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, payload.get().length);
            exchange.getResponseBody().write(payload.get());
          } else exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    var properties =
        new StorageProperties.Minio(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "us-east-1",
            "test-bucket",
            "test-access",
            "test-secret-key");
    var strategy = new MinioStorageStrategy(properties, provider);
    assertEquals(provider, strategy.provider());
    try {
      byte[] content = "存储协议测试".getBytes(StandardCharsets.UTF_8);
      strategy.put(
          strategy.bucket(),
          "tenants/1/test.txt",
          new ByteArrayInputStream(content),
          content.length,
          "application/octet-stream");
      try (var stream = strategy.open(strategy.bucket(), "tenants/1/test.txt")) {
        assertArrayEquals(content, stream.readAllBytes());
      }
      strategy.delete(strategy.bucket(), "tenants/1/test.txt");
      String playback =
          strategy.playbackUrl("test-bucket", "tenants/1/test.webm", "video/webm", 900);
      assertTrue(playback.contains("X-Amz-Expires=900"));
      assertTrue(playback.contains("response-content-type=video%2Fwebm"));
      assertTrue(playback.contains("response-content-disposition=inline"));
      assertEquals(6, requests.size());
      assertEquals("PUT /test-bucket/tenants/1/test.txt", requests.get(0));
      assertEquals("GET /test-bucket/tenants/1/test.txt", requests.get(2));
      assertEquals("DELETE /test-bucket/tenants/1/test.txt", requests.get(4));
      for (int i : List.of(1, 3, 5)) assertTrue(requests.get(i).startsWith("AWS4-HMAC-SHA256 "));
    } finally {
      strategy.close();
      server.stop(0);
    }
  }

  @Test
  void aliyunPassesExactBucketKeySizeAndStreamAndSanitizesErrors() throws Exception {
    var properties =
        new StorageProperties.Aliyun(
            "https://oss-cn-beijing.aliyuncs.com", "cn-beijing", "bucket", "id", "secret");
    var strategy = new AliyunOssStorageStrategy(properties);
    var client = mock(OSS.class);
    ReflectionTestUtils.setField(strategy, "client", client);
    var content = new ByteArrayInputStream(new byte[] {1, 2, 3});
    strategy.put("old-bucket", "tenants/1/test", content, 3, "application/octet-stream");
    var metadata = org.mockito.ArgumentCaptor.forClass(ObjectMetadata.class);
    verify(client)
        .putObject(eq("old-bucket"), eq("tenants/1/test"), same(content), metadata.capture());
    assertEquals(3, metadata.getValue().getContentLength());
    var object = new OSSObject();
    object.setObjectContent(content);
    when(client.getObject("old-bucket", "tenants/1/test")).thenReturn(object);
    assertSame(content, strategy.open("old-bucket", "tenants/1/test"));
    strategy.delete("old-bucket", "tenants/1/test");
    verify(client).deleteObject("old-bucket", "tenants/1/test");
    var signedRequest =
        org.mockito.ArgumentCaptor.forClass(com.aliyun.oss.model.GeneratePresignedUrlRequest.class);
    try {
      when(client.generatePresignedUrl(any(com.aliyun.oss.model.GeneratePresignedUrlRequest.class)))
          .thenReturn(new java.net.URI("https://bucket.example/video?signature=test").toURL());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    assertTrue(
        strategy
            .playbackUrl("old-bucket", "tenants/1/movie.mp4", "video/mp4", 900)
            .startsWith("https://"));
    verify(client).generatePresignedUrl(signedRequest.capture());
    assertEquals("old-bucket", signedRequest.getValue().getBucketName());
    assertEquals("video/mp4", signedRequest.getValue().getResponseHeaders().getContentType());
    when(client.getObject("old-bucket", "bad"))
        .thenThrow(new RuntimeException("sensitive-sdk-details"));
    var error = assertThrows(StorageFailure.class, () -> strategy.open("old-bucket", "bad"));
    assertFalse(error.getMessage().contains("sensitive"));
    assertNull(error.getCause());
    strategy.close();
    verify(client).shutdown();
  }
}
