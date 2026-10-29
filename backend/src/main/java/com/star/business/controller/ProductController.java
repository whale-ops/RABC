package com.star.business.controller;

import com.star.business.dto.BusinessInputs;
import com.star.business.service.ProductService;
import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

// 包职责：提供产品查询、维护与上下架接口。
// 模块联系：ProductService 执行权限与事务，OperationAudit 将写操作接入现有日志。
@OperationAudit("产品管理")
@RestController
@RequestMapping("/api/v1/business/products")
public class ProductController {
  private final ProductService service;

  public ProductController(ProductService service) {
    this.service = service;
  }

  @GetMapping
  public Object list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String category) {
    return ApiResponse.ok(service.list(page, size, keyword, status, category));
  }

  @GetMapping("/choices")
  public Object choices(@RequestParam(defaultValue = "") String keyword) {
    return ApiResponse.ok(service.choices(keyword));
  }

  @GetMapping("/{id}")
  public Object detail(@PathVariable String id) {
    return ApiResponse.ok(service.detail(id));
  }

  @PostMapping
  public ResponseEntity<?> create(@Valid @RequestBody BusinessInputs.Product input) {
    return ResponseEntity.status(201).body(ApiResponse.ok(service.save(null, input)));
  }

  @PatchMapping("/{id}")
  public Object edit(@PathVariable String id,
                     @Valid @RequestBody BusinessInputs.Product input) {
    return ApiResponse.ok(service.save(id, input));
  }

  @PatchMapping("/{id}/status")
  public Object status(@PathVariable String id,
                       @Valid @RequestBody BusinessInputs.ProductStatus input) {
    return ApiResponse.ok(service.status(id, input));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id,
                     @Valid @RequestBody BusinessInputs.Version input) {
    service.delete(id, input);
  }
}
