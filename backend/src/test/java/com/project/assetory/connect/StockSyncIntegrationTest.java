package com.project.assetory.connect;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.codef.CodefErrorType;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJob;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.exception.CodefSyncLockConflictException;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.connect.repository.SyncJobRepository;
import com.project.assetory.connect.service.StockSyncService;
import com.project.assetory.connect.service.StockSyncService.SyncResult;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockSyncIntegrationTest extends IntegrationTestSupport {

    private static final String ACCOUNT_NUMBER = "12345678901";
    private static final String CONNECTED_ID = "cid-sync-test";

    @Autowired StockSyncService stockSyncService;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired SyncJobRepository syncJobRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate transactionTemplate;

    private Long connectedAccountId;
    private Long assetId;

    @BeforeEach
    void setUp() throws Exception {
        when(codefApiClient.encryptRSA(anyString())).thenReturn("rsa-encrypted");
        String email = signupNew();
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        transactionTemplate.executeWithoutResult(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, CONNECTED_ID + UUID.randomUUID(), BusinessType.ST));
            account.changeAccountPassword("4321");
            connectedAccountId = account.getId();
            assetId = assetRepository.save(Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER)).getId();
        });
    }

    @Test
    @DisplayName("성공 → 예수금·국내 종목 저장(해외·매도 종목 제외), last_synced_at 갱신, sync_jobs SUCCESS 1행")
    void syncSuccess() throws Exception {
        respondWith(fixture("stock-financial-assets-success.json"));

        SyncResult result = stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        assertThat(result.status()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(asset().getBalance()).isEqualByComparingTo("1500000");
        assertThat(holdings()).extracting(Holding::getTicker).containsExactlyInAnyOrder("005930", "000660");
        Holding samsung = holding("005930");
        assertThat(samsung.getQuantity()).isEqualByComparingTo("20");
        assertThat(samsung.getEvalAmount()).isEqualByComparingTo("1100000");
        assertThat(samsung.getSecurityType()).isNull(); // 판별 규칙 확인 전까지 추측하지 않음
        assertThat(connectedAccount().getLastSyncedAt()).isNotNull();

        SyncJob job = onlyJob();
        assertThat(job.getJobType()).isEqualTo(SyncJobType.HOLDING);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(job.getCollectedCount()).isEqualTo(2);
        assertThat(job.getTargetDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getErrorType()).isNull();
    }

    @Test
    @DisplayName("CODEF 요청에 계좌번호·RSA 암호화한 계좌 비밀번호·connectedId를 넣는다")
    void requestParams() throws Exception {
        respondWith(fixture("stock-financial-assets-success.json"));

        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        ArgumentCaptor<HashMap<String, Object>> params = paramsCaptor();
        verify(codefApiClient).requestProduct(eq("/v1/kr/stock/a/account/financial-assets"), params.capture());
        assertThat(params.getValue())
                .containsEntry("organization", "0240")
                .containsEntry("account", ACCOUNT_NUMBER)
                .containsEntry("accountPassword", "rsa-encrypted");
        assertThat((String) params.getValue().get("connectedId")).startsWith(CONNECTED_ID);
    }

    @Test
    @DisplayName("같은 응답으로 두 번 동기화해도 결과가 같다 (멱등 — 종목 행 id·값 유지, 작업 기록만 2행)")
    void idempotent() throws Exception {
        respondWith(fixture("stock-financial-assets-success.json"));

        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);
        Map<String, Long> firstIds = holdingIds();
        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        assertThat(holdingIds()).isEqualTo(firstIds);
        assertThat(asset().getBalance()).isEqualByComparingTo("1500000");
        assertThat(holding("000660").getEvalAmount()).isEqualByComparingTo("880000");
        assertThat(syncJobRepository.findAllByConnectedAccountIdOrderByIdAsc(connectedAccountId)).hasSize(2)
                .allMatch(job -> job.getStatus() == SyncJobStatus.SUCCESS);
    }

    @Test
    @DisplayName("응답에 없는 기존 종목은 매도로 보고 삭제")
    void soldHoldingRemoved() throws Exception {
        respondWith(fixture("stock-financial-assets-success.json"));
        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        respondWith(successJson("""
                [{"resItemName":"삼성전자","resItemCode":"005930","resQuantity":"25",
                  "resValuationAmt":"1375000","resPurchaseAmount":"1250000","resValuationPL":"125000","resEarningsRate":"10"}]
                """));
        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        assertThat(holdings()).extracting(Holding::getTicker).containsExactly("005930");
        assertThat(holding("005930").getQuantity()).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("조회 실패 → 기존 자산·종목 유지, sync_jobs FAILED + 오류 유형·사용자 메시지, 인증 실패면 AUTH_FAILED")
    void failureKeepsData() throws Exception {
        respondWith(fixture("stock-financial-assets-success.json"));
        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        respondWith("{\"result\":{\"code\":\"CF-12800\",\"message\":\"계좌 12345678901 비밀번호 오류\"},\"data\":{}}");
        SyncResult result = stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        assertThat(result.status()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(asset().getBalance()).isEqualByComparingTo("1500000");
        assertThat(holdings()).hasSize(2);

        SyncJob failed = lastJob();
        assertThat(failed.getStatus()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(failed.getErrorType()).isEqualTo(CodefErrorType.AUTH_UNRECOVERABLE);
        assertThat(failed.getErrorMessage()).isEqualTo(CodefErrorType.AUTH_UNRECOVERABLE.getUserMessage())
                .doesNotContain(ACCOUNT_NUMBER).doesNotContain(CONNECTED_ID).doesNotContain("CF-");
        assertThat(connectedAccount().getStatus()).isEqualTo(ConnectedAccountStatus.AUTH_FAILED);
    }

    @Test
    @DisplayName("응답 불완전(종목 조회 실패 건) → 저장하지 않고 FAILED, 연동 상태는 유지")
    void incompleteResponseNotSaved() throws Exception {
        respondWith(fixture("stock-financial-assets-success.json"));
        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        respondWith(fixture("stock-financial-assets-incomplete.json"));
        SyncResult result = stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        assertThat(result.status()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(holdings()).extracting(Holding::getTicker).containsExactlyInAnyOrder("005930", "000660");
        assertThat(asset().getBalance()).isEqualByComparingTo("1500000");
        assertThat(connectedAccount().getStatus()).isEqualTo(ConnectedAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("통신 실패(재시도 가능)는 재시도 후 성공하면 SUCCESS — CODEF 2회 호출")
    void retriesNetworkError() throws Exception {
        when(codefApiClient.requestProduct(anyString(), any()))
                .thenReturn("{\"result\":{\"code\":\"CF-09980\",\"message\":\"timeout\"},\"data\":{}}")
                .thenReturn(fixture("stock-financial-assets-success.json"));

        SyncResult result = stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        assertThat(result.status()).isEqualTo(SyncJobStatus.SUCCESS);
        verify(codefApiClient, times(2)).requestProduct(anyString(), any());
    }

    @Test
    @DisplayName("인증 실패는 재시도하지 않는다 — CODEF 1회 호출")
    void doesNotRetryAuthError() throws Exception {
        respondWith("{\"result\":{\"code\":\"CF-12800\",\"message\":\"x\"},\"data\":{}}");

        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        verify(codefApiClient, times(1)).requestProduct(anyString(), any());
    }

    @Test
    @DisplayName("같은 연동 계정 동기화가 진행 중이면 락 충돌 — 작업 기록을 남기지 않음")
    void lockConflict() {
        redis.opsForValue().set("codef:lock:" + connectedAccountId, "other-worker");
        try {
            assertThatThrownBy(() -> stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING))
                    .isInstanceOf(CodefSyncLockConflictException.class);
            assertThat(syncJobRepository.findAllByConnectedAccountIdOrderByIdAsc(connectedAccountId)).isEmpty();
        } finally {
            redis.delete("codef:lock:" + connectedAccountId);
        }
    }

    @Test
    @DisplayName("동기화가 끝나면 락이 풀린다")
    void releasesLock() throws Exception {
        respondWith(fixture("stock-financial-assets-success.json"));

        stockSyncService.sync(connectedAccountId, SyncJobType.HOLDING);

        assertThat(redis.hasKey("codef:lock:" + connectedAccountId)).isFalse();
    }

    // ---------- helpers ----------

    private void respondWith(String body) throws Exception {
        when(codefApiClient.requestProduct(anyString(), any())).thenReturn(body);
    }

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/codef/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String successJson(String itemListJson) {
        return "{\"result\":{\"code\":\"CF-00000\",\"message\":\"성공\"},\"data\":{\"resDepositReceived\":\"1500000\",\"resItemList\":"
                + itemListJson + "}}";
    }

    private Asset asset() {
        return assetRepository.findById(assetId).orElseThrow();
    }

    private ConnectedAccount connectedAccount() {
        return connectedAccountRepository.findById(connectedAccountId).orElseThrow();
    }

    private List<Holding> holdings() {
        return holdingRepository.findAllByAssetId(assetId);
    }

    private Holding holding(String ticker) {
        return holdings().stream().filter(h -> h.getTicker().equals(ticker)).findFirst().orElseThrow();
    }

    private Map<String, Long> holdingIds() {
        Map<String, Long> ids = new HashMap<>();
        holdings().forEach(h -> ids.put(h.getTicker(), h.getId()));
        return ids;
    }

    private SyncJob onlyJob() {
        List<SyncJob> jobs = syncJobRepository.findAllByConnectedAccountIdOrderByIdAsc(connectedAccountId);
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    private SyncJob lastJob() {
        List<SyncJob> jobs = syncJobRepository.findAllByConnectedAccountIdOrderByIdAsc(connectedAccountId);
        return jobs.get(jobs.size() - 1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<HashMap<String, Object>> paramsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(HashMap.class);
    }
}
