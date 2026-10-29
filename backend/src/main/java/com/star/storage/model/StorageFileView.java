package com.star.storage.model;

// 包职责：定义文件展示、分页与下载流模型，不暴露存储内部配置和凭据。
// 模块联系：在 storage.service 与 storage.controller 之间传递数据，展示模型供前端文件页面使用。

/**
 * 定义文件列表和上传结果的展示字段，不暴露 Bucket、对象键或存储凭据。
 *
 * <p>关联模块：StorageFileService 组装展示数据，StorageFileController 返回给前端列表使用。
 */
public record StorageFileView(
    String id, String name, long size, String owner, String createdAt, String provider) {}
