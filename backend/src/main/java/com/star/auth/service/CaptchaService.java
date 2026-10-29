package com.star.auth.service;

import com.star.common.BusinessException;
import com.star.system.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

// 包职责：生成图片验证码，使用 Redis 保存一次性摘要并限制发送频率。
// 模块联系：绑定租户、认证用途与页面 Cookie；登录和注册服务在签发身份或提交申请前消费校验。
@Service
public class CaptchaService {
  private final StringRedisTemplate redis;
  private final SystemConfigService settings;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  public CaptchaService(StringRedisTemplate redis, SystemConfigService settings) {
    this.redis = redis;
    this.settings = settings;
  }

  private String nonce(HttpServletRequest req) {
    if (req.getCookies() != null)
      for (var c : req.getCookies()) if (c.getName().equals("star_csrf")) return c.getValue();
    return "";
  }

  public String key(String code, String purpose, String id, HttpServletRequest req) {
    return "star:captcha:"
        + AuthService.hash(code.trim() + ":" + purpose + ":" + nonce(req) + ":" + id);
  }

  public Map<String, Object> issue(String code, String purpose, HttpServletRequest req) {
    if (!Set.of("login", "register").contains(purpose)) throw BusinessException.bad("验证码用途不正确");
    Long attempts =
        redis.execute(
            new DefaultRedisScript<>(
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60)"
                    + " end; return n",
                Long.class),
            List.of("star:captcha-limit:" + AuthService.hash(req.getRemoteAddr())));
    if (attempts == null || attempts > 30)
      throw new BusinessException(429, "RATE_LIMITED", "验证码请求过于频繁，请稍后重试");
    var config = settings.forCode(code);
    boolean enabled =
        purpose.equals("login")
            ? config.captchaEnabled()
            : config.registrationEnabled() && config.registrationCaptchaEnabled();
    if (!enabled) return Map.of("enabled", false);
    StringBuilder answer = new StringBuilder();
    for (int i = 0; i < config.captchaLength(); i++)
      answer.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    String id = AuthService.random();
    redis
        .opsForValue()
        .set(
            key(code, purpose, id, req),
            AuthService.hash(answer.toString()),
            Duration.ofSeconds(config.captchaExpireSeconds()));
    return Map.of(
        "enabled",
        true,
        "captchaId",
        id,
        "image",
        draw(answer.toString()),
        "expiresIn",
        config.captchaExpireSeconds());
  }

  public void verify(
      String code, String purpose, String id, String answer, HttpServletRequest req) {
    if (id == null || answer == null || id.length() > 64 || answer.length() > 16)
      throw BusinessException.bad("请输入图片验证码");
    String expected = redis.opsForValue().getAndDelete(key(code, purpose, id, req));
    String actual = AuthService.hash(answer.trim().toUpperCase(Locale.ROOT));
    if (expected == null
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)))
      throw new BusinessException(400, "CAPTCHA_INVALID", "验证码错误或已过期，请刷新后重试");
  }

  private String draw(String text) {
    var img = new BufferedImage(180, 56, BufferedImage.TYPE_INT_RGB);
    var g = img.createGraphics();
    try {
      g.setColor(new Color(241, 247, 255));
      g.fillRect(0, 0, 180, 56);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      for (int i = 0; i < 10; i++) {
        g.setColor(new Color(160 + RANDOM.nextInt(70), 180 + RANDOM.nextInt(60), 220));
        g.drawLine(
            RANDOM.nextInt(180), RANDOM.nextInt(56), RANDOM.nextInt(180), RANDOM.nextInt(56));
      }
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 29));
      for (int i = 0; i < text.length(); i++) {
        g.setColor(
            new Color(20 + RANDOM.nextInt(60), 70 + RANDOM.nextInt(80), 140 + RANDOM.nextInt(70)));
        g.drawString(
            text.substring(i, i + 1), 10 + i * (160 / text.length()), 36 + RANDOM.nextInt(9));
      }
      var out = new ByteArrayOutputStream();
      ImageIO.write(img, "png", out);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("验证码生成失败");
    } finally {
      g.dispose();
    }
  }
}
