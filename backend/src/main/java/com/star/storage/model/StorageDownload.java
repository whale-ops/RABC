package com.star.storage.model;

import java.io.InputStream;

// 包职责：定义文件展示、分页与下载流模型，不暴露存储内部配置和凭据。
// 模块联系：在 storage.service 与 storage.controller 之间传递数据，展示模型供前端文件页面使用。

/**
 * 封装下载文件名、大小与输入流，供服务层向控制器传递下载内容，不直接序列化返回。
 *
 * <p>关联模块：StorageFileService 创建模型，StorageFileController 转发文件内容并关闭流。
 */
public record StorageDownload(String name, long size, InputStream stream) {}
