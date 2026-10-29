package com.star.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.auth.service.AuthService;
import com.star.common.BusinessException;
import com.star.storage.factory.StorageClientFactory;
import java.io.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 包职责：为本地和 WebDAV 视频签发短时不可猜测的只读票据，支持 Range 且不暴露目录或凭据。
// 模块联系：只由已完成文件鉴权的 StorageFileService 签发；读取时重新检查租户、文件和历史连接仍存在。
@Service
public class StorageMediaService {
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final StorageConfigService config;
  private final StorageClientFactory clients;
  private static final SecureRandom RANDOM = new SecureRandom();

  public StorageMediaService(
      StringRedisTemplate redis,
      ObjectMapper json,
      JdbcTemplate jdbc,
      StorageConfigService config,
      StorageClientFactory clients) {
    this.redis = redis;
    this.json = json;
    this.jdbc = jdbc;
    this.config = config;
    this.clients = clients;
  }

  public record Ticket(String tenant, String file, String profile, String contentType) {}

  public record Media(InputStream stream, long size, String contentType) {}

  private String key(String token) {
    return "star:storage:media:" + AuthService.hash(token);
  }

  public String issue(String tenant, String file, String profile, String type, int seconds) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    try {
      redis
          .opsForValue()
          .set(
              key(token),
              json.writeValueAsString(new Ticket(tenant, file, profile, type)),
              Duration.ofSeconds(seconds));
    } catch (Exception e) {
      throw new BusinessException(503, "MEDIA_UNAVAILABLE", "视频授权暂时不可用，请稍后重试");
    }
    return "/api/v1/storage/media/" + token;
  }

  public Media open(String token) {
    if (token == null || !token.matches("[0-9a-f]{64}")) throw BusinessException.missing();
    Ticket ticket;
    try {
      String value = redis.opsForValue().get(key(token));
      if (value == null) throw BusinessException.missing();
      ticket = json.readValue(value, Ticket.class);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw BusinessException.missing();
    }
    var rows =
        jdbc.queryForList(
            "SELECT size,bucket,object_key FROM sys_storage_file WHERE tenant_id=? AND id=? AND"
                + " storage_config_id=? AND provider IN ('LOCAL','WEBDAV')",
            ticket.tenant(),
            ticket.file(),
            ticket.profile());
    if (rows.isEmpty()) throw BusinessException.missing();
    var row = rows.get(0);
    var connection = config.connection(ticket.tenant(), ticket.profile());
    if (connection.provider() != com.star.storage.enums.StorageProvider.LOCAL
        && connection.provider() != com.star.storage.enums.StorageProvider.WEBDAV)
      throw BusinessException.missing();
    var strategy = clients.create(connection);
    try {
      var stream = strategy.open((String) row.get("bucket"), (String) row.get("object_key"));
      return new Media(
          new FilterInputStream(stream) {
            public void close() throws IOException {
              try {
                super.close();
              } finally {
                strategy.close();
              }
            }
          },
          ((Number) row.get("size")).longValue(),
          ticket.contentType());
    } catch (RuntimeException e) {
      strategy.close();
      throw e;
    }
  }
}
