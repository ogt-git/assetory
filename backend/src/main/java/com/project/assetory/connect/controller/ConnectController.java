package com.project.assetory.connect.controller;

import com.project.assetory.connect.dto.CodefConnectRequest;
import com.project.assetory.connect.dto.CodefConnectionResponse;
import com.project.assetory.connect.dto.StockRefreshResponse;
import com.project.assetory.connect.service.ConnectService;
import com.project.assetory.connect.service.StockRefreshService;
import com.project.assetory.global.exception.ErrorCode;
import com.project.assetory.global.response.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/codef")
@RequiredArgsConstructor
public class ConnectController {

    private final ConnectService connectService;
    private final StockRefreshService stockRefreshService;

    // 연동 계정 목록 조회
    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<List<CodefConnectionResponse>>> getConnections(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<CodefConnectionResponse> connections = connectService.getConnections(userId).stream()
                .map(CodefConnectionResponse::new)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(connections));
    }

    // 금융기관 계정 연결 — 인증서 방식(loginType=0)은 .der / .key 파일을 multipart/form-data로 전송
    @PostMapping(value = "/connect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CodefConnectionResponse>> connectAccount(
            Authentication authentication,
            @NotBlank(message = "기관코드는 필수입니다") @RequestParam("organization") String organization,
            @Pattern(regexp = "^(BK|ST)$", message = "businessType은 BK 또는 ST이어야 합니다") @RequestParam("businessType") String businessType,
            @Pattern(regexp = "^[01]$", message = "loginType은 0 또는 1이어야 합니다") @RequestParam("loginType") String loginType,
            @NotBlank(message = "비밀번호는 필수입니다") @RequestParam("password") String password,
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "birthDate", required = false) String birthDate,
            @RequestParam(value = "accountNumber", required = false) String accountNumber,
            @RequestParam(value = "accountPassword", required = false) String accountPassword,
            @RequestPart(value = "derFile", required = false) MultipartFile derFile,
            @RequestPart(value = "keyFile", required = false) MultipartFile keyFile
    ) throws Exception {
        CodefConnectRequest request = new CodefConnectRequest();
        request.setOrganization(organization);
        request.setBusinessType(businessType);
        request.setLoginType(loginType);
        request.setPassword(password);
        request.setId(id);
        request.setBirthDate(birthDate);
        request.setAccountNumber(accountNumber);
        request.setAccountPassword(accountPassword);

        Long userId = (Long) authentication.getPrincipal();
        if ("0".equals(loginType)) {
            connectService.attachCertFiles(request, derFile, keyFile);
        }

        // CODEF 원문 응답(connectedId 포함)은 내보내지 않고 연동 정보만 돌려준다
        CodefConnectionResponse response = new CodefConnectionResponse(connectService.connectAccount(userId, request));
        return ResponseEntity.ok(ApiResponse.success(response, "계정 연결이 완료되었습니다."));
    }

    // 연동 해지
    @DeleteMapping("/connect/{id}")
    public ResponseEntity<ApiResponse<Void>> disconnectAccount(
            Authentication authentication,
            @PathVariable Long id) throws Exception {
        Long userId = (Long) authentication.getPrincipal();
        connectService.disconnect(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null, "연동이 해지되었습니다."));
    }

    // 증권 자산 수동 새로고침 — 5분에 한 번
    @PostMapping("/sync/stock")
    public ResponseEntity<ApiResponse<StockRefreshResponse>> refreshStock(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        StockRefreshResponse result = stockRefreshService.refresh(userId);
        if (result.hasFailure()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("일부 증권 계좌 동기화에 실패했습니다.", ErrorCode.CODEF_SYNC_FAILED.name()));
        }
        String message = result.accounts().isEmpty() ? "업데이트된 자산 정보가 없습니다." : "자산 정보 동기화가 완료되었습니다.";
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }
}
