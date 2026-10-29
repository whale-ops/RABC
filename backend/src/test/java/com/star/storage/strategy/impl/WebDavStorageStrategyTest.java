package com.star.storage.strategy.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.star.storage.exception.StorageFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;

// 使用本地 WebDAV 协议桩验证远程目录前缀、凭据、流读写及失败处理，不访问真实 NAS。
class WebDavStorageStrategyTest {
  @Test
  void webDavStreamsWithinConfiguredDirectoryAndRejectsRedirects() throws Exception {
    String encoded = WebDavStorageStrategy.validateEndpoint("https://files.example.com/公司文件/");
    assertEquals(encoded, WebDavStorageStrategy.validateEndpoint(encoded));
    assertThrows(
        RuntimeException.class,
        () -> WebDavStorageStrategy.validateEndpoint("http://example.com/dav/%2e%2e/private/"));
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var files = new HashMap<String, byte[]>();
    var requests = new ArrayList<String>();
    var auth = new java.util.concurrent.atomic.AtomicBoolean(true);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    "test-user:test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          requests.add(exchange.getRequestMethod() + " " + path);
          if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization")))
            auth.set(false);
          if (path.contains("redirect")) {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/");
            exchange.sendResponseHeaders(302, -1);
          } else if (exchange.getRequestMethod().equals("MKCOL"))
            exchange.sendResponseHeaders(201, -1);
          else if (exchange.getRequestMethod().equals("PUT")) {
            files.put(path, exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(201, -1);
          } else if (exchange.getRequestMethod().equals("GET") && files.containsKey(path)) {
            byte[] bytes = files.get(path);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } else if (exchange.getRequestMethod().equals("DELETE")) {
            exchange.sendResponseHeaders(files.remove(path) == null ? 404 : 204, -1);
          } else exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    try {
      var strategy =
          new WebDavStorageStrategy(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/star/",
              "test-user",
              "test-secret");
      byte[] bytes = "WebDAV round trip".getBytes();
      String key = "tenants/123/2026/file.txt";
      strategy.put(
          "webdav", key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
      try (var stream = strategy.open("webdav", key)) {
        assertArrayEquals(bytes, stream.readAllBytes());
      }
      assertTrue(files.containsKey("/dav/star/" + key));
      assertTrue(auth.get());
      strategy.delete("webdav", key);
      strategy.delete("webdav", key);
      assertTrue(files.isEmpty());
      assertThrows(StorageFailure.class, () -> strategy.open("webdav", "tenants/../outside"));
      assertThrows(StorageFailure.class, () -> strategy.open("webdav", "tenants/123/redirect"));
      assertTrue(requests.stream().allMatch(p -> p.contains(" /dav/star/")));
      assertThrows(
          RuntimeException.class,
          () -> WebDavStorageStrategy.validateEndpoint("http://user:password@example.com/dav"));
      assertThrows(
          RuntimeException.class,
          () -> WebDavStorageStrategy.validateEndpoint("http://example.com/dav/../private/"));
    } finally {
      server.stop(0);
    }
  }
}
