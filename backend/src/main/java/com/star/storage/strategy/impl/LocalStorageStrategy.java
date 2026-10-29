package com.star.storage.strategy.impl;

import com.star.common.BusinessException;
import com.star.storage.enums.StorageProvider;
import com.star.storage.exception.StorageFailure;
import com.star.storage.strategy.StorageStrategy;
import java.io.*;
import java.nio.file.*;

// 包职责：在管理员指定的服务器绝对目录中流式保存文件，阻止目录穿越和符号链接逃逸。
// 模块联系：沿用随机租户对象键；下载与删除仍经文件 API 鉴权，本地目录不公开映射为静态资源。
public class LocalStorageStrategy implements StorageStrategy {
  private final Path root;

  public LocalStorageStrategy(String directory) {
    root = validateRoot(directory);
  }

  public static Path validateRoot(String directory) {
    try {
      Path p = Path.of(directory == null ? "" : directory).normalize();
      if (!p.isAbsolute() || p.getParent() == null || directory.length() > 255)
        throw new IllegalArgumentException();
      return p;
    } catch (Exception e) {
      throw BusinessException.bad("请填写服务器上的绝对目录，不能使用磁盘根目录");
    }
  }

  public StorageProvider provider() {
    return StorageProvider.LOCAL;
  }

  public String bucket() {
    return "local";
  }

  private void noLinks(Path p) throws IOException {
    Path part = p.getRoot();
    for (Path segment : p) {
      part = part.resolve(segment);
      if (Files.isSymbolicLink(part)) throw new IOException("symlink");
    }
  }

  private Path target(String bucket, String key) throws IOException {
    if (!"local".equals(bucket)
        || key == null
        || !key.startsWith("tenants/")
        || key.contains("\\")
        || key.contains(":")) throw new IOException("invalid key");
    for (String segment : key.split("/"))
      if (segment.equals(".") || segment.equals("..")) throw new IOException("invalid segment");
    Path target = root.resolve(key).normalize();
    if (!target.startsWith(root) || target.equals(root)) throw new IOException("invalid path");
    noLinks(target);
    return target;
  }

  public void put(String bucket, String key, InputStream data, long size, String type) {
    Path temporary = null;
    try {
      Path target = target(bucket, key);
      Files.createDirectories(target.getParent());
      noLinks(target.getParent());
      temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
      try (var out = Files.newOutputStream(temporary)) {
        data.transferTo(out);
      }
      if (Files.size(temporary) != size) throw new IOException("size mismatch");
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, target);
      }
    } catch (Exception e) {
      throw new StorageFailure();
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }
  }

  public InputStream open(String bucket, String key) {
    try {
      return Files.newInputStream(target(bucket, key), LinkOption.NOFOLLOW_LINKS);
    } catch (Exception e) {
      throw new StorageFailure();
    }
  }

  public void delete(String bucket, String key) {
    try {
      Files.deleteIfExists(target(bucket, key));
    } catch (Exception e) {
      throw new StorageFailure();
    }
  }

  public String playbackUrl(String bucket, String key, String type, int seconds) {
    throw new IllegalStateException("本地视频通过鉴权媒体接口生成播放地址");
  }
}
