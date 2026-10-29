package com.star.business.controller;

import com.star.business.dto.BusinessInputs;
import com.star.business.service.OrderService;
import com.star.common.ApiResponse;
import com.star.framework.annotation.OperationAudit;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

// 包职责：接收订单创建、查询、收款确认、物流和取消操作。
// 模块联系：OrderService 锁定库存并维护状态；收款为人工业务确认，不连接支付网关。
@OperationAudit("订单管理")
@RestController
@RequestMapping("/api/v1/business/orders")
public class OrderController {
  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  @GetMapping
  public Object list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status) {
    return ApiResponse.ok(service.list(page, size, keyword, status));
  }

  @GetMapping("/{id}")
  public Object detail(@PathVariable String id) {
    return ApiResponse.ok(service.detail(id));
  }

  @PostMapping
  public ResponseEntity<?> create(@Valid @RequestBody BusinessInputs.CreateOrder input) {
    return ResponseEntity.status(201).body(ApiResponse.ok(service.create(input)));
  }

  @PatchMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void edit(@PathVariable String id, @Valid @RequestBody BusinessInputs.EditOrder input) {
    service.edit(id, input);
  }

  @PostMapping("/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable String id, @Valid @RequestBody BusinessInputs.Version input) {
    service.transition(id, "cancel", input.version(), null);
  }

  @PostMapping("/{id}/pay")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void pay(@PathVariable String id, @Valid @RequestBody BusinessInputs.Version input) {
    service.transition(id, "pay", input.version(), null);
  }

  @PostMapping("/{id}/ship")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void ship(@PathVariable String id, @Valid @RequestBody BusinessInputs.Ship input) {
    service.transition(id, "ship", input.version(), input.trackingNo());
  }

  @PostMapping("/{id}/complete")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void complete(@PathVariable String id, @Valid @RequestBody BusinessInputs.Version input) {
    service.transition(id, "complete", input.version(), null);
  }
}
