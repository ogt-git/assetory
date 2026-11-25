package com.project.assetory.detection.controller;

import com.project.assetory.detection.dto.DetectionSettingResponse;
import com.project.assetory.detection.dto.DetectionSettingUpdateRequest;
import com.project.assetory.detection.service.DetectionSettingService;
import com.project.assetory.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "알림 설정")
@RestController
@RequestMapping("/api/detection-settings")
@RequiredArgsConstructor
public class DetectionSettingController {

    private final DetectionSettingService detectionSettingService;

    @Operation(summary = "알림 규칙별 설정 목록",
            description = "활성 규칙 전체(id 순)와 내 설정 · 실제 적용 기준값. 내 설정이 없으면 기본값으로 채운다 (FR-DT-02, UC-07)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<DetectionSettingResponse>>> getSettings(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(detectionSettingService.getSettings(userId)));
    }

    @Operation(summary = "알림 규칙 설정 저장",
            description = "수신 여부 · 기준값 · 제외 종목을 통째로 바꾼다. 기준값을 비우면 기본값, 허용 범위 밖이면 400과 함께 범위를 돌려준다. "
                    + "제외 종목은 종목 급등락 규칙만 받는다")
    @PutMapping("/{ruleCode}")
    public ResponseEntity<ApiResponse<DetectionSettingResponse>> updateSetting(
            Authentication authentication,
            @Parameter(description = "규칙 코드", schema = @Schema(type = "string", example = "STOCK_PRICE_CHANGE"))
            @PathVariable String ruleCode,
            @Valid @RequestBody DetectionSettingUpdateRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(detectionSettingService.update(userId, ruleCode, request)));
    }

    @Operation(summary = "알림 규칙 기본값으로 되돌리기",
            description = "내 설정을 지우고 기본값 응답을 돌려준다. 설정이 없어도 성공한다 (UC-07 3c)")
    @DeleteMapping("/{ruleCode}")
    public ResponseEntity<ApiResponse<DetectionSettingResponse>> resetSetting(
            Authentication authentication,
            @Parameter(description = "규칙 코드", schema = @Schema(type = "string", example = "STOCK_PRICE_CHANGE"))
            @PathVariable String ruleCode) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(detectionSettingService.reset(userId, ruleCode)));
    }
}
