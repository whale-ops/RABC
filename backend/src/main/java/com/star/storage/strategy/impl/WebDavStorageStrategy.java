package com.star.storage.strategy.impl;

import com.star.common.BusinessException;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.strategy.StorageStrategy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

// 包职责：通过标准 WebDAV MKCOL、PUT、GET、DELETE 连接其他文件服务器与 NAS。
// 模块联系：只操作配置目录下的租户随机对象键；禁止重定向携带凭据，错误不回显服务器正文。
public class WebDavStorageStrategy implements StorageStrategy {
  private final URI base;
  private final String authorization;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public WebDavStorageStrategy(String endpoint, String user, String password) {
    base = URI.create(validateEndpoint(endpoint));
    authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  public static String validateEndpoint(String value) {
    try {
      URI u = URI.create(value.trim());
      if (!("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
          || u.getHost() == null
          || u.getUserInfo() != null
          || u.getRawQuery() != null
          || u.getFragment() != null
          || u.getPort() == 0
          || u.getPort() > 65535
          || !u.normalize().equals(u)
          || u.getPath() != null && u.getPath().contains("\\"))
        throw new IllegalArgumentException();
      if (u.getPath() != null)
        for (String segment : u.getPath().split("/"))
          if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException();
      return u.toASCIIString().replaceAll("/+$", "") + "/";
    } catch (Exception e) {
      throw BusinessException.bad("请填写有效的 WebDAV HTTP/HTTPS 目录地址，不含账号、查询参数或相对路径");
    }
  }

  public StorageProvider provider() {
    return StorageProvider.WEBDAV;
  }

  public String bucket() {
    return "webdav";
  }

  private HttpRequest.Builder request(String bucket, String key) {
    if (!"webdav".equals(bucket)
        || key == null
        || !key.startsWith("tenants/")
        || !key.matches("[a-zA-Z0-9/_.-]+")) throw new StorageFailure();
    for (String part : key.split("/"))
      if (part.equals(".") || part.equals("..")) throw new StorageFailure();
    return HttpRequest.newBuilder(base.resolve(key))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", authorization);
  }

  public void put(String bucket, String key, InputStream stream, long size, String type) {
    try {
      request(bucket, key);
      String[] parts = key.split("/");
      String collection = "";
      for (int i = 0; i < parts.length - 1; i++) {
        collection += parts[i] + "/";
        int status =
            http.send(
                    request(bucket, collection)
                        .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode();
        if (status != 201 && status != 405) throw new StorageFailure();
      }
      var publisher =
          HttpRequest.BodyPublishers.fromPublisher(
              HttpRequest.BodyPublishers.ofInputStream(() -> stream), size);
      int status =
          http.send(
                  request(bucket, key).header("Content-Type", type).PUT(publisher).build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode();
      if (status != 200 && status != 201 && status != 204) throw new StorageFailure();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new StorageFailure();
    } catch (Exception e) {
      throw new StorageFailure();
    }
  }

  public InputStream open(String bucket, String key) {
    try {
      var response =
          http.send(request(bucket, key).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        response.body().close();
        throw new StorageFailure();
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new StorageFailure();
    } catch (Exception e) {
      throw new StorageFailure();
    }
  }

  public void delete(String bucket, String key) {
    try {
      int status =
          http.send(request(bucket, key).DELETE().build(), HttpResponse.BodyHandlers.discarding())
              .statusCode();
      if (status != 200 && status != 204 && status != 404) throw new StorageFailure();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new StorageFailure();
    } catch (Exception e) {
      throw new StorageFailure();
    }
  }

  public String playbackUrl(String bucket, String key, String type, int seconds) {
    throw new IllegalStateException("WebDAV 视频由鉴权媒体接口读取");
  }
}
