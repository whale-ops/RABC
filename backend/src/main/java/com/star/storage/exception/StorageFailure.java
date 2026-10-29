package com.star.storage.exception;

import com.star.common.BusinessException;

// 包职责：将存储配置错误和厂商 SDK 失败转为安全的业务异常。
// 模块联系：由 storage.factory 和策略实现使用，交给 common.ExceptionAdvice 转换为统一错误响应。

/**
 * 将存储配置或厂商 SDK 失败转换为稳定业务错误，避免签名、地址及凭据进入接口或日志。
 *
 * <p>关联模块：存储工厂和 OSS、MinIO 策略使用；通过 common.ExceptionAdvice 返回统一错误响应。
 */
public final class StorageFailure extends BusinessException {
  public StorageFailure() {
    super(503, "STORAGE_UNAVAILABLE", "文件存储暂时不可用，请检查服务端存储配置或稍后重试");
  }

  public static void require(boolean valid) {
    if (!valid) throw new StorageFailure();
  }

  public static boolean filled(String value) {
    return value != null && !value.isBlank();
  }
}
