package com.star.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.star.common.ApiResponse;
import com.star.common.BusinessException;
import com.star.system.dto.Inputs;
import com.star.system.service.SystemService;
import com.star.tool.excel.ExcelFiles;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

// 包职责：提供系统管理、首页统计与 Excel 导入导出的 HTTP 接口。
// 模块联系：对接前端系统页面，调用 system.service，使用 tool.excel 并接受 framework 审计与权限约束。

/**
 * 提供用户、角色、菜单、部门和岗位的通用管理接口，以及批量导入和 Excel 导出。
 *
 * <p>关联模块：SystemService 负责业务与事务，ExcelFiles 生成表格；前端 system-live.ts 和 SystemManagement.vue 调用。
 */
@com.star.framework.annotation.OperationAudit("系统管理")
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
  private final SystemService service;

  public SystemController(SystemService service) {
    this.service = service;
  }

  @GetMapping("/options")
  public Object options() {
    return ApiResponse.ok(service.options());
  }

  @GetMapping("/{resource}")
  public Object list(
      @PathVariable String resource,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String deptId,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "desc") String direction) {
    return ApiResponse.ok(
        service.list(resource, page, size, keyword, status, deptId, sort, direction, "list"));
  }

  @GetMapping("/{resource}/tree")
  public Object tree(@PathVariable String resource) {
    return ApiResponse.ok(service.tree(resource));
  }

  @GetMapping("/{resource}/{id}")
  public Object detail(@PathVariable String resource, @PathVariable String id) {
    return ApiResponse.ok(service.detail(resource, id));
  }

  @PostMapping("/users/import")
  public Object importUsers(@Valid @RequestBody Inputs.UserImport body) {
    return ApiResponse.ok(service.importUsers(body));
  }

  @PostMapping("/{resource}")
  public ResponseEntity<?> create(@PathVariable String resource, @RequestBody JsonNode body) {
    return ResponseEntity.status(201).body(ApiResponse.ok(service.save(resource, null, body)));
  }

  @PostMapping("/roles/import")
  public Object importRoles(@Valid @RequestBody Inputs.RoleImport body) {
    return ApiResponse.ok(service.importRoles(body));
  }

  @PatchMapping("/{resource}/{id}")
  public Object update(
      @PathVariable String resource, @PathVariable String id, @RequestBody JsonNode body) {
    return ApiResponse.ok(service.save(resource, id, body));
  }

  @PatchMapping("/{resource}/{id}/status")
  public Object status(
      @PathVariable String resource,
      @PathVariable String id,
      @Valid @RequestBody Inputs.Status body) {
    return ApiResponse.ok(service.status(resource, id, body));
  }

  @PostMapping("/{resource}/batch-delete")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String resource, @Valid @RequestBody Inputs.Delete body) {
    service.delete(resource, body.items());
  }

  @DeleteMapping("/{resource}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteOne(
      @PathVariable String resource, @PathVariable String id, @RequestParam int version) {
    service.delete(resource, List.of(new Inputs.Version(id, version)));
  }

  @PutMapping("/roles/{id}/permissions")
  public Object grant(@PathVariable String id, @Valid @RequestBody Inputs.Grant body) {
    return ApiResponse.ok(service.grant(id, body));
  }

  @PutMapping("/users/{id}/roles")
  public Object assign(@PathVariable String id, @Valid @RequestBody Inputs.Roles body) {
    return ApiResponse.ok(service.assignRoles(id, body));
  }

  @PostMapping("/users/{id}/password-reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(
      @PathVariable String id, @Valid @RequestBody Inputs.PasswordReset body) {
    service.resetPassword(id, body);
  }

  @GetMapping("/{resource}/export")
  @SuppressWarnings("unchecked")
  public ResponseEntity<byte[]> export(
      @PathVariable String resource,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String deptId,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "desc") String direction) {
    var first = service.list(resource, 1, 100, keyword, status, deptId, sort, direction, "export");
    long total = ((Number) first.get("total")).longValue();
    if (total > 10000) throw BusinessException.bad("单次最多导出一万条，请缩小筛选范围");
    var rows = new ArrayList<>((List<Map<String, Object>>) first.get("records"));
    for (int page = 2; page <= Math.ceil(total / 100.0); page++)
      rows.addAll(
          (List<Map<String, Object>>)
              service
                  .list(resource, page, 100, keyword, status, deptId, sort, direction, "export")
                  .get("records"));
    List<String> columns =
        switch (resource) {
          case "users" ->
              List.of(
                  "id",
                  "username",
                  "name",
                  "department",
                  "phone",
                  "email",
                  "role",
                  "post",
                  "status",
                  "createdAt");
          case "roles" -> List.of("id", "name", "code", "scope", "members", "status", "createdAt");
          case "menus" ->
              List.of("id", "name", "parent", "type", "path", "permission", "sort", "status");
          case "depts" -> List.of("id", "name", "parent", "leader", "phone", "members", "status");
          default -> List.of("id", "name", "code", "sort", "remark", "status", "createdAt");
        };
    var labels =
        Map.ofEntries(
            Map.entry("id", "ID"),
            Map.entry("username", "用户账号"),
            Map.entry("name", "名称"),
            Map.entry("department", "所属部门"),
            Map.entry("phone", "手机号码"),
            Map.entry("email", "电子邮箱"),
            Map.entry("role", "角色"),
            Map.entry("post", "岗位"),
            Map.entry("status", "状态"),
            Map.entry("createdAt", "创建时间"),
            Map.entry("code", "编码"),
            Map.entry("scope", "数据范围"),
            Map.entry("members", "成员数"),
            Map.entry("parent", "上级"),
            Map.entry("type", "类型"),
            Map.entry("path", "路由"),
            Map.entry("permission", "权限标识"),
            Map.entry("sort", "排序"),
            Map.entry("leader", "负责人"),
            Map.entry("remark", "备注"));
    List<List<String>> data =
        rows.stream()
            .map(row -> columns.stream().map(c -> Objects.toString(row.get(c), "")).toList())
            .toList();
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"" + resource + ".xlsx\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(ExcelFiles.write("系统数据", columns.stream().map(labels::get).toList(), data));
  }
}
