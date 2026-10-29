package com.star.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import com.star.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 验证密码长度、UTF-8 字节上限以及字母数字组合规则。
 *
 * <p>关联模块：直接测试 AuthService.passwordPolicy，保护注册、初始化密码、重置与个人改密流程。
 */
class PasswordPolicyTest {
  @Test
  void rejectsShortOrSingleClassPasswords() {
    assertThrows(BusinessException.class, () -> AuthService.passwordPolicy("abc123"));
    assertThrows(BusinessException.class, () -> AuthService.passwordPolicy("abcdefghijklmnop"));
    assertThrows(BusinessException.class, () -> AuthService.passwordPolicy("1234567890123456"));
    assertDoesNotThrow(() -> AuthService.passwordPolicy("CorrectPassword2026"));
  }

  @Test
  void bcryptLimitUsesUtf8BytesRatherThanCharacters() {
    assertDoesNotThrow(() -> AuthService.passwordPolicy("中".repeat(23) + "Ab1"));
    assertThrows(BusinessException.class, () -> AuthService.passwordPolicy("中".repeat(24) + "Ab1"));
  }

  @Test
  void storedHashesAreSaltedAndVerifyTheOriginalPassword() {
    var encoder = new BCryptPasswordEncoder(12);
    String first = encoder.encode("CorrectPassword2026"),
        second = encoder.encode("CorrectPassword2026");
    assertNotEquals(first, second);
    assertTrue(encoder.matches("CorrectPassword2026", first));
    assertFalse(encoder.matches("WrongPassword2026", first));
  }
}
