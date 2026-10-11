package com.project.assetory.connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.connect.codef.CodefApiClient;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.Institution;
import com.project.assetory.connect.dto.CodefConnectRequest;
import com.project.assetory.connect.exception.CodefAccountNotFoundException;
import com.project.assetory.connect.exception.CodefAlreadyConnectedException;
import com.project.assetory.connect.exception.CodefApiException;
import com.project.assetory.connect.exception.CodefConnectInProgressException;
import com.project.assetory.connect.exception.CodefUnsupportedOperationException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectService {

    private static final String CONNECT_LOCK_PREFIX = "codef:connect:lock:";
    private static final long CONNECT_LOCK_TTL_MINUTES = 5L;

    // 소유권(UUID)이 일치할 때만 삭제 — GET 후 DEL 사이에 다른 요청의 락을 지우는 일을 막는다
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    // ID/PW 방식(loginType=1)으로 증권 자산 조회를 지원하지 않는 증권사 기관 코드
    private static final Set<String> STOCK_IDPW_UNSUPPORTED_ORGS = Set.of(
            "0225",  // IBK투자증권
            "0243",  // 한국투자증권
            "0262",  // 하이투자증권
            "0264",  // 키움증권
            "0270",  // 하나증권
            "0279",  // DB금융투자
            "0287"   // 메리츠증권
    );

    // 인증서 방식(loginType=0)으로 증권 자산 조회를 지원하지 않는 증권사 기관 코드
    private static final Set<String> STOCK_CERT_UNSUPPORTED_ORGS = Set.of(
            "0227"   // 다올투자증권
    );

    private final CodefApiClient codefApiClient;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    // 은행·증권 계정 연결 (connectedId 발급). 같은 기관·업무구분은 기존 행을 갱신한다 (FR-CN-08)
    public ConnectedAccount connectAccount(Long userId, CodefConnectRequest request) throws Exception {
        String businessType = request.getBusinessType().toUpperCase();
        String loginType    = request.getLoginType();

        // 1. 요청 검증
        if (!hasValue(request.getOrganization())) {
            throw new IllegalArgumentException("organization은 필수입니다.");
        }
        if (!"BK".equals(businessType) && !"ST".equals(businessType)) {
            throw new IllegalArgumentException("businessType은 BK(은행) 또는 ST(증권)만 허용됩니다.");
        }
        if (!"0".equals(loginType) && !"1".equals(loginType)) {
            throw new IllegalArgumentException("loginType은 '0'(인증서) 또는 '1'(아이디/패스워드)만 허용됩니다.");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("password는 필수입니다.");
        }

        // 증권 + 미지원 로그인 방식 조합 사전 차단 — CODEF createAccount 호출 전 거절
        if ("ST".equals(businessType) && "1".equals(loginType)
                && STOCK_IDPW_UNSUPPORTED_ORGS.contains(request.getOrganization())) {
            throw new CodefUnsupportedOperationException(
                    "해당 증권사는 ID/PW 방식으로 자산 조회를 지원하지 않습니다. 인증서 방식으로 연동해 주세요.",
                    "STOCK_IDPW_UNSUPPORTED");
        }
        if ("ST".equals(businessType) && "0".equals(loginType)
                && STOCK_CERT_UNSUPPORTED_ORGS.contains(request.getOrganization())) {
            throw new CodefUnsupportedOperationException(
                    "해당 증권사는 인증서 방식으로 자산 조회를 지원하지 않습니다.",
                    "STOCK_CERT_UNSUPPORTED");
        }

        // 2. 기관 조회 (institutions 테이블) + 업무 구분 일치 확인
        Institution institution = institutionRepository.findByCodefOrgCode(request.getOrganization())
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 금융기관입니다."));
        BusinessType type = BusinessType.valueOf(businessType);
        if (institution.getInstitutionType() != type.getInstitutionType()) {
            throw new IllegalArgumentException("선택한 금융기관과 업무 구분이 맞지 않습니다.");
        }

        // 3. active 연동 선조회 — CODEF createAccount 호출 전 차단
        if (isAlreadyActive(userId, institution, type)) {
            throw new CodefAlreadyConnectedException();
        }

        // 4. Redis connect lock 획득 (setIfAbsent = NX EX, UUID 소유권 토큰)
        String lockKey   = CONNECT_LOCK_PREFIX + userId + ":" + institution.getId();
        String lockToken = UUID.randomUUID().toString();
        Boolean locked   = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, CONNECT_LOCK_TTL_MINUTES, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(locked)) {
            throw new CodefConnectInProgressException();
        }

        try {
            // 5. 락 획득 후 active 재조회 — race condition 방지 (double-check)
            if (isAlreadyActive(userId, institution, type)) {
                throw new CodefAlreadyConnectedException();
            }

            log.info("[Connect] userId={} organization={} businessType={} loginType={}",
                    userId, request.getOrganization(), businessType, loginType);

            // 6. RSA 암호화 + CODEF createAccount 호출 (기관 비밀번호·인증서는 이 요청에만 쓰고 저장·로그하지 않음)
            String encryptedPw = codefApiClient.encryptRSA(request.getPassword());

            HashMap<String, Object> accountMap = new HashMap<>();
            accountMap.put("countryCode", "KR");
            accountMap.put("businessType", businessType);
            accountMap.put("clientType", type.getClientType());
            accountMap.put("organization", request.getOrganization());
            accountMap.put("loginType", loginType);
            accountMap.put("password", encryptedPw);

            if ("0".equals(loginType)) {
                // 인증서 방식 — derFile, keyFile 필수 / certType 하드코딩
                if (!hasValue(request.getDerFileBase64()) || !hasValue(request.getKeyFileBase64())) {
                    throw new IllegalArgumentException("인증서 방식(loginType=0)은 derFile과 keyFile이 필수입니다.");
                }
                accountMap.put("certType", "1");
                accountMap.put("derFile", request.getDerFileBase64());
                accountMap.put("keyFile", request.getKeyFileBase64());
            } else {
                // 아이디/패스워드 방식 — id 필수
                if (!hasValue(request.getId())) {
                    throw new IllegalArgumentException("아이디/패스워드 방식(loginType=1)은 id가 필수입니다.");
                }
                accountMap.put("id", request.getId());
            }

            if (hasValue(request.getBirthDate())) {
                accountMap.put("birthDate", request.getBirthDate());
            }

            List<HashMap<String, Object>> accountList = new ArrayList<>();
            accountList.add(accountMap);

            HashMap<String, Object> parameterMap = new HashMap<>();
            parameterMap.put("accountList", accountList);

            String response = codefApiClient.createAccount(parameterMap);

            JsonNode root = objectMapper.readTree(response);
            String resultCode    = root.path("result").path("code").asText();
            String resultMessage = root.path("result").path("message").asText();

            log.info("[Connect] CODEF result code={} message={}", resultCode, resultMessage);

            if (!"CF-00000".equals(resultCode)) {
                throw new CodefApiException(resultCode, resultMessage);
            }

            // 7. DB 저장 또는 기존 행 갱신 (UNIQUE(user_id, institution_id, business_type))
            String connectedId = root.path("data").path("connectedId").asText().replaceAll("[\\r\\n\\s]", "");
            if (connectedId.isEmpty()) {
                throw new CodefApiException("CONNECTED_ID_EMPTY", "CODEF connectedId가 비어 있습니다. organizationCode=" + request.getOrganization());
            }

            Optional<ConnectedAccount> existing = connectedAccountRepository
                    .findByUserIdAndInstitutionIdAndBusinessType(userId, institution.getId(), type);

            // 재인증(AUTH_FAILED·EXPIRED) — 이전 connectedId는 CODEF에서 먼저 정리해 고아 계정을 남기지 않는다 (FR-CN-05)
            existing.filter(prev -> prev.getConnectedId() != null)
                    .ifPresent(prev -> deleteCodefAccountQuietly(prev.getConnectedId(), type,
                            request.getOrganization(), prev.getId()));

            ConnectedAccount conn = existing.orElseGet(() -> ConnectedAccount.create(
                    userRepository.getReferenceById(userId), institution, connectedId, type));
            conn.reconnect(connectedId);
            connectedAccountRepository.save(conn);
            log.info("[Connect] saved/reactivated connectedId for org={} type={}", request.getOrganization(), type);

            // 기관이 로딩된 conn을 돌려준다 (merge 결과는 기관이 지연 로딩 프록시일 수 있음 — open-in-view 꺼짐)
            return conn;

        } finally {
            // 8. connect lock 해제 (소유권 검증 후 삭제)
            releaseConnectLock(lockKey, lockToken, userId);
        }
    }

    // 연동 해지 — CODEF deleteAccount 호출(실패해도 진행) 후 DISCONNECTED, connectedId 즉시 파기 (D-21)
    // CODEF 호출이 DB 트랜잭션을 붙잡지 않도록 트랜잭션 없이 조회 → 호출 → 저장 순서로 처리
    public void disconnect(Long userId, Long connectionId) {
        ConnectedAccount connection = connectedAccountRepository
                .findByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new AccessDeniedException("해당 연동 정보에 접근할 수 없습니다."));
        if (connection.getStatus() == ConnectedAccountStatus.DISCONNECTED) {
            throw new CodefAccountNotFoundException("이미 해지된 연동입니다.");
        }

        if (connection.getConnectedId() != null) {
            deleteCodefAccountQuietly(connection.getConnectedId(), connection.getBusinessType(),
                    connection.getInstitution().getCodefOrgCode(), connectionId);
        }

        connection.disconnect();
        connectedAccountRepository.save(connection);

        log.info("[Disconnect] connectionId={} userId={} deactivated", connectionId, userId);
    }

    // AUTH_FAILED·EXPIRED도 함께 보여준다 — 사용자가 재인증할 수 있게 (FR-CN-05)
    public List<ConnectedAccount> getConnections(Long userId) {
        return connectedAccountRepository.findAllByUserIdAndStatusNotOrderByIdAsc(
                userId, ConnectedAccountStatus.DISCONNECTED);
    }

    // 인증서 파일을 읽어 Base64 인코딩 후 request DTO에 설정
    public void attachCertFiles(CodefConnectRequest request, MultipartFile derFile, MultipartFile keyFile) throws Exception {
        validateCertFileExtension(derFile, ".der");
        validateCertFileExtension(keyFile, ".key");
        // getMimeEncoder() 사용 금지 — 76자마다 \r\n 삽입으로 CODEF CF-11204 유발
        request.setDerFileBase64(Base64.getEncoder().encodeToString(derFile.getBytes()));
        request.setKeyFileBase64(Base64.getEncoder().encodeToString(keyFile.getBytes()));
    }

    private void validateCertFileExtension(MultipartFile file, String expectedExt) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("인증서 파일이 비어 있습니다: " + expectedExt);
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(expectedExt)) {
            throw new IllegalArgumentException("허용되지 않는 파일 형식입니다: " + originalName + " (허용: " + expectedExt + ")");
        }
    }

    // CODEF 계정 삭제 — 실패해도 로컬 처리는 계속한다 (원본 방식)
    private void deleteCodefAccountQuietly(String connectedId, BusinessType type, String organization, Long connectionId) {
        HashMap<String, Object> accountMap = new HashMap<>();
        accountMap.put("connectedId", connectedId);
        accountMap.put("countryCode", "KR");
        accountMap.put("businessType", type.name());
        accountMap.put("clientType", type.getClientType());
        accountMap.put("organization", organization);

        ArrayList<HashMap<String, Object>> accountList = new ArrayList<>();
        accountList.add(accountMap);

        HashMap<String, Object> params = new HashMap<>();
        params.put("accountList", accountList);

        try {
            String response = codefApiClient.deleteAccount(params);
            JsonNode root = objectMapper.readTree(response);
            String resultCode = root.path("result").path("code").asText();
            if (!"CF-00000".equals(resultCode)) {
                log.warn("[Disconnect] CODEF 해지 응답 비정상 code={} connectionId={}", resultCode, connectionId);
            }
        } catch (Exception e) {
            log.warn("[Disconnect] CODEF deleteAccount 실패 connectionId={} error={}", connectionId, e.getClass().getSimpleName());
        }
    }

    private boolean isAlreadyActive(Long userId, Institution institution, BusinessType type) {
        return connectedAccountRepository
                .findByUserIdAndInstitutionIdAndBusinessType(userId, institution.getId(), type)
                .map(ConnectedAccount::isActive)
                .orElse(false);
    }

    private void releaseConnectLock(String lockKey, String lockToken, Long userId) {
        Long released = stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
        if (released == null || released == 0L) {
            log.warn("[Connect] 락 소유권 불일치 — 해제 건너뜀 userId={} (TTL 만료 후 재발급된 락)", userId);
        }
    }

    private boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }
}
