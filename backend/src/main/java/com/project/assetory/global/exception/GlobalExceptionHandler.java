package com.project.assetory.global.exception;

import com.project.assetory.global.response.ApiResponse;
import com.project.assetory.connect.exception.CodefAccountNotFoundException;
import com.project.assetory.connect.exception.CodefAlreadyConnectedException;
import com.project.assetory.connect.codef.CodefErrorType;
import com.project.assetory.connect.exception.CodefApiException;
import com.project.assetory.connect.exception.CodefAuthException;
import com.project.assetory.connect.exception.CodefConnectInProgressException;
import com.project.assetory.connect.exception.CodefInstitutionUnavailableException;
import com.project.assetory.connect.exception.CodefSyncException;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.exception.RefreshCooldownException;
import com.project.assetory.market.exception.KisApiException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<?>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("필수 헤더가 누락되었습니다: " + e.getHeaderName(), "MISSING_HEADER"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        if (message.isBlank()) message = "입력값이 올바르지 않습니다.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, "VALIDATION_ERROR"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, "VALIDATION_ERROR"));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<?>> handleAuth(AuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(e.getMessage(), ErrorCode.AUTH_FAILED.name()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiResponse<?>> handleDuplicateEmail(DuplicateEmailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(e.getMessage(), ErrorCode.DUPLICATE_EMAIL.name()));
    }

    //데이터 중복 방지 핸들러
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("이미 존재하는 데이터입니다.", "DUPLICATE_DATA"));
    }

    //리소스 찾기 실패 핸들러
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleEntityNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getMessage(), ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    //보안 및 권한 핸들러
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.ACCESS_DENIED.getMessage(), ErrorCode.ACCESS_DENIED.name()));
    }

    //API 호출 제한 핸들러
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<?>> handleTooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(e.getMessage(), ErrorCode.TOO_MANY_REQUESTS.name()));
    }

    // 수동 새로고침 쿨다운 — 남은 초를 Retry-After 헤더와 본문에 함께 준다
    @ExceptionHandler(RefreshCooldownException.class)
    public ResponseEntity<ApiResponse<?>> handleRefreshCooldown(RefreshCooldownException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiResponse.error(e.getMessage(), "CODEF_COOLDOWN_ACTIVE",
                        Map.of("retryAfterSeconds", e.getRetryAfterSeconds())));
    }

    // 같은 연동 계정 동기화가 이미 진행 중 — 409 Conflict
    @ExceptionHandler(CodefSyncLockConflictException.class)
    public ResponseEntity<ApiResponse<?>> handleSyncLockConflict(CodefSyncLockConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(e.getMessage(), "CODEF_SYNC_IN_PROGRESS"));
    }

    @ExceptionHandler(CodefAuthException.class)
    public ResponseEntity<ApiResponse<?>> handleCodefAuth(CodefAuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(e.getMessage(), ErrorCode.CODEF_AUTH_FAILED.name()));
    }

    @ExceptionHandler(CodefAccountNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleCodefAccountNotFound(CodefAccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getMessage(), ErrorCode.CONNECTION_NOT_FOUND.name()));
    }

    @ExceptionHandler(CodefSyncException.class)
    public ResponseEntity<ApiResponse<?>> handleCodefSync(CodefSyncException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(e.getMessage(), ErrorCode.CODEF_SYNC_FAILED.name()));
    }

    // CODEF 실패 응답 — 사용자에게는 유형별 메시지와 유형 이름만 준다 (CODEF 원문 메시지는 로그에만)
    @ExceptionHandler(CodefApiException.class)
    public ResponseEntity<ApiResponse<?>> handleCodefApi(CodefApiException e) {
        // CODEF 원문 메시지는 계좌번호 등이 섞일 수 있어 로그에도 남기지 않는다 (NFR-SE-07)
        log.warn("[CODEF] 실패 응답 code={} type={}", e.getCodefCode(), e.getErrorType());
        // 기관·로그인 방식 미지원(CF-12030·12040·12050)은 요청 조합 문제라 422 — 사전 차단 없이 CODEF 응답으로만 안내 (S-1)
        HttpStatus status = e.getErrorType() == CodefErrorType.UNSUPPORTED_OPERATION
                ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(ApiResponse.error(e.getErrorType().getUserMessage(), e.getErrorType().name()));
    }

    // 이미 활성 연동이 존재 — 409 Conflict
    @ExceptionHandler(CodefAlreadyConnectedException.class)
    public ResponseEntity<ApiResponse<?>> handleCodefAlreadyConnected(CodefAlreadyConnectedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(e.getMessage(), "CODEF_ALREADY_CONNECTED"));
    }

    // 동일 기관 연동이 진행 중 — 409 Conflict
    @ExceptionHandler(CodefConnectInProgressException.class)
    public ResponseEntity<ApiResponse<?>> handleCodefConnectInProgress(CodefConnectInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(e.getMessage(), "CODEF_CONNECT_IN_PROGRESS"));
    }

    // 금융기관 사이트 변경/점검으로 조회 불가 (CF-12701, CF-12710) — 503 Service Unavailable
    @ExceptionHandler(CodefInstitutionUnavailableException.class)
    public ResponseEntity<ApiResponse<?>> handleCodefInstitutionUnavailable(CodefInstitutionUnavailableException e) {
        log.warn("[CODEF] 금융기관 조회 불가 code={}", e.getCodefCode());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(e.getMessage(), "INSTITUTION_UNAVAILABLE"));
    }

    // 잘못된 상태 전환 → 400
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getMessage(), "INVALID_STATE"));
    }

    // 날짜 범위 오류(startDate > endDate) 등 잘못된 파라미터 → 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getMessage(), "INVALID_ARGUMENT"));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(403, e.getMessage()));
    }

    // 본문 형식 오류 (JSON 문법, 알 수 없는 enum 값 등) → 400. 입력값은 메시지에 넣지 않는다
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("요청 본문 형식이 올바르지 않습니다.", "INVALID_REQUEST_BODY"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("잘못된 파라미터 값입니다: " + e.getName() + "=" + e.getValue(), "INVALID_PARAMETER"));
    }

    @ExceptionHandler(MailException.class)
    public ResponseEntity<ApiResponse<?>> handleMail(MailException e) {
        // 메일 예외 메시지·스택에는 수신 주소가 들어갈 수 있어 예외 종류만 남긴다
        log.error("[Mail] 이메일 발송 실패 error={}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("이메일 발송에 실패했습니다. 서버 메일 설정을 확인해주세요.", "MAIL_SEND_FAILED"));
    }

    // 한투 호출 실패 — 원문 메시지 · 토큰은 응답과 로그에 남기지 않는다 (유형 · 한투 코드만)
    @ExceptionHandler(KisApiException.class)
    public ResponseEntity<ApiResponse<?>> handleKisApi(KisApiException e) {
        log.warn("[KIS] 실패 type={} code={}", e.getErrorType(), e.getKisCode());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(ErrorCode.MARKET_DATA_UNAVAILABLE.getMessage(), ErrorCode.MARKET_DATA_UNAVAILABLE.name()));
    }

    // 매핑되지 않은 경로 — 500으로 흘리지 않고 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND.getMessage(), ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("지원하지 않는 HTTP 메서드입니다: " + e.getMethod(), "METHOD_NOT_ALLOWED"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getMessage(), ErrorCode.INTERNAL_SERVER_ERROR.name()));
    }
}
