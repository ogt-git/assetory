package com.project.assetory.snapshot.controller;

import com.project.assetory.global.response.ApiResponse;
import com.project.assetory.snapshot.domain.TrendPeriod;
import com.project.assetory.snapshot.dto.AssetChangeResponse;
import com.project.assetory.snapshot.dto.AssetTrendResponse;
import com.project.assetory.snapshot.dto.PortfolioResponse;
import com.project.assetory.snapshot.dto.SnapshotDetailResponse;
import com.project.assetory.snapshot.dto.SnapshotMonthResponse;
import com.project.assetory.snapshot.service.AssetChangeQueryService;
import com.project.assetory.snapshot.service.AssetTrendQueryService;
import com.project.assetory.snapshot.service.PortfolioQueryService;
import com.project.assetory.snapshot.service.SnapshotRecordQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.beans.PropertyEditorSupport;
import java.time.LocalDate;
import java.time.YearMonth;

@Tag(name = "자산 기록")
@RestController
@RequestMapping("/api/snapshots")
@RequiredArgsConstructor
public class SnapshotController {

    private final AssetChangeQueryService assetChangeQueryService;
    private final AssetTrendQueryService assetTrendQueryService;
    private final SnapshotRecordQueryService snapshotRecordQueryService;
    private final PortfolioQueryService portfolioQueryService;

    // 보유 종목 손익 · 자산 비중 (FR-AS-03 · 04, S-01 ⑤ · ⑥) — 최근 COMPLETE 기록 기준. 고정 경로라 /{date}보다 먼저 매칭된다
    @Operation(summary = "보유 종목 손익 · 자산 비중",
            description = """
                    가장 최근 COMPLETE 기록을 기준으로 자산군 비중과 종목별 평가금액 · 원금 · 손익 · 비중 · 당일 변동을 돌려준다.
                    - 평가금액 = 그날 한투 종가 × 같은 시점 보유수량(기록값). /api/assets(CODEF 평가액 기준)와 값이 다를 수 있다
                    - 원금 = 기록 수량 × 현재 평균 매입가. 평균 매입가가 없거나 그 뒤 전량 매도했거나 기록 수량이 없으면 원금 · 손익 · 손익률은 null
                    - 비중 = 금액 ÷ 그날 총자산 × 100. 반올림 합계를 100으로 맞추지 않는다. 예수금 · 수기 자산은 종목 행에 없으므로 종목 비중 합은 증권 비중과 다를 수 있다
                    - 최근 기록이 PARTIAL · FAILED여도 값은 최근 COMPLETE 기준이고, latestRecord로 날짜 · 상태를 알려준다
                    - 기록이 없으면 200 + 빈 목록
                    """)
    @GetMapping("/portfolio")
    public ResponseEntity<ApiResponse<PortfolioResponse>> getPortfolio(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(PortfolioResponse.from(portfolioQueryService.getPortfolio(userId))));
    }

    // 자산 기록 월별 목록 (FR-AS-06, S-02) — 상태 무관 그 달 기록 전체. 누락 날은 직전 COMPLETE 값 (01 §14 #19)
    @Operation(summary = "자산 기록 월별 목록",
            description = """
                    그 달의 일별 자산 기록을 날짜 내림차순으로 돌려준다(휴장일은 기록이 없다).
                    - month(yyyy-MM) 생략 시 가장 최근 기록이 있는 달. 형식이 틀리면 400 INVALID_PARAMETER
                    - missing=true(PARTIAL · FAILED): totalAsset · referenceDate는 그 날짜 이전 마지막 COMPLETE 값, 증감은 null — 그날의 부분 금액은 내리지 않는다
                    - summary(이번 달)는 그 달 첫 COMPLETE → 마지막 COMPLETE (월간 결산과 같은 기준). 첫 거래일의 전월 말 대비 변화는 포함하지 않는다
                    - risingDays · fallingDays는 COMPLETE 기록의 전일 대비 부호로 센다
                    - 기록이 없는 달은 200 + records []
                    """)
    @GetMapping
    public ResponseEntity<ApiResponse<SnapshotMonthResponse>> getMonth(
            Authentication authentication,
            @Parameter(description = "조회할 달 (yyyy-MM). 생략 시 최근 기록이 있는 달", schema = @Schema(type = "string", example = "2026-09"))
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(SnapshotMonthResponse.from(snapshotRecordQueryService.getMonth(userId, month))));
    }

    // 자산 기록 날짜별 상세 (FR-AS-06, FR-AN-01 종목 단위). /changes · /trend는 고정 경로라 이 변수 경로보다 먼저 매칭된다
    @Operation(summary = "자산 기록 날짜별 상세",
            description = """
                    그날의 자산군 금액 · 증감과 자산별 · 종목별 기록(수량 · 단가 · 금액 · 전일 대비)을 돌려준다. 계좌번호는 마스킹 값만.
                    - date(yyyy-MM-dd) 형식이 틀리거나 없는 날짜(2026-02-30 등)면 400 INVALID_PARAMETER
                    - 그 날짜에 기록이 없으면(휴장일 · 연동 전 · 미래, 다른 사용자의 날짜 포함) 404 SNAPSHOT_NOT_FOUND
                    - missing=true(PARTIAL · FAILED): totalAsset · referenceDate만 직전 COMPLETE 값, 증감 · assetClasses는 null, assets는 빈 목록
                    """)
    @GetMapping("/{date}")
    public ResponseEntity<ApiResponse<SnapshotDetailResponse>> getDetail(
            Authentication authentication,
            @Parameter(description = "기록 날짜 (yyyy-MM-dd)", schema = @Schema(type = "string", format = "date", example = "2026-09-03"))
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(SnapshotDetailResponse.from(snapshotRecordQueryService.getDetail(userId, date))));
    }

    // period는 코드값 "1M" · "1Y" · "ALL"만 받는다. Converter 빈은 실패 시 Spring이 enum 이름 변환(ONE_MONTH)으로 다시 시도해
    // 이름이 통과하므로, 대체 변환이 없는 PropertyEditor로 묶는다. 실패 → MethodArgumentTypeMismatchException → 400 INVALID_PARAMETER
    @InitBinder
    void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(TrendPeriod.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(TrendPeriod.fromCode(text));
            }
        });
    }

    // 총자산 기간별 추이 (FR-AN-05 · 06, S-01 ③) — COMPLETE 기록만
    @Operation(summary = "총자산 기간별 추이 (1개월 · 1년 · 전체)",
            description = """
                    가장 최근 COMPLETE 기록을 끝점으로 기간의 COMPLETE 기록 점 목록과 기간 요약을 돌려준다. PARTIAL · FAILED 날은 점에 넣지 않는다.
                    - period: 1M / 1Y / ALL (대문자 정확히 일치, 생략 시 1Y). 다른 값은 400 INVALID_PARAMETER
                    - 시작점: 1M · 1Y = 1개월 · 1년 전 같은 날(말일 보정) 이하의 마지막 COMPLETE, ALL = 첫 COMPLETE
                    - fullPeriod=false: 기간 미달로 연동 이후 첫 기록부터 표시
                    - summary는 끝점 vs 시작점이고, 시장가격 영향은 시작점을 뺀 (시작, 끝] 기록의 합이다 — /changes의 전월 · 전년 · 누적 대비와 같은 값
                    - 기록이 없으면 200 + points [] + summary.available=false
                    """)
    @GetMapping("/trend")
    public ResponseEntity<ApiResponse<AssetTrendResponse>> getTrend(
            Authentication authentication,
            @Parameter(description = "조회 기간", schema = @Schema(type = "string", allowableValues = {"1M", "1Y", "ALL"}, defaultValue = "1Y"))
            @RequestParam(defaultValue = "1Y") TrendPeriod period) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(AssetTrendResponse.from(assetTrendQueryService.getTrend(userId, period))));
    }

    // 전일 · 전월 · 전년 · 누적 증감 (FR-AN-01 · 02 · 03 · 06) — 배치가 저장한 COMPLETE 기록만 쓴다. 사용자는 토큰으로만 식별
    @Operation(summary = "자산 증감 조회 (전일 · 전월 · 전년 · 연동 이후 누적)",
            description = """
                    가장 최근 COMPLETE 기록을 기준으로 전일 · 전월 · 전년 · 연동 이후 누적 증감을 돌려준다. PARTIAL · FAILED 기록은 비교에 쓰지 않는다.
                    - available=false: 기간 미달 또는 기준이 될 COMPLETE 기록이 없음. 이때 나머지 필드는 모두 null이다
                    - 전월 · 전년이 available=false면 sinceFirstRecord(연동 이후 누적)를 대신 보여준다
                    - 기록이 하나도 없어도 200이다(오류 아님)
                    - 시장가격 영향을 계산할 수 없는 기록이 기간에 있으면 marketChangeAmount · otherChangeAmount는 null이다
                    """)
    // useReturnTypeSchema — 예시를 붙여도 응답 스키마(ApiResponse<AssetChangeResponse>)는 반환 타입에서 그대로 만든다
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", useReturnTypeSchema = true,
            content = @Content(examples = {
            @ExampleObject(name = "전일 · 누적 있음, 전월 · 전년 기간 미달", value = """
                    {"success": true, "message": "요청이 성공적으로 처리되었습니다.", "errorCode": null, "timestamp": "2026-10-02T09:00:00",
                     "data": {"asOfDate": "2026-10-01", "totalAsset": 3100000.00,
                      "latestRecord": {"date": "2026-10-02", "status": "PARTIAL"},
                      "day": {"available": true, "baseDate": "2026-09-30", "baseTotal": 3000000.00, "changeAmount": 100000.00,
                              "changeRate": 3.3333, "marketChangeAmount": 40000.00, "otherChangeAmount": 60000.00,
                              "assetClassChanges": {"securities": 100000.00, "deposit": 0.00, "cash": 0.00}},
                      "month": {"available": false, "baseDate": null, "baseTotal": null, "changeAmount": null, "changeRate": null,
                                "marketChangeAmount": null, "otherChangeAmount": null, "assetClassChanges": null},
                      "year": {"available": false, "baseDate": null, "baseTotal": null, "changeAmount": null, "changeRate": null,
                               "marketChangeAmount": null, "otherChangeAmount": null, "assetClassChanges": null},
                      "sinceFirstRecord": {"available": true, "baseDate": "2026-09-30", "baseTotal": 3000000.00, "changeAmount": 100000.00,
                                           "changeRate": 3.3333, "marketChangeAmount": 40000.00, "otherChangeAmount": 60000.00,
                                           "assetClassChanges": {"securities": 100000.00, "deposit": 0.00, "cash": 0.00}}}}"""),
            @ExampleObject(name = "기록 없음", value = """
                    {"success": true, "message": "요청이 성공적으로 처리되었습니다.", "errorCode": null, "timestamp": "2026-10-02T09:00:00",
                     "data": {"asOfDate": null, "totalAsset": null, "latestRecord": null,
                      "day": {"available": false, "baseDate": null, "baseTotal": null, "changeAmount": null, "changeRate": null,
                              "marketChangeAmount": null, "otherChangeAmount": null, "assetClassChanges": null},
                      "month": {"available": false, "baseDate": null, "baseTotal": null, "changeAmount": null, "changeRate": null,
                                "marketChangeAmount": null, "otherChangeAmount": null, "assetClassChanges": null},
                      "year": {"available": false, "baseDate": null, "baseTotal": null, "changeAmount": null, "changeRate": null,
                               "marketChangeAmount": null, "otherChangeAmount": null, "assetClassChanges": null},
                      "sinceFirstRecord": {"available": false, "baseDate": null, "baseTotal": null, "changeAmount": null, "changeRate": null,
                                           "marketChangeAmount": null, "otherChangeAmount": null, "assetClassChanges": null}}}""")
    }))
    @GetMapping("/changes")
    public ResponseEntity<ApiResponse<AssetChangeResponse>> getChanges(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(AssetChangeResponse.from(assetChangeQueryService.getChanges(userId))));
    }
}
