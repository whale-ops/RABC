package com.star;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 包职责：应用启动入口，统一装配后台各业务模块。
// 模块联系：启动 auth、system、monitor、storage，并加载 config 与 framework 基础能力。

/**
 * 后台启动入口，启用 Spring Boot 自动装配并扫描系统管理 Mapper。
 *
 * <p>关联模块：装配 auth 认证、framework 基础能力、monitor 日志、storage 存储、system 系统管理及 config 配置。
 */
@SpringBootApplication
@MapperScan("com.star.system.mapper")
public class StarApplication {
  public static void main(String[] args) {
    SpringApplication.run(StarApplication.class, args);
  }
}
