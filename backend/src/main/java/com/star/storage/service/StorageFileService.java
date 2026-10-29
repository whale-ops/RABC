package com.star.storage.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.storage.config.StorageProperties;
import com.star.storage.enums.StorageCategory;
import com.star.storage.exception.StorageFailure;
import com.star.storage.factory.StorageRouter;
import com.star.storage.model.StorageDownload;
import com.star.storage.model.StorageFilePage;
import com.star.storage.model.StorageFileView;
import com.star.storage.strategy.StorageStrategy;
import java.io.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

// 包职责：处理文件权限、租户隔离、对象命名、数据库索引和上传失败补偿。
// 模块联系：被 storage.controller 调用，通过 framework.security 鉴权、storage.factory 按后台配置与历史连接访问远端对象。

/**
 * 处理文件业务：校验 RBAC 与租户、生成对象名、维护 MySQL 文件索引并执行上传失败补偿。租户管理员管理本租户文件，普通用户需要对应权限且仅能访问本人文件。
 *
 * <p>关联模块：StorageFileController 调用本服务；PermissionService 校验权限，StorageRouter
 * 委托策略操作远端对象。历史文件按自身连接位置、provider 和 bucket 路由。
 */
@Service
public class StorageFileService {
  private static final Logger log = LoggerFactory.getLogger(StorageFileService.class);
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final StorageRouter router;
  private final StorageProperties properties;
  private final TransactionTemplate transactions;
  private final StorageMediaService media;

  public StorageFileService(
      JdbcTemplate jdbc,
      PermissionService permissions,
      StorageRouter router,
      StorageProperties properties,
      TransactionTemplate transactions,
      StorageMediaService media) {
    this.jdbc = jdbc;
    this.permissions = permissions;
    this.router = router;
    this.properties = properties;
    this.transactions = transactions;
    this.media = media;
  }

  public StorageFilePage list(String keyword, int page, int pageSize) {
    return list(keyword, page, pageSize, "all");
  }

  /** 分类条件先应用于数据库查询，列表、数量和总大小保持一致。 */
  public StorageFilePage list(String keyword, int page, int pageSize, String category) {
    permissions.require("storage:file:list");
    var fileCategory = StorageCategory.parse(category);
    if (keyword == null
        || keyword.length() > 100
        || page < 1
        || page > 100000
        || pageSize < 1
        || pageSize > 100) throw BusinessException.bad("文件查询条件不合法");
    var args = new ArrayList<Object>();
    String where = scope(args) + fileCategory.sqlCondition();
    where += " AND f.name LIKE ? ESCAPE '!'";
    args.add("%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    var summary =
        jdbc.queryForMap(
            "SELECT COUNT(*) total,COALESCE(SUM(f.size),0) totalSize"
                + " FROM sys_storage_file f WHERE "
                + where,
            args.toArray());
    args.add(pageSize);
    args.add((page - 1) * pageSize);
    var rows =
        jdbc.queryForList(
            select() + " WHERE " + where + " ORDER BY f.created_at DESC,f.id DESC LIMIT ? OFFSET ?",
            args.toArray());
    return new StorageFilePage(
        rows.stream().map(this::view).toList(),
        ((Number) summary.get("total")).longValue(),
        ((Number) summary.get("totalSize")).longValue());
  }

  /** 不使用原文件名作为对象键，避免路径穿越、同名覆盖与跨租户键冲突。 */
  static String filename(String original) {
    if (original == null) throw BusinessException.bad("文件名不能为空");
    String name = original.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
    if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255)
      throw BusinessException.bad("文件名不合法或超过 255 个字符");
    return name;
  }

  public StorageFileView upload(MultipartFile file) {
    return upload(file, "all");
  }

  /** 拒绝选错分类的文件，在访问远端存储之前完成检查。 */
  public StorageFileView upload(MultipartFile file, String category) {
    permissions.require("storage:file:upload");
    var fileCategory = StorageCategory.parse(category);
    if (file == null || file.isEmpty() || file.getSize() > properties.maxFileSize())
      throw BusinessException.bad("请选择非空文件，且大小不能超过配置的上传上限");
    String name = filename(file.getOriginalFilename());
    if (!fileCategory.accepts(name))
      throw BusinessException.bad(
          switch (fileCategory) {
            case IMAGES -> "图片管理仅支持 PNG、JPG、JPEG、GIF、WEBP、BMP、AVIF";
            case VIDEOS -> "视频管理仅支持 MP4、WebM、OGV、MOV、M4V";
            default -> "图片或视频请到对应的管理页面上传";
          });
    String suffix =
        name.contains(".")
            ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "bin";
    if (!suffix.matches("[a-z0-9]{1,10}")) suffix = "bin";
    String key =
        "tenants/"
            + Actor.tenant()
            + "/"
            + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            + "/"
            + UUID.randomUUID()
            + "."
            + suffix;
    try (var access = router.current()) {
      var strategy = access.strategy();
      String bucket = strategy.bucket();
      String id = IdWorker.getIdStr();
      // 对象使用二进制内容类型并经鉴权接口附件下载，不以内联网页方式执行用户文件。
      try (var stream = file.getInputStream()) {
        strategy.put(bucket, key, stream, file.getSize(), "application/octet-stream");
      } catch (IOException | RuntimeException failure) {
        // 上传超时也可能已在远端成功，只清理本次生成的随机键，不触碰其他文件。
        compensate(strategy, bucket, key);
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new StorageFailure();
      }
      try {
        // TransactionTemplate 返回时已完成提交，提交失败同样进入补偿分支。
        transactions.executeWithoutResult(
            status ->
                jdbc.update(
                    "INSERT INTO"
                        + " sys_storage_file(id,tenant_id,name,size,provider,bucket,object_key,created_by,storage_config_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    id,
                    Actor.tenant(),
                    name,
                    file.getSize(),
                    strategy.provider().name(),
                    bucket,
                    key,
                    Actor.user().id,
                    access.profileId()));
      } catch (RuntimeException failure) {
        compensate(strategy, bucket, key);
        throw failure;
      }
      return view(find(id, false));
    }
  }

  private void compensate(StorageStrategy strategy, String bucket, String key) {
    try {
      strategy.delete(bucket, key);
    } catch (RuntimeException cleanupFailure) {
      // 不记录 SDK 原始异常；保留精确对象键便于管理员排查极端情况下的孤立对象。
      log.error(
          "文件上传补偿失败：provider={}, bucket={}, key={}, traceId={}",
          strategy.provider(),
          bucket,
          key,
          MDC.get("traceId"));
    }
  }

  /** 签发视频播放地址前检查当前身份，只允许访问已授权的视频记录。 */
  public com.star.storage.model.StoragePlayback playback(String id) {
    permissions.require("storage:file:download");
    var row = find(id, false);
    String name = (String) row.get("name");
    if (!StorageCategory.VIDEOS.accepts(name)) throw BusinessException.bad("该文件不是可预览的视频");
    String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    String contentType =
        switch (extension) {
          case "webm" -> "video/webm";
          case "ogv" -> "video/ogg";
          case "mov" -> "video/quicktime";
          default -> "video/mp4";
        };
    int seconds = 900;
    if (Set.of("LOCAL", "WEBDAV").contains(row.get("provider"))) {
      String url =
          media.issue(
              Actor.tenant(),
              id,
              String.valueOf(row.get("storage_config_id")),
              contentType,
              seconds);
      return new com.star.storage.model.StoragePlayback(
          url, System.currentTimeMillis() + seconds * 1000L);
    }
    try (var access = router.forFile(row)) {
      String url =
          access
              .strategy()
              .playbackUrl(
                  (String) row.get("bucket"), (String) row.get("object_key"), contentType, seconds);
      return new com.star.storage.model.StoragePlayback(
          url, System.currentTimeMillis() + seconds * 1000L);
    }
  }

  /** 下载仍走后端鉴权流，不因视频预览优化而改变原下载行为。 */
  public StorageDownload download(String id) {
    permissions.require("storage:file:download");
    var row = find(id, false);
    var access = router.forFile(row);
    try {
      var stream =
          access.strategy().open((String) row.get("bucket"), (String) row.get("object_key"));
      var managed =
          new FilterInputStream(stream) {
            @Override
            public void close() throws IOException {
              try {
                super.close();
              } finally {
                access.close();
              }
            }
          };
      return new StorageDownload(
          (String) row.get("name"), ((Number) row.get("size")).longValue(), managed);
    } catch (RuntimeException failure) {
      access.close();
      throw failure;
    }
  }

  /** 先删除远端对象再删索引；远端失败保留记录。索引删除失败可重试，策略删除操作幂等。 */
  public void delete(String id) {
    permissions.require("storage:file:delete");
    transactions.executeWithoutResult(
        status -> {
          var row = find(id, true);
          try (var access = router.forFile(row)) {
            access.strategy().delete((String) row.get("bucket"), (String) row.get("object_key"));
            jdbc.update(
                "DELETE FROM sys_storage_file WHERE tenant_id=? AND id=?", Actor.tenant(), id);
          }
        });
  }

  /** 用户明确选择仅移除记录时，不解析存储配置、不调用远端；沿用删除权限和租户、所有者隔离。 */
  public void deleteRecord(String id) {
    permissions.require("storage:file:delete");
    transactions.executeWithoutResult(
        status -> {
          find(id, true);
          jdbc.update(
              "DELETE FROM sys_storage_file WHERE tenant_id=? AND id=?", Actor.tenant(), id);
        });
  }

  private Map<String, Object> find(String id, boolean lock) {
    if (id == null || !id.matches("[0-9]{1,20}")) throw BusinessException.missing();
    var args = new ArrayList<Object>();
    String where = scope(args);
    args.add(id);
    var rows =
        jdbc.queryForList(
            select() + " WHERE " + where + " AND f.id=?" + (lock ? " FOR UPDATE" : ""),
            args.toArray());
    if (rows.isEmpty()) throw BusinessException.missing();
    return rows.get(0);
  }

  private String scope(List<Object> args) {
    args.add(Actor.tenant());
    if (permissions.admin(Actor.user())) return "f.tenant_id=?";
    args.add(Actor.user().id);
    return "f.tenant_id=? AND f.created_by=?";
  }

  private String select() {
    // 上传人名称实时关联用户表，删除账号后不会继续泄露历史个人信息。
    return "SELECT f.*,CAST(f.id AS CHAR) fileId,COALESCE(u.name,'已删除用户') owner FROM"
        + " sys_storage_file f LEFT JOIN sys_user u ON u.tenant_id=f.tenant_id AND"
        + " u.id=f.created_by";
  }

  private StorageFileView view(Map<String, Object> row) {
    Object time = row.get("created_at");
    Instant instant =
        time instanceof Timestamp t
            ? t.toInstant()
            : ((LocalDateTime) time).toInstant(ZoneOffset.UTC);
    return new StorageFileView(
        (String) row.get("fileId"),
        (String) row.get("name"),
        ((Number) row.get("size")).longValue(),
        (String) row.get("owner"),
        instant.toString(),
        ((String) row.get("provider")).toLowerCase(Locale.ROOT));
  }
}
