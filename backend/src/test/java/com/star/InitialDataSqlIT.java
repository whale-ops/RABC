package com.star;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证新空库的基础数据和 SQL 重复执行行为，检查已有密码不会被覆盖。
 *
 * <p>关联模块：配合 schema.sql、data.sql 及日志初始化脚本；断言针对干净测试库，不适合直接在已有业务库执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class InitialDataSqlIT {
  @Autowired JdbcTemplate jdbc;
  @Autowired BCryptPasswordEncoder encoder;

  @Test
  void initialAdministratorHasAllPermissionsAndNoDemoData() {
    assertEquals(1L, count("sys_tenant"));
    assertEquals(1L, count("sys_user"));
    assertEquals(1L, count("sys_role"));
    assertEquals(1L, count("sys_dept"));
    assertEquals(0L, count("sys_post"));
    assertEquals(71L, count("sys_menu"));
    assertEquals(
        71L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role_menu WHERE tenant_id=1 AND role_id=1", Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=1 AND user_id=1001 AND role_id=1",
            Long.class));
    String hash =
        jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=1001", String.class);
    assertTrue(encoder.matches("StarAdmin@2026", hash));
    assertTrue(
        jdbc.queryForObject(
            "SELECT must_change_password FROM sys_user WHERE id=1001", Boolean.class));
  }

  @Test
  void standaloneDataScriptRequiresNoSessionVariablesAndPreservesChangedPasswordOnReplay() {
    String changedHash = encoder.encode("ChangedInTest2026");
    jdbc.update(
        "UPDATE sys_user SET password_hash=?,auth_version=7,must_change_password=FALSE WHERE"
            + " id=1001",
        changedHash);
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              var scripts = new ResourceDatabasePopulator(new ClassPathResource("db/data.sql"));
              scripts.setSqlScriptEncoding("UTF-8");
              scripts.populate(connection);
              scripts.populate(connection);
              return null;
            });
    assertEquals(1L, count("sys_user"));
    assertEquals(71L, count("sys_menu"));
    assertEquals(
        changedHash,
        jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=1001", String.class));
    assertEquals(
        7, jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=1001", Integer.class));
    assertFalse(
        jdbc.queryForObject(
            "SELECT must_change_password FROM sys_user WHERE id=1001", Boolean.class));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }
}
