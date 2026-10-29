package com.star.business.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

// 包职责：声明订单与产品的输入约束，拒绝客户端传入金额、租户或订单状态快照。
// 模块联系：business.controller 校验输入，business.service 负责库存、价格计算和状态流转。
public final class BusinessInputs {
  private BusinessInputs() {}

  public record Product(
      @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String sku,
      @NotBlank @Size(max = 120) String name,
      @NotNull @Size(max = 60) String category,
      @NotNull @DecimalMin("0.00") @DecimalMax("9999999999.99") @Digits(integer = 10, fraction = 2)
          BigDecimal price,
      @NotNull @Min(0) @Max(1000000000) Integer stock,
      @NotNull @Size(max = 1000) String description,
      @PositiveOrZero Long version) {}

  public record Version(@NotNull @PositiveOrZero Long version) {}

  public record ProductStatus(
      @NotNull @PositiveOrZero Long version, @NotNull @Pattern(regexp = "上架|下架") String status) {}

  public record Item(
      @NotBlank @Pattern(regexp = "[0-9]{1,20}") String productId,
      @NotNull @Min(1) @Max(10000) Integer quantity) {}

  public record CreateOrder(
      @NotBlank @Pattern(regexp = "[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
          String requestId,
      @NotBlank @Size(max = 100) String customer,
      @NotNull @Size(max = 32) String phone,
      @NotNull @Size(max = 300) String address,
      @NotNull @Size(max = 500) String remark,
      @NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items) {}

  public record EditOrder(
      @NotNull @PositiveOrZero Long version,
      @NotBlank @Size(max = 100) String customer,
      @NotNull @Size(max = 32) String phone,
      @NotNull @Size(max = 300) String address,
      @NotNull @Size(max = 500) String remark) {}

  public record Ship(
      @NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 100) String trackingNo) {}
}
