package com.project.assetory.analysis.controller;

import com.project.assetory.analysis.dto.StockEventDetailResponse;
import com.project.assetory.analysis.service.StockEventDetailQueryService;
import com.project.assetory.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// 원인 분석 상세 (S-04, ANL-9, Q-9) — 프론트 라우트 /analysis/:stockCode/:date와 같은 키. 알림 딥링크(VIEW_ANALYSIS)도 이 키를 쓴다
@Tag(name = "StockEvent", description = "종목 급등락 원인 분석")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/stock-events")
public class StockEventController {

    private final StockEventDetailQueryService stockEventDetailQueryService;

    @Operation(summary = "원인 분석 상세",
            description = """
                    내 급등락 이벤트 하나의 변동 · 공유 분석(근거 상태 · 요약) · 시장 비교 · 근거(공시 · 같은 날 보도) · 지난 이벤트를 돌려준다.
                    저장된 값만 읽는다(조회 중 외부 API 호출 없음).
                    - ticker는 영문 대문자 · 숫자 6자리, date는 yyyy-MM-dd — 형식이 틀리거나 없는 날짜면 400
                    - 그 종목 · 날짜에 내 이벤트가 없으면(다른 사용자의 이벤트 포함) 404 STOCK_EVENT_NOT_FOUND
                    - aiSummary는 analysisStatus = ANALYZED일 때만 값이 있다
                    - afterClose: 이벤트 날 장 마감 이후 공개면 true, 같은 날이지만 시각을 모르면 null
                    - 소급 이벤트(backfilled)는 내 금액 · 비중 · 손익이 null
                    """)
    @GetMapping("/{ticker}/{date}")
    public ResponseEntity<ApiResponse<StockEventDetailResponse>> getDetail(
            Authentication authentication,
            @Parameter(description = "종목코드 (영문 대문자 · 숫자 6자리)", example = "000660")
            @PathVariable @Pattern(regexp = "^[0-9A-Z]{6}$", message = "ticker는 영문 대문자 · 숫자 6자리여야 합니다") String ticker,
            @Parameter(description = "이벤트 날짜 (yyyy-MM-dd)", schema = @Schema(type = "string", format = "date", example = "2026-11-16"))
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(stockEventDetailQueryService.getDetail(userId, ticker, date)));
    }
}
