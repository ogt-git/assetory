package com.project.assetory.asset.dto;

import com.project.assetory.asset.domain.AssetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// 수기 자산 입력 — 유형·이름·금액 (원본의 취득가·취득일·메모는 버림, D-19)
public record ManualAssetRequest(

        @NotNull(message = "자산 유형은 필수입니다.")
        AssetType assetType,

        @NotBlank(message = "자산명은 필수입니다.")
        @Size(max = 100, message = "자산명은 100자 이하여야 합니다.")
        String name,

        @NotNull(message = "금액은 필수입니다.")
        BigDecimal balance
) {}
