package com.star.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.*;
import com.star.auth.service.AuthService;
import com.star.common.BusinessException;
import com.star.framework.security.Actor;
import com.star.framework.security.PermissionService;
import com.star.system.dto.Inputs;
import com.star.system.entity.BaseRecord;
import com.star.system.entity.Dept;
import com.star.system.entity.Menu;
import com.star.system.entity.Post;
import com.star.system.entity.Role;
import com.star.system.entity.User;
import com.star.system.mapper.DeptMapper;
import com.star.system.mapper.MenuMapper;
import com.star.system.mapper.PostMapper;
import com.star.system.mapper.RoleMapper;
import com.star.system.mapper.UserMapper;
import jakarta.validation.Validator;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 包职责：实现系统管理的查询、维护、授权、导入及数据关系处理。
// 模块联系：连接 system.mapper、framework.security 和 auth；保留首页历史审计，配合 monitor 处理用户日志匿名化。

/**
 * 实现系统管理五类资源的查询、校验、增删改、授权和导入，并统一保护租户与版本。
 *
 * <p>关联模块：连接各实体 Mapper、PermissionService 与 Relations；为系统管理页面、首页和账号审核提供业务能力。
 */
@Service
public class SystemService {
  private final Map<String, BaseMapper<?>> mappers;
  private final UserMapper users;
  private final RoleMapper roles;
  private final MenuMapper menus;
  private final DeptMapper depts;
  private final PostMapper posts;
  private final Relations relations;
  private final PermissionService permission;
  private final ObjectMapper json;
  private final Validator validator;
  private final BCryptPasswordEncoder passwords;
  private final JdbcTemplate jdbc;
  private final AuthService auth;
  public static final Map<String, String> PREFIX =
      Map.of(
          "users",
          "system:user",
          "roles",
          "system:role",
          "menus",
          "system:menu",
          "depts",
          "system:dept",
          "posts",
          "system:post");

  public SystemService(
      UserMapper users,
      RoleMapper roles,
      MenuMapper menus,
      DeptMapper depts,
      PostMapper posts,
      Relations relations,
      PermissionService permission,
      ObjectMapper json,
      Validator validator,
      BCryptPasswordEncoder passwords,
      JdbcTemplate jdbc,
      AuthService auth) {
    this.users = users;
    this.roles = roles;
    this.menus = menus;
    this.depts = depts;
    this.posts = posts;
    this.relations = relations;
    this.permission = permission;
    this.json = json;
    this.validator = validator;
    this.passwords = passwords;
    this.jdbc = jdbc;
    mappers =
        Map.of("users", users, "roles", roles, "menus", menus, "depts", depts, "posts", posts);
    this.auth = auth;
  }

  public String code(String kind, String action) {
    String prefix = PREFIX.get(kind);
    if (prefix == null) throw BusinessException.missing();
    return prefix + ":" + action;
  }

  /** 写操作先获取租户锁，再重新认证并校验操作权限；敏感配置还要求内置管理员身份。 */
  private void guard(String kind, String action, boolean admin) {
    relations.lockTenant();
    Actor.set(auth.authenticate());
    permission.require(code(kind, action));
    if (admin) permission.requireAdmin();
  }

  @SuppressWarnings("unchecked")
  private BaseMapper<BaseRecord> mapper(String kind) {
    code(kind, "list");
    return (BaseMapper<BaseRecord>) (BaseMapper<?>) mappers.get(kind);
  }

  /** 为资源查询附加当前租户；用户资源还使用当前操作对应的数据范围，避免越权。 */
  @SuppressWarnings("unchecked")
  private QueryWrapper<BaseRecord> query(String kind, String action) {
    return kind.equals("users")
        ? (QueryWrapper<BaseRecord>) (QueryWrapper<?>) permission.userQuery(code(kind, action))
        : new QueryWrapper<BaseRecord>().eq("tenant_id", Actor.tenant());
  }

  public BaseRecord find(String kind, String id, String action) {
    if (id == null || !id.matches("[0-9]{1,20}")) throw BusinessException.bad("ID 格式不合法");
    BaseRecord row = mapper(kind).selectOne(query(kind, action).eq("id", id));
    if (row == null) throw BusinessException.missing();
    return row;
  }

  public Object detail(String kind, String id) {
    permission.require(code(kind, "read"));
    return view(kind, find(kind, id, "read"));
  }

  /** 统一分页、筛选和排序白名单，供管理列表与导出使用同一套可见数据规则。 */
  public Map<String, Object> list(
      String kind,
      int page,
      int size,
      String keyword,
      String status,
      String deptId,
      String sort,
      String direction,
      String action) {
    permission.require(code(kind, action));
    if (page < 1 || size < 1 || size > 100) throw BusinessException.bad("分页范围应为 page≥1、size 1–100");
    if (keyword != null && keyword.length() > 100) throw BusinessException.bad("查询关键字过长");
    var q = query(kind, action);
    if (keyword != null && !keyword.isBlank()) {
      String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").trim();
      q.and(
          w -> {
            w.like("name", escaped);
            if (kind.equals("users")) w.or().like("username", escaped).or().like("phone", escaped);
            if (Set.of("roles", "posts").contains(kind)) w.or().like("code", escaped);
          });
    }
    if (status != null && !status.isBlank()) {
      validStatus(status);
      q.eq("status", status);
    }
    if (kind.equals("users") && deptId != null && !deptId.isBlank()) q.eq("dept_id", deptId);
    var allowed =
        new HashMap<>(
            Map.of("name", "name", "status", "status", "createdAt", "created_at", "id", "id"));
    if (kind.equals("users")) {
      allowed.put("username", "username");
      allowed.put("phone", "phone");
    }
    if (Set.of("posts", "roles").contains(kind)) allowed.put("code", "code");
    if (Set.of("posts", "depts", "menus").contains(kind)) allowed.put("sort", "sort");
    if (kind.equals("roles")) allowed.put("scope", "scope");
    if (kind.equals("menus")) {
      allowed.put("type", "type");
      allowed.put("path", "path");
      allowed.put("permission", "permission");
    }
    String column = allowed.get(sort == null || sort.isBlank() ? "createdAt" : sort);
    if (column == null || !Set.of("asc", "desc").contains(direction))
      throw BusinessException.bad("不支持的排序字段或方向");
    q.orderBy(true, "asc".equals(direction), column).orderByAsc("id");
    var result = mapper(kind).selectPage(new Page<>(page, size), q);
    return Map.of(
        "records",
        result.getRecords().stream().map(r -> view(kind, r)).toList(),
        "total",
        result.getTotal(),
        "page",
        page,
        "size",
        size);
  }

  private <T> T input(JsonNode raw, Class<T> type) {
    if (raw == null || !raw.isObject()) throw BusinessException.bad("请求体必须为 JSON 对象");
    try {
      T value = json.treeToValue(raw, type);
      var violations = validator.validate(value);
      if (!violations.isEmpty())
        throw BusinessException.bad(violations.iterator().next().getPropertyPath() + "：字段不合法");
      return value;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw BusinessException.bad("字段不合法，请刷新页面后重试");
    }
  }

  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  private static String nullableId(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static int order(Integer i) {
    return i == null ? 0 : i;
  }

  private List<String> ids(List<String> values) {
    if (values == null) return List.of();
    if (values.stream().anyMatch(v -> v == null || !v.matches("[0-9]{1,20}")))
      throw BusinessException.bad("关联 ID 不合法");
    return new ArrayList<>(new LinkedHashSet<>(values));
  }

  private void validStatus(String status) {
    if (!Set.of("启用", "停用").contains(status)) throw BusinessException.bad("状态不合法");
  }

  private void version(BaseRecord old, Integer version) {
    if (old != null && !Objects.equals(old.version, version))
      throw BusinessException.conflict("记录已被其他人修改，请刷新后重试");
  }

  private void mutable(BaseRecord row) {
    if (row instanceof User u && Boolean.TRUE.equals(u.builtin))
      throw BusinessException.conflict("内置管理员请通过个人中心修改资料，不能在此修改或删除");
    if (row instanceof Role r && Boolean.TRUE.equals(r.builtin))
      throw BusinessException.conflict("内置管理员角色受保护");
  }

  private void activeRef(String kind, String id) {
    BaseRecord r = find(kind, id, "list");
    if (!"启用".equals(r.status)) throw BusinessException.bad("关联的" + kind + "已停用");
  }

  private void checkRoleAssignment(List<String> newIds, List<String> oldIds) {
    if (newIds.isEmpty()) throw BusinessException.bad("请至少选择一个角色");
    var actorPermissions = permission.getPermissionList(Actor.user().id, "login");
    for (String id : newIds) {
      Role r = (Role) find("roles", id, "list");
      if (!"启用".equals(r.status)) throw BusinessException.bad("不能分配停用角色");
      if (Boolean.TRUE.equals(r.builtin) && !oldIds.contains(id)) throw BusinessException.denied();
      if (!permission.admin(Actor.user())) {
        var rolePermissions =
            jdbc.queryForList(
                "SELECT m.permission FROM sys_menu m JOIN sys_role_menu rm ON"
                    + " rm.tenant_id=m.tenant_id AND rm.menu_id=m.id WHERE rm.tenant_id=? AND"
                    + " rm.role_id=? AND m.permission<>''",
                String.class,
                Actor.tenant(),
                id);
        if (!actorPermissions.containsAll(rolePermissions) || !oldIds.contains(id))
          throw new BusinessException(403, "GRANT_DENIED", "非租户管理员不能变更用户角色");
      }
    }
  }

  /** 将管理员输入的角色名称解析为已有普通角色，必要时创建未授权的新角色。 */
  private List<String> resolveRoleNames(List<String> roleIds, List<String> names) {
    if (names == null || names.isEmpty()) return roleIds;
    permission.requireAdmin();
    var result = new LinkedHashSet<>(roleIds);
    for (String inputName : names) {
      String name = text(inputName);
      if (name.isBlank()) throw BusinessException.bad("角色名称不能为空");
      Role role =
          roles.selectOne(
              new QueryWrapper<Role>().eq("tenant_id", Actor.tenant()).eq("name", name));
      if (role == null) {
        permission.require("system:role:create");
        role = new Role();
        role.id = IdWorker.getIdStr();
        role.tenantId = Actor.tenant();
        role.name = name;
        role.code = "role_" + role.id;
        role.scope = "仅本人";
        role.status = "启用";
        role.builtin = false;
        role.remark = "";
        role.version = 0;
        role.createdAt = role.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        roles.insert(role);
        relations.audit("create", "roles", role.id);
      }
      if (Boolean.TRUE.equals(role.builtin)) throw BusinessException.bad("内置管理员角色不能分配给其他用户");
      if (!"启用".equals(role.status)) throw BusinessException.bad("不能分配停用角色");
      result.add(role.id);
    }
    if (result.size() > 10) throw BusinessException.bad("最多填写 10 个角色");
    return new ArrayList<>(result);
  }

  private void parent(String kind, String id, String parentId, String status) {
    if (parentId == null) return;
    Set<String> seen = new HashSet<>();
    if (id != null) seen.add(id);
    String current = parentId;
    while (current != null) {
      if (seen.size() > 32) throw BusinessException.bad("组织和菜单层级最多 32 层");
      if (!seen.add(current)) throw BusinessException.bad("上级不能为自身或下级节点");
      BaseRecord p = find(kind, current, "list");
      if ("启用".equals(status) && !"启用".equals(p.status))
        throw BusinessException.bad("启用节点不能挂在停用节点下");
      if (p instanceof Menu m) {
        if ("按钮".equals(m.type)) throw BusinessException.bad("按钮不能包含子菜单");
        current = m.parentId;
      } else current = ((Dept) p).parentId;
    }
  }

  /** 按普通用户保存规则导入整批记录；任意一行失败会回滚整批用户及关联关系。 */
  @Transactional
  public Map<String, Integer> importUsers(Inputs.UserImport body) {
    guard("users", "create", true);
    Set<String> accounts = new HashSet<>();
    int index = 0;
    for (var row : body.rows()) {
      index++;
      if (!accounts.add(row.username().toLowerCase(Locale.ROOT)))
        throw BusinessException.bad("第 " + index + " 条数据：文件内用户账号重复");
      try {
        save("users", null, json.valueToTree(row));
      } catch (BusinessException ex) {
        throw new BusinessException(
            ex.status, ex.code, "第 " + index + " 条数据：" + ex.getMessage() + "；本批次未导入任何账号");
      } catch (org.springframework.dao.DataIntegrityViolationException ex) {
        throw BusinessException.conflict("第 " + index + " 条数据：账号已存在或关联数据不合法；本批次未导入任何账号");
      }
    }
    return Map.of("imported", index);
  }

  /** 批次内检查名称和编码，再以普通角色规则逐条创建；失败时整批回滚。 */
  @Transactional
  public Map<String, Integer> importRoles(Inputs.RoleImport body) {
    guard("roles", "create", true);
    Set<String> codes = new HashSet<>(), names = new HashSet<>();
    int index = 0;
    for (var row : body.rows()) {
      index++;
      if (!codes.add(row.code().toLowerCase(Locale.ROOT))
          || !names.add(row.name().trim().toLowerCase(Locale.ROOT)))
        throw BusinessException.bad("第 " + index + " 条数据：文件内角色名称或编码重复；本批次未导入任何角色");
      try {
        // 普通创建固定使用非内置角色，且不自动创建权限或成员绑定。
        save("roles", null, json.valueToTree(row));
      } catch (BusinessException ex) {
        throw new BusinessException(
            ex.status, ex.code, "第 " + index + " 条数据：" + ex.getMessage() + "；本批次未导入任何角色");
      } catch (org.springframework.dao.DataIntegrityViolationException ex) {
        throw BusinessException.conflict("第 " + index + " 条数据：角色名称或编码已存在；本批次未导入任何角色");
      }
    }
    return Map.of("imported", index);
  }

  /** 根据资源类型绑定输入并执行引用、权限和版本校验，再统一保存与审计。 */
  @Transactional
  public Map<String, Object> save(String kind, String id, JsonNode raw) {
    String action = id == null ? "create" : "update";
    code(kind, action);
    guard(
        kind,
        action,
        Set.of("roles", "menus").contains(kind) || (kind.equals("users") && id == null));
    BaseRecord old = id == null ? null : find(kind, id, action);
    if (old != null) mutable(old);
    BaseRecord value;
    Integer expected;
    List<String> roleIds = null, postIds = null;
    switch (kind) {
      case "users" -> {
        var b = input(raw, Inputs.UserWrite.class);
        expected = b.version();
        var u = new User();
        u.username = b.username();
        u.name = text(b.name());
        u.deptId = nullableId(b.deptId());
        u.email = text(b.email());
        u.phone = text(b.phone());
        u.remark = text(b.remark());
        u.status = b.status();
        roleIds = ids(b.roleIds());
        postIds = ids(b.postIds());
        if (u.deptId == null) throw BusinessException.bad("请选择所属部门");
        activeRef("depts", u.deptId);
        permission.checkDepartment(u.deptId, code(kind, action));
        var previous =
            old == null ? List.<String>of() : relations.ids("user-role", Actor.tenant(), id);
        roleIds = resolveRoleNames(roleIds, b.roleNames());
        checkRoleAssignment(roleIds, previous);
        for (String p : postIds) activeRef("posts", p);
        if (old == null) {
          AuthService.passwordPolicy(b.initialPassword());
          u.passwordHash = passwords.encode(b.initialPassword());
          u.authVersion = 0;
          u.mustChangePassword = true;
          u.builtin = false;
          u.bio = "";
        } else {
          var existing = (User) old;
          if (!existing.username.equals(u.username)) throw BusinessException.bad("用户账号创建后不可修改");
          if (b.initialPassword() != null && !b.initialPassword().isBlank())
            throw BusinessException.bad("修改密码请使用专用操作");
          u.authVersion = existing.authVersion + 1;
          u.passwordHash = existing.passwordHash;
          u.mustChangePassword = existing.mustChangePassword;
          u.builtin = existing.builtin;
          u.bio = existing.bio;
          if (id.equals(Actor.user().id)) throw BusinessException.conflict("请通过个人中心修改自己的资料");
        }
        value = u;
      }
      case "roles" -> {
        var b = input(raw, Inputs.RoleWrite.class);
        expected = b.version();
        var r = new Role();
        r.name = text(b.name());
        r.code = b.code();
        r.scope = b.scope();
        r.status = b.status();
        r.remark = text(b.remark());
        r.builtin = false;
        if ("自定义部门".equals(r.scope) && old == null)
          throw BusinessException.bad("请先以仅本人范围创建，再在权限中选择自定义部门");
        if ("自定义部门".equals(r.scope) && relations.ids("role-dept", Actor.tenant(), id).isEmpty())
          throw BusinessException.bad("请先在权限设置中选择自定义部门");
        value = r;
      }
      case "menus" -> {
        var b = input(raw, Inputs.MenuWrite.class);
        expected = b.version();
        var m = new Menu();
        m.name = text(b.name());
        m.parentId = nullableId(b.parentId());
        m.type = b.type();
        m.path = text(b.path());
        m.permission = text(b.permission());
        m.sort = order(b.sort());
        m.icon = text(b.icon());
        m.status = b.status();
        if (!m.path.isEmpty()
            && (!m.path.startsWith("/") || m.path.startsWith("//") || m.path.contains(":")))
          throw BusinessException.bad("路由必须是站内绝对路径");
        if (!m.permission.isEmpty()
            && !m.permission.matches("[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*){1,3}"))
          throw BusinessException.bad("权限码格式不合法");
        if ("按钮".equals(m.type) && m.permission.isBlank())
          throw BusinessException.bad("按钮必须配置权限标识");
        if ("菜单".equals(m.type) && m.path.isBlank()) throw BusinessException.bad("菜单必须配置路由");
        if ("菜单".equals(m.type)
            && menus.selectCount(
                    new QueryWrapper<Menu>()
                        .eq("tenant_id", Actor.tenant())
                        .eq("type", "菜单")
                        .eq("path", m.path)
                        .ne(id != null, "id", id))
                > 0) throw BusinessException.conflict("该路由已关联其他菜单");
        if (old != null
            && "按钮".equals(m.type)
            && menus.selectCount(
                    new QueryWrapper<Menu>().eq("tenant_id", Actor.tenant()).eq("parent_id", id))
                > 0) throw BusinessException.conflict("有子节点的菜单不能改为按钮");
        parent(kind, id, m.parentId, m.status);
        value = m;
      }
      case "depts" -> {
        var b = input(raw, Inputs.DeptWrite.class);
        expected = b.version();
        var d = new Dept();
        d.name = text(b.name());
        d.parentId = nullableId(b.parentId());
        d.leader = text(b.leader());
        d.phone = text(b.phone());
        d.sort = order(b.sort());
        d.status = b.status();
        d.remark = text(b.remark());
        parent(kind, id, d.parentId, d.status);
        value = d;
      }
      case "posts" -> {
        var b = input(raw, Inputs.PostWrite.class);
        expected = b.version();
        var p = new Post();
        p.name = text(b.name());
        p.code = b.code();
        p.sort = order(b.sort());
        p.status = b.status();
        p.remark = text(b.remark());
        p.status = b.status();
        value = p;
      }
      default -> throw BusinessException.missing();
    }
    version(old, expected);
    if (old != null && !"启用".equals(value.status)) checkDisable(kind, id);
    value.tenantId = Actor.tenant();
    value.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    if (old == null) {
      value.id = IdWorker.getIdStr();
      value.version = 0;
      value.createdAt = value.updatedAt;
      mapper(kind).insert(value);
    } else {
      value.id = id;
      value.version = old.version + 1;
      value.createdAt = old.createdAt;
      int changed =
          mapper(kind).update(value, query(kind, action).eq("id", id).eq("version", old.version));
      if (changed != 1) throw BusinessException.conflict("记录已变化，请刷新");
    }
    if (roleIds != null) {
      relations.replace("user-role", value.id, roleIds);
      relations.replace("user-post", value.id, postIds);
    }
    relations.audit(action, kind, value.id);
    return view(kind, value);
  }

  private void checkDisable(String kind, String id) {
    if (kind.equals("depts")
        && (depts.selectCount(
                    new QueryWrapper<Dept>()
                        .eq("tenant_id", Actor.tenant())
                        .eq("parent_id", id)
                        .eq("status", "启用"))
                > 0
            || users.selectCount(
                    new QueryWrapper<User>()
                        .eq("tenant_id", Actor.tenant())
                        .eq("dept_id", id)
                        .eq("status", "启用"))
                > 0)) throw BusinessException.conflict("部门仍有启用的下级或用户");
    if (kind.equals("menus")
        && menus.selectCount(
                new QueryWrapper<Menu>()
                    .eq("tenant_id", Actor.tenant())
                    .eq("parent_id", id)
                    .eq("status", "启用"))
            > 0) throw BusinessException.conflict("请先停用下级菜单");
  }

  @Transactional
  public Object status(String kind, String id, Inputs.Status body) {
    guard(kind, "update", Set.of("roles", "menus").contains(kind));
    var row = find(kind, id, "update");
    mutable(row);
    version(row, body.version());
    if (kind.equals("users") && id.equals(Actor.user().id))
      throw BusinessException.conflict("不能停用自己的账号");
    if ("停用".equals(body.status())) checkDisable(kind, id);
    if ("启用".equals(body.status())) {
      if (row instanceof Dept d) parent(kind, id, d.parentId, body.status());
      if (row instanceof Menu m) parent(kind, id, m.parentId, body.status());
      if (row instanceof User u && u.deptId != null) activeRef("depts", u.deptId);
    }
    row.status = body.status();
    row.version++;
    row.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    if (row instanceof User u) u.authVersion++;
    if (mapper(kind).update(row, query(kind, "update").eq("id", id).eq("version", body.version()))
        != 1) throw BusinessException.conflict("记录已变化");
    relations.audit("status", kind, id);
    return view(kind, row);
  }

  /** 只删除选中的资源；删除用户时清理其申请和绑定并匿名化日志，删除角色仅解除绑定。 */
  @Transactional
  public void delete(String kind, List<Inputs.Version> items) {
    guard(kind, "delete", Set.of("roles", "menus").contains(kind));
    if (items.stream().map(Inputs.Version::id).distinct().count() != items.size())
      throw BusinessException.bad("重复 ID");
    for (var item : items) {
      var r = find(kind, item.id(), "delete");
      mutable(r);
      version(r, item.version());
      if (r instanceof User && r.id.equals(Actor.user().id))
        throw BusinessException.conflict("不能删除当前账号");
      if (r instanceof Post && relations.references("sys_user_post", "post_id", r.id) > 0)
        throw BusinessException.conflict("岗位仍有关联用户");
      if (r instanceof Dept
          && (depts.selectCount(
                      new QueryWrapper<Dept>()
                          .eq("tenant_id", Actor.tenant())
                          .eq("parent_id", r.id))
                  > 0
              || users.selectCount(
                      new QueryWrapper<User>().eq("tenant_id", Actor.tenant()).eq("dept_id", r.id))
                  > 0
              || relations.references("sys_role_dept", "dept_id", r.id) > 0))
        throw BusinessException.conflict("部门仍有子部门、用户或数据范围引用");
      if (r instanceof Menu
          && menus.selectCount(
                  new QueryWrapper<Menu>().eq("tenant_id", Actor.tenant()).eq("parent_id", r.id))
              > 0) throw BusinessException.conflict("请先删除下级菜单");
    }
    for (var item : items) {
      if (kind.equals("users")) {
        User user = (User) find(kind, item.id(), "delete");
        // 申请表独立保存联系方式，删除用户时需要一并清理。
        jdbc.update(
            "DELETE FROM sys_account_request WHERE tenant_id=? AND (user_id=? OR username=?)",
            Actor.tenant(),
            user.id,
            user.username);
        for (String table : List.of("sys_login_log", "sys_operation_log"))
          jdbc.update(
              "UPDATE "
                  + table
                  + " SET username='已删除',display_name='已删除',ip='',user_agent='' WHERE tenant_id=?"
                  + " AND (user_id=? OR username=?)",
              Actor.tenant(),
              user.id,
              user.username);
        relations.replace("user-role", item.id(), List.of());
        relations.replace("user-post", item.id(), List.of());
      }
      if (kind.equals("roles")) {
        jdbc.update(
            "DELETE FROM sys_user_role WHERE tenant_id=? AND role_id=?", Actor.tenant(), item.id());
        relations.replace("role-menu", item.id(), List.of());
        relations.replace("role-dept", item.id(), List.of());
      }
      if (kind.equals("menus"))
        jdbc.update(
            "DELETE FROM sys_role_menu WHERE tenant_id=? AND menu_id=?", Actor.tenant(), item.id());
      if (mapper(kind)
              .delete(query(kind, "delete").eq("id", item.id()).eq("version", item.version()))
          != 1) throw BusinessException.conflict("记录已变化");
      relations.audit("delete", kind, item.id());
    }
  }

  /** 替换角色的菜单权限及自定义部门范围；不会通过角色名称自动赋予能力。 */
  @Transactional
  public Object grant(String id, Inputs.Grant body) {
    guard("roles", "grant", true);
    Role r = (Role) find("roles", id, "read");
    mutable(r);
    version(r, body.version());
    var menuIds = ids(body.menuIds());
    var deptIds = ids(body.deptIds());
    for (String m : menuIds) activeRef("menus", m);
    for (String d : deptIds) activeRef("depts", d);
    if ("自定义部门".equals(body.scope()) && deptIds.isEmpty()) throw BusinessException.bad("请选择自定义部门");
    r.scope = body.scope();
    r.version++;
    r.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    roles.update(
        r,
        new QueryWrapper<Role>()
            .eq("tenant_id", Actor.tenant())
            .eq("id", id)
            .eq("version", body.version()));
    relations.replace("role-menu", id, menuIds);
    relations.replace("role-dept", id, "自定义部门".equals(body.scope()) ? deptIds : List.of());
    relations.audit("grant", "roles", id);
    return view("roles", r);
  }

  /** 在同一事务中校验并替换用户的角色集合，保护内置角色和已变化的记录。 */
  @Transactional
  public Object assignRoles(String id, Inputs.Roles body) {
    guard("users", "assign-role", true);
    User u = (User) find("users", id, "assign-role");
    mutable(u);
    version(u, body.version());
    var roleIds = ids(body.roleIds());
    checkRoleAssignment(roleIds, relations.ids("user-role", Actor.tenant(), id));
    u.version++;
    u.authVersion++;
    u.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    users.update(
        u,
        permission.userQuery("system:user:assign-role").eq("id", id).eq("version", body.version()));
    relations.replace("user-role", id, roleIds);
    relations.audit("assign-role", "users", id);
    return view("users", u);
  }

  /** 按数据范围与保护规则重置密码，并递增认证版本使旧会话失效。 */
  @Transactional
  public void resetPassword(String id, Inputs.PasswordReset body) {
    guard("users", "reset-password", false);
    User u = (User) find("users", id, "reset-password");
    mutable(u);
    version(u, body.version());
    if (id.equals(Actor.user().id)) throw BusinessException.conflict("请使用个人中心修改自己的密码");
    AuthService.passwordPolicy(body.newPassword());
    u.passwordHash = passwords.encode(body.newPassword());
    u.mustChangePassword = true;
    u.authVersion++;
    u.version++;
    u.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    users.update(
        u,
        permission
            .userQuery("system:user:reset-password")
            .eq("id", id)
            .eq("version", body.version()));
    relations.audit("reset-password", "users", id);
  }

  /** 将数据库实体转换为前端展示字段，避免把密码摘要等敏感字段直接返回。 */
  public Map<String, Object> view(String kind, BaseRecord r) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", r.id);
    out.put("name", r.name);
    out.put("status", r.status);
    out.put("version", r.version);
    out.put(
        "createdAt", r.createdAt == null ? null : r.createdAt.atOffset(ZoneOffset.UTC).toString());
    if (r instanceof User u) {
      out.put("username", u.username);
      out.put("deptId", u.deptId);
      out.put("department", label("depts", u.deptId));
      out.put("email", u.email);
      out.put("phone", u.phone);
      out.put("remark", u.remark);
      out.put("builtin", u.builtin);
      var rs = relations.ids("user-role", u.tenantId, u.id);
      var ps = relations.ids("user-post", u.tenantId, u.id);
      out.put("roleIds", rs);
      out.put("postIds", ps);
      out.put("role", rs.stream().map(id -> label("roles", id)).collect(Collectors.joining("、")));
      out.put("post", ps.stream().map(id -> label("posts", id)).collect(Collectors.joining("、")));
    } else if (r instanceof Role v) {
      out.put("code", v.code);
      out.put("scope", v.scope);
      out.put("remark", v.remark);
      out.put("builtin", v.builtin);
      out.put("menuIds", relations.ids("role-menu", v.tenantId, v.id));
      out.put("deptIds", relations.ids("role-dept", v.tenantId, v.id));
      out.put("members", relations.references("sys_user_role", "role_id", v.id));
    } else if (r instanceof Menu m) {
      out.put("parentId", m.parentId);
      out.put("parent", m.parentId == null ? "顶级菜单" : label("menus", m.parentId));
      out.put("type", m.type);
      out.put("path", m.path);
      out.put("permission", m.permission);
      out.put("sort", m.sort);
      out.put("icon", m.icon);
    } else if (r instanceof Dept d) {
      out.put("parentId", d.parentId);
      out.put(
          "parent",
          d.parentId == null
              ? jdbc.queryForObject(
                  "SELECT name FROM sys_tenant WHERE id=?", String.class, Actor.tenant())
              : label("depts", d.parentId));
      out.put("leader", d.leader);
      out.put("phone", d.phone);
      out.put("sort", d.sort);
      out.put("remark", d.remark);
      out.put(
          "members",
          users.selectCount(permission.userQuery("system:user:list").eq("dept_id", d.id)));
    } else if (r instanceof Post p) {
      out.put("code", p.code);
      out.put("sort", p.sort);
      out.put("remark", p.remark);
    }
    return out;
  }

  private String label(String kind, String id) {
    if (id == null) return "";
    var r =
        mapper(kind)
            .selectOne(new QueryWrapper<BaseRecord>().eq("tenant_id", Actor.tenant()).eq("id", id));
    return r == null ? "" : r.name;
  }

  /** 返回当前账号可用的部门、角色、岗位及菜单，供表单与授权界面选择。 */
  public Object options() {
    if (!permission.getPermissionList(Actor.user().id, "login").stream()
        .anyMatch(p -> p.startsWith("system:"))) throw BusinessException.denied();
    var allowed = permission.scope("system:user:list");
    var ds =
        depts.selectList(
            new QueryWrapper<Dept>().eq("tenant_id", Actor.tenant()).orderByAsc("sort", "id"));
    var rs =
        roles.selectList(new QueryWrapper<Role>().eq("tenant_id", Actor.tenant()).orderByAsc("id"));
    if (!permission.admin(Actor.user())) {
      rs =
          rs.stream()
              .filter(
                  r -> relations.ids("user-role", Actor.tenant(), Actor.user().id).contains(r.id))
              .toList();
    }
    return Map.of(
        "departments",
        ds.stream()
            .filter(
                d ->
                    allowed.all()
                        || allowed.depts().contains(d.id)
                        || Objects.equals(d.id, Actor.user().deptId))
            .map(d -> view("depts", d))
            .toList(),
        "roles",
        rs.stream().map(r -> view("roles", r)).toList(),
        "posts",
        posts
            .selectList(
                new QueryWrapper<Post>().eq("tenant_id", Actor.tenant()).orderByAsc("sort", "id"))
            .stream()
            .map(p -> view("posts", p))
            .toList(),
        "menus",
        permission.admin(Actor.user())
            ? menus
                .selectList(
                    new QueryWrapper<Menu>()
                        .eq("tenant_id", Actor.tenant())
                        .orderByAsc("sort", "id"))
                .stream()
                .map(m -> view("menus", m))
                .toList()
            : List.of());
  }

  public List<Map<String, Object>> tree(String kind) {
    if (!Set.of("menus", "depts").contains(kind)) throw BusinessException.missing();
    permission.require(code(kind, "list"));
    var rows =
        mapper(kind).selectList(query(kind, "list").orderByAsc("sort", "id")).stream()
            .map(r -> view(kind, r))
            .toList();
    return buildTree(rows, null);
  }

  public List<Map<String, Object>> buildTree(List<Map<String, Object>> rows, String parent) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var row : rows)
      if (Objects.equals(row.get("parentId"), parent)) {
        var item = new LinkedHashMap<>(row);
        item.put("children", buildTree(rows, (String) row.get("id")));
        result.add(item);
      }
    return result;
  }

  /** 从启用菜单中提取当前账号可访问的页面并保留父级，供路由和侧边栏使用。 */
  public List<Map<String, Object>> navigation() {
    var all =
        menus.selectList(
            new QueryWrapper<Menu>()
                .eq("tenant_id", Actor.tenant())
                .eq("status", "启用")
                .ne("type", "按钮")
                .orderByAsc("sort", "id"));
    var grants = permission.getPermissionList(Actor.user().id, "login");
    Set<String> keep = new HashSet<>();
    for (Menu m : all)
      if (permission.admin(Actor.user())
          || (!m.permission.isBlank() && grants.contains(m.permission))) {
        keep.add(m.id);
        String p = m.parentId;
        while (p != null) {
          keep.add(p);
          String x = p;
          p =
              all.stream()
                  .filter(a -> a.id.equals(x))
                  .map(a -> a.parentId == null ? "" : a.parentId)
                  .findFirst()
                  .orElse("");
          if (p.isEmpty()) p = null;
        }
      }
    return buildTree(
        all.stream().filter(m -> keep.contains(m.id)).map(m -> view("menus", m)).toList(), null);
  }
}
