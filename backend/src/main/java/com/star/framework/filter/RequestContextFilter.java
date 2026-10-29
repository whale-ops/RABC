package com.star.framework.filter;

import com.star.framework.security.Actor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 包职责：维护请求编号、安全响应头及线程上下文清理。
// 模块联系：先于 config.WebConfig 执行，配合 framework.security，供 common 响应和 monitor 日志追踪请求。

/**
 * 为每次请求生成追踪编号并设置响应安全头，确保请求结束后清除线程中的身份。
 *
 * <p>关联模块：先于 WebConfig 执行；Actor 保存身份，ApiResponse、ExceptionAdvice 和日志切面使用追踪编号。
 */
@Component("starRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Actor.clear();
    MDC.put("traceId", UUID.randomUUID().toString());
    response.setHeader("X-Request-Id", MDC.get("traceId"));
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    try {
      chain.doFilter(request, response);
    } finally {
      Actor.clear();
      MDC.remove("traceId");
    }
  }
}
