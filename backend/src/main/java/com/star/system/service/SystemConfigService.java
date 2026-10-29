package com.star.system.service;

import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.system.dto.SystemConfigInputs;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：保存租户的品牌、登录和注册配置，未配置时提供兼容默认值。
// 模块联系：管理接口依赖 RBAC，公开认证入口只读取品牌与开关白名单，不返回服务器凭据。
@Service
public class SystemConfigService {
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;

  public SystemConfigService(JdbcTemplate jdbc, PermissionService permissions) {
    this.jdbc = jdbc;
    this.permissions = permissions;
  }

  public record Settings(
      long version,
      String systemName,
      String systemLogo,
      String systemDescription,
      boolean captchaEnabled,
      int captchaLength,
      int captchaExpireSeconds,
      boolean registrationEnabled,
      boolean registrationCaptchaEnabled) {}

  public static Settings defaults() {
    return new Settings(0, "StarAdmin", "/favicon.svg", "企业级快速开发平台", false, 4, 120, true, false);
  }

  public Settings forTenant(String tenant) {
    if (tenant == null) return defaults();
    var rows =
        jdbc.query(
            "SELECT * FROM sys_system_config WHERE tenant_id=?",
            (r, n) ->
                new Settings(
                    r.getLong("version"),
                    r.getString("system_name"),
                    Objects.toString(r.getString("system_logo"), "/favicon.svg"),
                    r.getString("system_description"),
                    r.getBoolean("captcha_enabled"),
                    r.getInt("captcha_length"),
                    r.getInt("captcha_expire_seconds"),
                    r.getBoolean("registration_enabled"),
                    r.getBoolean("registration_captcha_enabled")),
            tenant);
    return rows.isEmpty() ? defaults() : rows.get(0);
  }

  public Settings forCode(String code) {
    var tenants =
        jdbc.queryForList(
            "SELECT CAST(id AS CHAR) FROM sys_tenant WHERE code=? AND status='启用'",
            String.class,
            code.trim());
    return forTenant(tenants.isEmpty() ? null : tenants.get(0));
  }

  public Map<String, Object> publicSettings(String code) {
    var s = forCode(code);
    return Map.of(
        "systemName",
        s.systemName(),
        "systemLogo",
        s.systemLogo(),
        "systemDescription",
        s.systemDescription(),
        "captchaEnabled",
        s.captchaEnabled(),
        "captchaLength",
        s.captchaLength(),
        "captchaExpireSeconds",
        s.captchaExpireSeconds(),
        "registrationEnabled",
        s.registrationEnabled(),
        "registrationCaptchaEnabled",
        s.registrationCaptchaEnabled());
  }

  public Settings read() {
    permissions.require("system:config:read");
    return forTenant(Actor.tenant());
  }

  private void prepare() {
    permissions.require("system:config:update");
    permissions.requireAdmin();
    jdbc.update(
        "INSERT INTO sys_system_config(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE"
            + " tenant_id=tenant_id",
        Actor.tenant());
  }

  private Settings updated(int count) {
    if (count != 1) throw BusinessException.conflict("配置已被其他页面修改，请刷新后重试");
    return forTenant(Actor.tenant());
  }

  @Transactional
  public Settings basic(SystemConfigInputs.Basic b) {
    String name = b.systemName().trim(), description = b.systemDescription().trim();
    if (name.isEmpty() || description.isEmpty()) throw BusinessException.bad("系统名称和描述不能为空");
    String logo = normalizeLogo(b.systemLogo());
    prepare();
    return updated(
        jdbc.update(
            "UPDATE sys_system_config SET"
                + " system_name=?,system_logo=?,system_description=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6)"
                + " WHERE tenant_id=? AND version=?",
            name,
            logo,
            description,
            Actor.tenant(),
            b.version()));
  }

  @Transactional
  public Settings login(SystemConfigInputs.Login b) {
    prepare();
    return updated(
        jdbc.update(
            "UPDATE sys_system_config SET"
                + " captcha_enabled=?,captcha_length=?,captcha_expire_seconds=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6)"
                + " WHERE tenant_id=? AND version=?",
            b.captchaEnabled(),
            b.captchaLength(),
            b.captchaExpireSeconds(),
            Actor.tenant(),
            b.version()));
  }

  @Transactional
  public Settings registration(SystemConfigInputs.Registration b) {
    prepare();
    return updated(
        jdbc.update(
            "UPDATE sys_system_config SET"
                + " registration_enabled=?,registration_captcha_enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6)"
                + " WHERE tenant_id=? AND version=?",
            b.registrationEnabled(),
            b.registrationCaptchaEnabled(),
            Actor.tenant(),
            b.version()));
  }

  /** 自定义 Logo 解码并重编码为 PNG，限制大小和像素；登录前也能显示，不依赖私有对象签名地址。 */
  String normalizeLogo(String value) {
    if ("/favicon.svg".equals(value)) return value;
    if (!value.matches("^data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+$"))
      throw BusinessException.bad("Logo 仅支持 PNG、JPEG 图片");
    try {
      byte[] bytes = Base64.getDecoder().decode(value.substring(value.indexOf(',') + 1));
      if (bytes.length > 256 * 1024) throw BusinessException.bad("Logo 不能超过 256 KB");
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw BusinessException.bad("Logo 图片无法读取");
        var reader = readers.next();
        try {
          reader.setInput(input);
          if (reader.getWidth(0) > 1024 || reader.getHeight(0) > 1024)
            throw BusinessException.bad("Logo 宽高不能超过 1024 像素");
          BufferedImage img = reader.read(0);
          var output = new ByteArrayOutputStream();
          ImageIO.write(img, "png", output);
          if (output.size() > 256 * 1024) throw BusinessException.bad("Logo 转换后不能超过 256 KB，请压缩图片");
          return "data:image/png;base64,"
              + Base64.getEncoder().encodeToString(output.toByteArray());
        } finally {
          reader.dispose();
        }
      }
    } catch (IllegalArgumentException | IOException e) {
      throw BusinessException.bad("Logo 图片格式不正确");
    }
  }
}
