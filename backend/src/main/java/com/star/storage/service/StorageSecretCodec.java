package com.star.storage.service;

import com.star.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 包职责：使用部署端独立密钥加密数据库中的存储凭据，绑定租户、连接和字段，防止密文调换。
// 模块联系：仅由 StorageConfigService 在保存与创建存储客户端时使用；不向 API 返回明文或密文。
@Component
public class StorageSecretCodec {
  private final Path path;
  private final JdbcTemplate jdbc;
  private byte[] key;
  private static final SecureRandom RANDOM = new SecureRandom();

  public StorageSecretCodec(
      @Value("${star.storage.config-key-file:.runtime/storage-config.key}") String path,
      JdbcTemplate jdbc) {
    this.path = resolveKeyPath(Path.of("").toAbsolutePath(), path);
    this.jdbc = jdbc;
  }

  /** IDE 从工程根目录启动和脚本从 backend 启动时使用同一密钥文件，避免工作目录变化造成解密失败。 */
  static Path resolveKeyPath(Path workingDirectory, String configured) {
    Path value = Path.of(configured);
    if (value.isAbsolute()) return value.normalize();
    Path base = workingDirectory;
    if (Files.isRegularFile(base.resolve("backend/pom.xml"))) base = base.resolve("backend");
    return base.resolve(value).toAbsolutePath().normalize();
  }

  private synchronized byte[] key() {
    if (key != null) return key;
    try {
      if (!Files.exists(path)) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_storage_profile", Long.class) > 0)
          throw new IllegalStateException();
        Files.createDirectories(path.getParent());
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        try {
          Files.write(path, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          try {
            Files.setPosixFilePermissions(
                path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
          } catch (UnsupportedOperationException ignored) {
          }
        } catch (FileAlreadyExistsException ignored) {
        }
      }
      byte[] value = Files.readAllBytes(path);
      if (value.length != 32) throw new IllegalStateException();
      return key = value;
    } catch (Exception e) {
      throw new BusinessException(503, "STORAGE_KEY_UNAVAILABLE", "存储配置加密密钥不可用，请检查服务端密钥文件");
    }
  }

  public String encrypt(String value, String binding) {
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] packed = new byte[iv.length + data.length];
      System.arraycopy(iv, 0, packed, 0, iv.length);
      System.arraycopy(data, 0, packed, iv.length, data.length);
      return Base64.getEncoder().encodeToString(packed);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public String decrypt(String value, String binding) {
    try {
      byte[] packed = Base64.getDecoder().decode(value);
      if (packed.length < 29) throw new IllegalStateException();
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key(), "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
      cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private BusinessException unavailable() {
    return new BusinessException(503, "STORAGE_KEY_UNAVAILABLE", "存储凭据无法解密，请检查原密钥文件或重新设置凭据");
  }
}
