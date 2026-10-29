package com.star.storage.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.common.BusinessException;
import com.star.framework.security.*;
import com.star.storage.factory.StorageClientFactory;
import com.star.storage.model.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：按租户维护任意数量的命名存储配置，管理启停、当前上传位置和历史连接快照。
// 模块联系：复用加密连接服务与适配器字段定义；配置移除仅归档入口，历史文件继续使用原连接。
@Service
public class StorageCatalogService {
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final StorageConfigService connections;
  private final StorageClientFactory clients;

  public StorageCatalogService(
      JdbcTemplate jdbc,
      PermissionService permissions,
      StorageConfigService connections,
      StorageClientFactory clients) {
    this.jdbc = jdbc;
    this.permissions = permissions;
    this.connections = connections;
    this.clients = clients;
  }

  public record Entry(
      String id,
      String name,
      String type,
      boolean enabled,
      Map<String, String> values,
      Set<String> secretsSet) {}

  public record View(
      long version,
      boolean enabled,
      String entryId,
      List<Entry> items,
      List<com.star.storage.factory.StorageBackendAdapter.Type> types) {}

  private String authorize(boolean write) {
    permissions.require(write ? "system:config:update" : "system:config:read");
    permissions.requireAdmin();
    return Actor.tenant();
  }

  private String s(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private boolean flag(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue() != 0;
  }

  private Map<String, Object> locked(String tenant) {
    jdbc.update(
        "INSERT INTO sys_storage_setting(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE"
            + " tenant_id=tenant_id",
        tenant);
    var head =
        jdbc.queryForMap("SELECT * FROM sys_storage_setting WHERE tenant_id=? FOR UPDATE", tenant);
    if (!flag(head, "catalog_ready")) {
      String active = null;
      for (String column : List.of("aliyun_profile_id", "minio_profile_id")) {
        if (head.get(column) == null) continue;
        String profile = s(head, column), type = column.startsWith("aliyun") ? "ALIYUN" : "MINIO";
        String id = IdWorker.getIdStr();
        jdbc.update(
            "INSERT INTO sys_storage_entry(id,tenant_id,name,profile_id) VALUES(?,?,?,?)",
            id,
            tenant,
            type.equals("ALIYUN") ? "阿里云 OSS（原配置）" : "MinIO（原配置）",
            profile);
        if (type.equals(s(head, "provider"))) active = id;
      }
      jdbc.update(
          "UPDATE sys_storage_setting SET active_entry_id=?,catalog_ready=TRUE WHERE tenant_id=?",
          active,
          tenant);
      head = jdbc.queryForMap("SELECT * FROM sys_storage_setting WHERE tenant_id=?", tenant);
    }
    return head;
  }

  private void checkVersion(Map<String, Object> head, long version) {
    if (((Number) head.get("version")).longValue() != version)
      throw BusinessException.conflict("存储配置已被其他页面修改，请刷新后重试");
  }

  private void bump(String tenant) {
    jdbc.update("UPDATE sys_storage_setting SET version=version+1 WHERE tenant_id=?", tenant);
  }

  private Map<String, Object> entry(String tenant, String id) {
    if (id == null || !id.matches("[0-9]{1,20}")) throw BusinessException.missing();
    var rows =
        jdbc.queryForList(
            "SELECT * FROM sys_storage_entry WHERE tenant_id=? AND id=? AND deleted=FALSE",
            tenant,
            id);
    if (rows.isEmpty()) throw BusinessException.missing();
    return rows.get(0);
  }

  private View view(String tenant) {
    var head = jdbc.queryForMap("SELECT * FROM sys_storage_setting WHERE tenant_id=?", tenant);
    var items =
        jdbc.query(
            "SELECT e.*,p.provider,p.endpoint,p.region,p.bucket,p.access_cipher,p.secret_cipher"
                + " FROM sys_storage_entry e JOIN sys_storage_profile p ON p.tenant_id=e.tenant_id"
                + " AND p.id=e.profile_id WHERE e.tenant_id=? AND e.deleted=FALSE ORDER BY"
                + " e.created_at,e.id",
            (r, n) ->
                new Entry(
                    r.getString("id"),
                    r.getString("name"),
                    r.getString("provider").toLowerCase(Locale.ROOT),
                    r.getBoolean("enabled"),
                    Map.of(
                        "endpoint",
                        r.getString("endpoint"),
                        "region",
                        r.getString("region"),
                        "bucket",
                        r.getString("bucket")),
                    "LOCAL".equals(r.getString("provider"))
                            || r.getString("access_cipher").isEmpty()
                        ? Set.of()
                        : Set.of("accessKey", "secretKey")),
            tenant);
    return new View(
        ((Number) head.get("version")).longValue(),
        flag(head, "enabled"),
        head.get("active_entry_id") == null ? null : s(head, "active_entry_id"),
        items,
        clients.types());
  }

  @Transactional
  public View read() {
    String tenant = authorize(false);
    locked(tenant);
    return view(tenant);
  }

  @Transactional
  public View save(String id, StorageEntryInput b) {
    String tenant = authorize(true);
    var head = locked(tenant);
    checkVersion(head, b.version());
    var adapter = clients.adapter(b.type());
    Set<String> allowed = new HashSet<>();
    adapter.type().fields().forEach(f -> allowed.add(f.key()));
    if (!allowed.containsAll(b.values().keySet())
        || b.values().values().stream().anyMatch(Objects::isNull))
      throw BusinessException.bad("存储字段与所选协议不一致");
    String name = b.name().trim();
    if (name.isEmpty()) throw BusinessException.bad("请填写配置名称");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_entry WHERE tenant_id=? AND deleted=FALSE AND name=?"
                + " AND id<>?",
            Long.class,
            tenant,
            name,
            id == null ? "0" : id)
        > 0) throw BusinessException.bad("配置名称已存在");
    Map<String, Object> previous = id == null ? null : entry(tenant, id);
    String previousId = previous == null ? null : s(previous, "profile_id");
    if (previousId != null
        && !adapter
            .provider()
            .name()
            .equals(
                jdbc.queryForObject(
                    "SELECT provider FROM sys_storage_profile WHERE tenant_id=? AND id=?",
                    String.class,
                    tenant,
                    previousId))) previousId = null;
    var v = b.values();
    String profile =
        connections.saveProvider(
            tenant,
            adapter.provider(),
            new StorageConfigInput.Provider(
                v.get("endpoint"),
                v.get("region"),
                v.get("bucket"),
                v.get("accessKey"),
                v.get("secretKey")),
            previousId);
    if (profile == null) throw BusinessException.bad("请完整填写存储配置");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_storage_entry WHERE tenant_id=? AND profile_id=? AND"
                + " deleted=FALSE AND id<>?",
            Long.class,
            tenant,
            profile,
            id == null ? "0" : id)
        > 0) throw BusinessException.bad("该连接位置已存在，请编辑已有配置或填写其他服务器地址");
    if (previous == null) {
      id = IdWorker.getIdStr();
      jdbc.update(
          "INSERT INTO sys_storage_entry(id,tenant_id,name,profile_id,enabled) VALUES(?,?,?,?,?)",
          id,
          tenant,
          name,
          profile,
          b.enabled());
    } else
      jdbc.update(
          "UPDATE sys_storage_entry SET name=?,profile_id=?,enabled=? WHERE tenant_id=? AND id=?",
          name,
          profile,
          b.enabled(),
          tenant,
          id);
    // 关闭当前连接会明确暂停新上传，不自动切换其他服务器；已有文件始终按原 profile 读取。
    if (id.equals(s(head, "active_entry_id")) && !b.enabled())
      jdbc.update("UPDATE sys_storage_setting SET enabled=FALSE WHERE tenant_id=?", tenant);
    bump(tenant);
    return view(tenant);
  }

  @Transactional
  public View select(StorageEntryInput.Selection b) {
    String tenant = authorize(true);
    var head = locked(tenant);
    checkVersion(head, b.version());
    if (b.entryId() != null) {
      var target = entry(tenant, b.entryId());
      if (!flag(target, "enabled")) throw BusinessException.bad("请先启用这条存储配置");
    } else if (b.enabled()) throw BusinessException.bad("请先新增并选择上传存储配置");
    jdbc.update(
        "UPDATE sys_storage_setting SET enabled=?,active_entry_id=?,version=version+1 WHERE"
            + " tenant_id=?",
        b.enabled(),
        b.entryId(),
        tenant);
    return view(tenant);
  }

  @Transactional
  public View remove(String id, long version) {
    String tenant = authorize(true);
    var head = locked(tenant);
    checkVersion(head, version);
    entry(tenant, id);
    if (id.equals(s(head, "active_entry_id"))) {
      if (flag(head, "enabled")) throw BusinessException.bad("该配置正在用于上传，请先切换存储或关闭文件上传");
      jdbc.update("UPDATE sys_storage_setting SET active_entry_id=NULL WHERE tenant_id=?", tenant);
    }
    jdbc.update("UPDATE sys_storage_entry SET deleted=TRUE WHERE tenant_id=? AND id=?", tenant, id);
    bump(tenant);
    return view(tenant);
  }
}
