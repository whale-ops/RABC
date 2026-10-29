package com.star;


import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 验证应用可启动、MySQL 与 Redis 可连接，并检查健康接口和未登录访问拦截。
 *
 * <p>关联模块：通过随机 HTTP 端口检查 WebConfig、RequestContextFilter 及基础依赖，需注入专用开发配置。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootApplicationIT {
  @Autowired DataSource dataSource;
  @Autowired StringRedisTemplate redis;
  @LocalServerPort int port;

  @Test
  void contextStartsAndDependenciesAreReal() throws Exception {
    try (var connection = dataSource.getConnection()) {
      assertTrue(connection.isValid(3));
      assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
    }
    try (var connection = redis.getConnectionFactory().getConnection()) {
      assertEquals("PONG", connection.ping());
    }
    var http = HttpClient.newHttpClient();
    var health =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, health.statusCode());
    assertTrue(health.body().contains("UP"));
    var protectedResponse =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/system/users"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, protectedResponse.statusCode());
    assertTrue(protectedResponse.headers().firstValue("X-Request-Id").isPresent());
  }
}
