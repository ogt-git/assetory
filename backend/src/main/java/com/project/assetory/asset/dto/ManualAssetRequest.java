package com.project.assetory.asset.dto;

import com.project.assetory.asset.domain.AssetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 수기 자산 입력 (D-19) — 공통(asset_type, name, balance) + DEPOSIT·SAVINGS일 때 depositDetail 필수.
 * 원본의 부동산·연금·기타 유형, 취득가·취득일·메모는 버림. 유형·상세 필수 여부·날짜 순서는 서비스에서 검증한다
 */
public record ManualAssetRequest(

        @NotNull(message = "자산 유형은 필수입니다.")
        AssetType assetType,

        @NotBlank(message = "자산명은 필수입니다.")
        @Size(max = 100, message = "자산명은 100자 이하여야 합니다.")
        String name,

        // 현재 금액 — 예금·적금은 현재 잔액, 현금은 보유액
        @NotNull(message = "금액은 필수입니다.")
        @Positive(message = "금액은 0보다 커야 합니다.")
        @Digits(integer = 16, fraction = 2, message = "금액 형식이 올바르지 않습니다.")
        BigDecimal balance,

        @Valid
        DepositDetailRequest depositDetail
) {

    public record DepositDetailRequest(

            @NotNull(message = "금리는 필수입니다.")
            @DecimalMin(value = "0", message = "금리는 0 이상이어야 합니다.")
            @Digits(integer = 2, fraction = 3, message = "금리는 소수점 셋째 자리까지, 100% 미만이어야 합니다.")
            BigDecimal interestRate,

            @NotNull(message = "시작일은 필수입니다.")
            LocalDate startDate,

            @NotNull(message = "만기일은 필수입니다.")
            LocalDate maturityDate,

            @NotNull(message = "원금은 필수입니다.")
            @Positive(message = "원금은 0보다 커야 합니다.")
            @Digits(integer = 16, fraction = 2, message = "원금 형식이 올바르지 않습니다.")
            BigDecimal principal,

            @NotNull(message = "기간(개월)은 필수입니다.")
            @Positive(message = "기간은 1개월 이상이어야 합니다.")
            Integer saveTerm,

            @NotNull(message = "자동 재예치 여부는 필수입니다.")
            Boolean autoRenew
    ) {}
}
