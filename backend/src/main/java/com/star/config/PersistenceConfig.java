package com.star.config;


import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// 包职责：集中配置 Web 认证拦截、MyBatis-Plus 分页及密码编码器。
// 模块联系：装配 auth、framework 与 system 所需基础组件，配置生效于整个后台。

/**
 * 注册 MyBatis-Plus 分页插件和 BCrypt 密码编码器，统一分页上限与密码计算成本。
 *
 * <p>关联模块：SystemService 使用分页能力；AuthService、AccountService 和账号申请服务使用密码编码器。
 */
@Configuration
public class PersistenceConfig {
  @Bean
  public MybatisPlusInterceptor mybatisPlusInterceptor() {
    var interceptor = new MybatisPlusInterceptor();
    var pagination = new PaginationInnerInterceptor(DbType.MYSQL);
    pagination.setMaxLimit(100L);
    interceptor.addInnerInterceptor(pagination);
    return interceptor;
  }

  @Bean
  public BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }
}
