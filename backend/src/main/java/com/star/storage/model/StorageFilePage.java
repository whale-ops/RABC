package com.star.storage.model;

import java.util.List;

// 包职责：定义文件展示、分页与下载流模型，不暴露存储内部配置和凭据。
// 模块联系：在 storage.service 与 storage.controller 之间传递数据，展示模型供前端文件页面使用。

/**
 * 封装文件分页列表、符合筛选条件的文件总数及总大小。
 *
 * <p>关联模块：StorageFileService 汇总查询结果，StorageFileController 返回给前端文件管理页面。
 */
public record StorageFilePage(List<StorageFileView> items, long total, long totalSize) {}
