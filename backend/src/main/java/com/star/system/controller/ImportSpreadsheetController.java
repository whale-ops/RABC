package com.star.system.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.star.common.ApiResponse;
import com.star.common.BusinessException;
import com.star.framework.security.PermissionService;
import com.star.tool.excel.ExcelFiles;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

// 包职责：提供系统管理、首页统计与 Excel 导入导出的 HTTP 接口。
// 模块联系：对接前端系统页面，调用 system.service，使用 tool.excel 并接受 framework 审计与权限约束。

/**
 * 提供用户和角色的 Excel 模板下载及预览解析，限制文件大小、列数和记录数。
 *
 * <p>关联模块：ExcelFiles 生成模板，EasyExcel 读取首个工作表；UserImport.vue 和 RoleImport.vue 确认后再调用导入接口落库。
 */
@RestController
@RequestMapping("/api/v1/system/{resource:users|roles}/import")
public class ImportSpreadsheetController {
  private static final Map<String, List<String>> HEADERS =
      Map.of(
          "users",
              List.of("用户账号", "用户姓名", "初始密码", "部门ID", "角色ID", "岗位ID", "电子邮箱", "手机号码", "状态", "备注"),
          "roles", List.of("角色名称", "角色编码", "数据范围", "状态", "备注"));
  private final PermissionService permissions;

  public ImportSpreadsheetController(PermissionService permissions) {
    this.permissions = permissions;
  }

  private void authorize(String resource) {
    permissions.requireAdmin();
    permissions.require("system:" + (resource.equals("users") ? "user" : "role") + ":create");
  }

  @GetMapping("/template")
  public ResponseEntity<byte[]> template(@PathVariable String resource) {
    authorize(resource);
    return ResponseEntity.ok()
        .header(
            "Content-Disposition", "attachment; filename=\"" + resource + "-import-template.xlsx\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(
            ExcelFiles.write(
                resource.equals("users") ? "用户导入" : "角色导入", HEADERS.get(resource), List.of()));
  }

  /** 仅解析和返回表格预览，不创建账号或角色；正式导入由 SystemController 提交到事务服务。 */
  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object preview(@PathVariable String resource, @RequestParam("file") MultipartFile file)
      throws IOException {
    authorize(resource);
    if (file.isEmpty() || file.getSize() > 1024 * 1024)
      throw BusinessException.bad("请选择不超过 1 MB 的 Excel 文件");
    if (file.getOriginalFilename() == null
        || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
      throw BusinessException.bad("仅支持 .xlsx 格式");
    List<List<String>> matrix = new ArrayList<>();
    var listener =
        new AnalysisEventListener<Map<Integer, String>>() {
          int width;

          @Override
          public void invokeHeadMap(Map<Integer, String> head, AnalysisContext context) {
            width = head.keySet().stream().max(Integer::compareTo).orElse(-1) + 1;
            if (width < (resource.equals("users") ? 3 : 2) || width > HEADERS.get(resource).size())
              throw BusinessException.bad("模板表头格式不正确");
            matrix.add(values(head));
          }

          private List<String> values(Map<Integer, String> cells) {
            if (cells.keySet().stream().anyMatch(i -> i < 0 || i >= width))
              throw BusinessException.bad("存在超出表头范围的列");
            var row = new ArrayList<String>();
            for (int i = 0; i < width; i++) row.add(Objects.toString(cells.get(i), ""));
            return row;
          }

          @Override
          public void invoke(Map<Integer, String> cells, AnalysisContext context) {
            if (matrix.size() > 100) throw BusinessException.bad("一次最多导入 100 条数据");
            matrix.add(values(cells));
          }

          @Override
          public void doAfterAllAnalysed(AnalysisContext context) {}
        };
    try (var input = file.getInputStream()) {
      EasyExcel.read(input, listener)
          .excelType(ExcelTypeEnum.XLSX)
          .autoCloseStream(false)
          .sheet(0)
          .doRead();
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw BusinessException.bad("Excel 文件无法读取，请使用下载的模板并保存为 .xlsx 格式");
    }
    if (matrix.size() < 2) throw BusinessException.bad("模板中尚未填写用户数据");
    return ApiResponse.ok(Map.of("matrix", matrix));
  }
}
