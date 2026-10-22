package com.project.assetory.asset.controller;

import com.project.assetory.asset.dto.ManualAssetRequest;
import com.project.assetory.asset.dto.ManualAssetResponse;
import com.project.assetory.asset.dto.AssetOverviewResponse;
import com.project.assetory.asset.service.AssetQueryService;
import com.project.assetory.asset.service.ManualAssetService;
import com.project.assetory.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AssetController {

    private final AssetQueryService assetQueryService;
    private final ManualAssetService manualAssetService;

    // 통합 자산 조회 (FR-AS-01) — 증권 계좌 + 수기 자산 + 자산군별 합계. 계좌번호는 마스킹 값만
    @GetMapping("/assets")
    public ResponseEntity<ApiResponse<AssetOverviewResponse>> getAssets(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(assetQueryService.getOverview(userId)));
    }

    // 수기 자산 입력
    @PostMapping("/assets/manual")
    public ResponseEntity<ApiResponse<ManualAssetResponse>> saveManualAsset(
            Authentication authentication,
            @Valid @RequestBody ManualAssetRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        ManualAssetResponse response = manualAssetService.save(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, "수동 자산이 등록되었습니다."));
    }

    // 수기 자산 목록
    @GetMapping("/assets/manual")
    public ResponseEntity<ApiResponse<List<ManualAssetResponse>>> getManualAssets(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(manualAssetService.getAll(userId)));
    }

    // 수기 자산 수정
    @PutMapping("/assets/manual/{assetId}")
    public ResponseEntity<ApiResponse<ManualAssetResponse>> updateManualAsset(
            Authentication authentication,
            @PathVariable Long assetId,
            @Valid @RequestBody ManualAssetRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(manualAssetService.update(userId, assetId, request)));
    }

    // 수기 자산 삭제
    @DeleteMapping("/assets/manual/{assetId}")
    public ResponseEntity<ApiResponse<Void>> deleteManualAsset(
            Authentication authentication,
            @PathVariable Long assetId) {
        Long userId = (Long) authentication.getPrincipal();
        manualAssetService.delete(userId, assetId);
        return ResponseEntity.ok(ApiResponse.success(null, "수동 자산이 삭제되었습니다."));
    }
}
