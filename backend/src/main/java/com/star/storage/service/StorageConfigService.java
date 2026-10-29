package com.star.storage.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.common.BusinessException;
import com.star.framework.security.*;
import com.star.storage.enums.StorageProvider;
import com.star.storage.model.StorageConfigInput;
import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：按租户持久化动态存储选择、连接位置及加密凭据；历史文件绑定原连接位置。
// 模块联系：管理员 API 只返回脱敏视图；StorageRouter 每次操作读取最新配置与凭据，无需重启。
@Service
public class StorageConfigService {
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final StorageSecretCodec codec;

  public StorageConfigService(
      JdbcTemplate jdbc, PermissionService permissions, StorageSecretCodec codec) {
    this.jdbc = jdbc;
    this.permissions = permissions;
    this.codec = codec;
  }

  public record Head(
      boolean enabled, StorageProvider provider, String aliyunId, String minioId, long version) {}

  public record Connection(
      String id,
      String tenant,
      StorageProvider provider,
      String endpoint,
      String region,
      String bucket,
      String accessKey,
      String secretKey) {
    @Override
    public String toString() {
      return "StorageConnection[" + provider + ",credentials=redacted]";
    }
  }

  public record ProviderView(
      String endpoint, String region, String bucket, boolean accessKeySet, boolean secretKeySet) {}

  public record View(
      long version, boolean enabled, String provider, ProviderView aliyun, ProviderView minio) {}

  public Head head(String tenant, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT * FROM sys_storage_setting WHERE tenant_id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) ->
                new Head(
                    rs.getBoolean("enabled"),
                    StorageProvider.valueOf(rs.getString("provider")),
                    rs.getString("aliyun_profile_id"),
                    rs.getString("minio_profile_id"),
                    rs.getLong("version")),
            tenant);
    return rows.isEmpty() ? new Head(false, StorageProvider.ALIYUN, null, null, 0) : rows.get(0);
  }

  private Map<String, Object> row(String tenant, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM sys_storage_profile WHERE tenant_id=? AND id=?", tenant, id);
    if (rows.isEmpty()) throw BusinessException.bad("存储连接不存在，请重新配置");
    return rows.get(0);
  }

  private String text(Map<String, Object> row, String field) {
    return Objects.toString(row.get(field), "");
  }

  private String binding(String tenant, String id, String field) {
    return tenant + ":" + id + ":" + field;
  }

  public Connection connection(String tenant, String id) {
    var r = row(tenant, id);
    return new Connection(
        id,
        tenant,
        StorageProvider.valueOf(text(r, "provider")),
        text(r, "endpoint"),
        text(r, "region"),
        text(r, "bucket"),
        "LOCAL".equals(text(r, "provider"))
            ? ""
            : codec.decrypt(text(r, "access_cipher"), binding(tenant, id, "access")),
        "LOCAL".equals(text(r, "provider"))
            ? ""
            : codec.decrypt(text(r, "secret_cipher"), binding(tenant, id, "secret")));
  }

  private ProviderView view(String tenant, String id) {
    if (id == null) return new ProviderView("", "", "", false, false);
    var r = row(tenant, id);
    return new ProviderView(
        text(r, "endpoint"),
        text(r, "region"),
        text(r, "bucket"),
        !text(r, "access_cipher").isEmpty(),
        !text(r, "secret_cipher").isEmpty());
  }

  private View view(String tenant) {
    var h = head(tenant, false);
    return new View(
        h.version(),
        h.enabled(),
        h.provider().name().toLowerCase(Locale.ROOT),
        view(tenant, h.aliyunId()),
        view(tenant, h.minioId()));
  }

  public View read() {
    permissions.require("system:config:read");
    permissions.requireAdmin();
    return view(Actor.tenant());
  }

  @Transactional
  public View save(StorageConfigInput b) {
    permissions.require("system:config:update");
    permissions.requireAdmin();
    String tenant = Actor.tenant();
    jdbc.update(
        "INSERT INTO sys_storage_setting(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE"
            + " tenant_id=tenant_id",
        tenant);
    var h = head(tenant, true);
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT catalog_ready FROM sys_storage_setting WHERE tenant_id=?",
            Boolean.class,
            tenant))) throw BusinessException.conflict("存储配置已升级，请使用配置列表管理");
    if (h.version() != b.version()) throw BusinessException.conflict("存储配置已被其他页面修改，请重新加载后保存");
    String aliyun = saveProvider(tenant, StorageProvider.ALIYUN, b.aliyun(), h.aliyunId()),
        minio = saveProvider(tenant, StorageProvider.MINIO, b.minio(), h.minioId());
    if (b.enabled() && ("aliyun".equals(b.provider()) ? aliyun : minio) == null)
      throw BusinessException.bad("请完整配置当前上传使用的存储服务");
    jdbc.update(
        "UPDATE sys_storage_setting SET"
            + " enabled=?,provider=?,aliyun_profile_id=?,minio_profile_id=?,version=version+1 WHERE"
            + " tenant_id=?",
        b.enabled(),
        b.provider().toUpperCase(Locale.ROOT),
        aliyun,
        minio,
        tenant);
    return view(tenant);
  }

  /** 新目录启用后只按租户选择的命名配置路由；未升级租户保留原连接选择。 */
  public String currentProfile(String tenant) {
    var rows =
        jdbc.queryForList(
            "SELECT catalog_ready,active_entry_id FROM sys_storage_setting WHERE tenant_id=?",
            tenant);
    if (!rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("catalog_ready"))) {
      var selected =
          jdbc.queryForList(
              "SELECT CAST(profile_id AS CHAR) FROM sys_storage_entry WHERE tenant_id=? AND id=?"
                  + " AND enabled=TRUE AND deleted=FALSE",
              String.class,
              tenant,
              rows.get(0).get("active_entry_id"));
      if (selected.isEmpty()) throw BusinessException.bad("请先新增并选择可用的上传存储配置");
      return selected.get(0);
    }
    var h = head(tenant, false);
    return h.provider() == StorageProvider.ALIYUN ? h.aliyunId() : h.minioId();
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private String credential(String entered, Connection previous, boolean secret) {
    return entered == null || entered.isBlank()
        ? (previous == null ? "" : secret ? previous.secretKey() : previous.accessKey())
        : entered;
  }

  public String saveProvider(
      String tenant, StorageProvider provider, StorageConfigInput.Provider b, String previousId) {
    if (trim(b.endpoint()).isEmpty()
        && trim(b.bucket()).isEmpty()
        && trim(b.accessKey()).isEmpty()
        && trim(b.secretKey()).isEmpty()
        && previousId == null) return null;
    var previous =
        previousId == null || (!trim(b.accessKey()).isEmpty() && !trim(b.secretKey()).isEmpty())
            ? null
            : connection(tenant, previousId);
    if (trim(b.endpoint()).length() > 255
        || trim(b.accessKey()).length() > 256
        || trim(b.secretKey()).length() > 512) throw BusinessException.bad("存储地址或凭据长度超出限制");
    if (provider == StorageProvider.LOCAL) {
      String path =
          com.star.storage.strategy.impl.LocalStorageStrategy.validateRoot(b.endpoint()).toString();
      return persist(tenant, provider, path, "", "local", "", "");
    }
    if (provider == StorageProvider.WEBDAV) {
      String access = credential(b.accessKey(), previous, false),
          secret = credential(b.secretKey(), previous, true);
      if (access.isBlank() || secret.isBlank() || access.contains(":"))
        throw BusinessException.bad("请填写 WebDAV 用户名和密码，用户名不能包含冒号");
      return persist(
          tenant,
          provider,
          com.star.storage.strategy.impl.WebDavStorageStrategy.validateEndpoint(b.endpoint()),
          "",
          "webdav",
          access,
          secret);
    }
    String endpoint = endpoint(b.endpoint(), provider),
        region = trim(b.region()),
        bucket = trim(b.bucket());
    if (provider != StorageProvider.ALIYUN && region.isEmpty()) region = "us-east-1";
    if (!region.matches("[a-z0-9-]{1,64}")) throw BusinessException.bad("请填写有效的存储区域");
    if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))
      throw BusinessException.bad("Bucket 需为 3–63 位小写字母、数字、点或短横线");
    String access = credential(b.accessKey(), previous, false),
        secret = credential(b.secretKey(), previous, true);
    if (access.isBlank() || secret.isBlank()) throw BusinessException.bad("首次配置必须填写访问 ID 和访问密钥");
    return persist(tenant, provider, endpoint, region, bucket, access, secret);
  }

  String endpoint(String value, StorageProvider provider) {
    try {
      var u = URI.create(trim(value));
      String scheme = u.getScheme(), host = u.getHost();
      if (host == null
          || !("https".equals(scheme) || "http".equals(scheme))
          || u.getUserInfo() != null
          || u.getRawQuery() != null
          || u.getRawFragment() != null
          || !(u.getPath() == null || u.getPath().isEmpty() || u.getPath().equals("/"))
          || u.getPort() == 0
          || u.getPort() > 65535) throw new IllegalArgumentException();
      if (provider == StorageProvider.ALIYUN
          && (!"https".equals(scheme) || !host.toLowerCase(Locale.ROOT).endsWith(".aliyuncs.com")))
        throw BusinessException.bad("阿里云 OSS 请使用 HTTPS 官方地域 Endpoint（aliyuncs.com）");
      return new URI(scheme, null, host.toLowerCase(Locale.ROOT), u.getPort(), null, null, null)
          .toString();
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw BusinessException.bad("存储地址需为有效的 HTTP/HTTPS API 地址，不包含路径、账号或查询参数");
    }
  }

  private String persist(
      String tenant,
      StorageProvider provider,
      String endpoint,
      String region,
      String bucket,
      String access,
      String secret) {
    var found =
        jdbc.queryForList(
            "SELECT CAST(id AS CHAR) FROM sys_storage_profile WHERE tenant_id=? AND provider=? AND"
                + " endpoint=? AND region=? AND bucket=?",
            String.class,
            tenant,
            provider.name(),
            endpoint,
            region,
            bucket);
    String id = found.isEmpty() ? IdWorker.getIdStr() : found.get(0);
    String a = codec.encrypt(access, binding(tenant, id, "access")),
        s = codec.encrypt(secret, binding(tenant, id, "secret"));
    if (found.isEmpty())
      jdbc.update(
          "INSERT INTO"
              + " sys_storage_profile(id,tenant_id,provider,endpoint,region,bucket,access_cipher,secret_cipher)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          id,
          tenant,
          provider.name(),
          endpoint,
          region,
          bucket,
          a,
          s);
    else
      jdbc.update(
          "UPDATE sys_storage_profile SET"
              + " access_cipher=?,secret_cipher=?,credential_version=credential_version+1 WHERE"
              + " tenant_id=? AND id=?",
          a,
          s,
          tenant,
          id);
    return id;
  }

  /** 仅首次导入指定旧租户的配置；绑定现有文件，不覆盖已在后台保存的选择。 */
  @Transactional
  public void importLegacy(
      String tenant,
      boolean enabled,
      StorageProvider selected,
      Map<StorageProvider, StorageConfigInput.Provider> providers) {
    jdbc.queryForList("SELECT id FROM sys_tenant WHERE id=? FOR UPDATE", tenant);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_setting WHERE tenant_id=?", Long.class, tenant)
        > 0) return;
    var ids = new EnumMap<StorageProvider, String>(StorageProvider.class);
    for (var entry : providers.entrySet()) {
      var p = entry.getKey();
      var b = entry.getValue();
      String id;
      try {
        id = saveProvider(tenant, p, b, null);
      } catch (BusinessException bad) {
        if (bad.status == 400) continue;
        throw bad;
      }
      if (id == null) continue;
      ids.put(p, id);
      var legacyBuckets =
          jdbc.queryForList(
              "SELECT DISTINCT bucket FROM sys_storage_file WHERE tenant_id=? AND provider=? AND"
                  + " storage_config_id IS NULL",
              String.class,
              tenant,
              p.name());
      for (String bucket : legacyBuckets) {
        String historyId =
            persist(
                tenant,
                p,
                endpoint(b.endpoint(), p),
                trim(b.region()).isEmpty() ? "us-east-1" : trim(b.region()),
                bucket,
                b.accessKey(),
                b.secretKey());
        jdbc.update(
            "UPDATE sys_storage_file SET storage_config_id=? WHERE tenant_id=? AND provider=? AND"
                + " bucket=? AND storage_config_id IS NULL",
            historyId,
            tenant,
            p.name(),
            bucket);
      }
    }
    jdbc.update(
        "INSERT INTO"
            + " sys_storage_setting(tenant_id,enabled,provider,aliyun_profile_id,minio_profile_id)"
            + " VALUES(?,?,?,?,?)",
        tenant,
        enabled && ids.containsKey(selected),
        selected.name(),
        ids.get(StorageProvider.ALIYUN),
        ids.get(StorageProvider.MINIO));
  }
}
