package com.star.config;

import com.star.auth.service.AuthService;
import com.star.framework.security.Actor;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

// 包职责：集中配置 Web 认证拦截、MyBatis-Plus 分页及密码编码器。
// 模块联系：装配 auth、framework 与 system 所需基础组件，配置生效于整个后台。

/**
 * 配置接口认证拦截器，为受保护接口验证登录身份并在请求结束时清理。
 *
 * <p>关联模块：调用 AuthService 验证会话，通过 Actor 向系统管理与日志模块传递当前用户。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthService auth;

  public WebConfig(AuthService auth) {
    this.auth = auth;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest req, HttpServletResponse res, Object handler) {
                String p = req.getRequestURI();
                if (("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()))
                    && p.matches("/api/v1/storage/media/[0-9a-f]{64}")) return true;
                if (Set.of(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/public-config",
                        "/api/v1/auth/captcha",
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/health")
                    .contains(p)) return true;
                var user = auth.authenticate();
                Actor.set(user);
                return true;
              }

              @Override
              public void afterCompletion(
                  HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
                Actor.clear();
              }
            })
        .addPathPatterns("/api/**");
  }
}
