package com.star.storage.factory;

import com.star.common.BusinessException;
import com.star.storage.enums.StorageProvider;
import com.star.storage.service.StorageConfigService.Connection;
import com.star.storage.strategy.StorageStrategy;
import java.util.*;
import org.springframework.stereotype.Component;

// 包职责：自动发现存储协议适配器，公开类型和字段定义，按连接快照创建请求专属客户端。
// 模块联系：配置页面按服务端元数据渲染；StorageRouter 管理独立客户端生命周期，避免共享可变凭据。
@Component
public class StorageClientFactory {
  private final Map<StorageProvider, StorageBackendAdapter> adapters = new LinkedHashMap<>();

  public StorageClientFactory(List<StorageBackendAdapter> implementations) {
    for (var a : implementations) {
      if (adapters.putIfAbsent(a.provider(), a) != null)
        throw new IllegalStateException("重复的存储适配器");
    }
  }

  public List<StorageBackendAdapter.Type> types() {
    return adapters.values().stream().map(StorageBackendAdapter::type).toList();
  }

  public StorageBackendAdapter adapter(String code) {
    return adapters.values().stream()
        .filter(a -> a.type().value().equals(code))
        .findFirst()
        .orElseThrow(() -> BusinessException.bad("该存储协议尚未接入，请选择已支持的类型"));
  }

  public StorageStrategy create(Connection c) {
    var adapter = adapters.get(c.provider());
    if (adapter == null) throw BusinessException.bad("存储适配器不可用");
    return adapter.create(c);
  }
}
