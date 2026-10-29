package com.star.storage.enums;

import com.star.common.BusinessException;
import java.util.Locale;
import java.util.Set;

// 包职责：定义对象存储分类与厂商标识，保持查询和上传使用相同规则。
// 模块联系：StorageFileService 按分类查询历史索引并校验上传，前端两个管理页面传入分类。

/** 按文件名扩展名分类，兼容已有记录；分类只用于管理展示，不作为文件内容安全判断。 */
public enum StorageCategory {
  ALL,
  IMAGES,
  VIDEOS,
  ATTACHMENTS;

  private static final Set<String> IMAGE_EXTENSIONS =
      Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif");
  private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "ogv", "mov", "m4v");

  public static StorageCategory parse(String value) {
    return switch (value == null ? "all" : value) {
      case "all" -> ALL;
      case "images" -> IMAGES;
      case "videos" -> VIDEOS;
      case "attachments" -> ATTACHMENTS;
      default -> throw BusinessException.bad("文件分类不合法");
    };
  }

  /** 前端选择和后端分页使用相同扩展名范围，已有图片无需修改数据库或重新上传。 */
  public boolean accepts(String name) {
    int dot = name.lastIndexOf('.');
    boolean image =
        dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    boolean video =
        dot >= 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    return switch (this) {
      case ALL -> true;
      case IMAGES -> image;
      case VIDEOS -> video;
      case ATTACHMENTS -> !image && !video;
    };
  }

  public String sqlCondition() {
    if (this == ALL) return "";
    String images =
        "(f.name LIKE '%.%' AND LOWER(SUBSTRING_INDEX(f.name,'.',-1)) IN ('"
            + String.join("','", IMAGE_EXTENSIONS)
            + "'))";
    String videos =
        "(f.name LIKE '%.%' AND LOWER(SUBSTRING_INDEX(f.name,'.',-1)) IN ('"
            + String.join("','", VIDEO_EXTENSIONS)
            + "'))";
    return " AND "
        + switch (this) {
          case IMAGES -> images;
          case VIDEOS -> videos;
          case ATTACHMENTS -> "NOT " + images + " AND NOT " + videos;
          default -> throw new IllegalStateException("未知文件分类");
        };
  }
}
