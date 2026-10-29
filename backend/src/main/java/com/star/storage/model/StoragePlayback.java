package com.star.storage.model;

// 包职责：定义文件接口的只读响应模型，不包含存储账号或密钥。
// 模块联系：StorageFileService 签发地址，前端 VideoDocumentPreview 按需加载视频。

/** 只读视频播放地址和过期时间；有效期内持有地址即可读取该视频，请勿记录到日志。 */
public record StoragePlayback(String url, long expiresAt) {}
