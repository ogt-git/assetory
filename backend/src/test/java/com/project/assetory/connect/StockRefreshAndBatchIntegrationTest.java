package com.project.assetory.connect;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.domain.SyncJob;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.connect.repository.SyncJobRepository;
import com.project.assetory.connect.service.StockSyncBatchScheduler;
import com.project.assetory.connect.service.StockSyncBatchService;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockRefreshAndBatchIntegrationTest extends IntegrationTestSupport {

    private static final String AUTH_FAIL = "{\"result\":{\"code\":\"CF-12800\",\"message\":\"x\"},\"data\":{}}";

    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired SyncJobRepository syncJobRepository;
    @Autowired StockSyncBatchService stockSyncBatchService;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ApplicationContext context;

    private String successBody;
    private User user;
    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        when(codefApiClient.encryptRSA(anyString())).thenReturn("rsa-encrypted");
        try (InputStream in = getClass().getResourceAsStream("/codef/stock-financial-assets-success.json")) {
            successBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 기본은 성공. 테스트마다 connectedId로 실패할 계정을 고른다
        when(codefApiClient.requestProduct(anyString(), any())).thenReturn(successBody);

        String email = signupNew();
        user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        accessToken = objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    // ---------- 수동 새로고침 ----------

    @Test
    @DisplayName("수동 새로고침 성공 → 200, 계정별 결과와 다음 요청 가능 시각, sync_jobs HOLDING 기록")
    void refreshSuccess() throws Exception {
        Long id = newStockAccount("0240");

        refresh().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts[0].connectionId").value(id))
                .andExpect(jsonPath("$.data.accounts[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nextAvailableAt").exists());

        assertThat(jobs(id)).singleElement().satisfies(job -> {
            assertThat(job.getJobType()).isEqualTo(SyncJobType.HOLDING);
            assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        });
    }

    @Test
    @DisplayName("쿨다운 중 요청 → 429, Retry-After 헤더와 남은 시간(초), 동기화하지 않음")
    void refreshDuringCooldown() throws Exception {
        Long id = newStockAccount("0240");
        refresh().andExpect(status().isOk());

        refresh().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("CODEF_COOLDOWN_ACTIVE"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.greaterThan(0), org.hamcrest.Matchers.lessThanOrEqualTo(300))))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        assertThat(jobs(id)).hasSize(1);
    }

    @Test
    @DisplayName("수집이 실패해도 쿨다운은 유지된다 (502 + 계정별 실패 사유)")
    void failedRefreshStillCoolsDown() throws Exception {
        newStockAccount("0240");
        // 연동 상태를 바꾸지 않는 실패(기관 점검) — 인증 오류면 AUTH_FAILED가 되어 다음 요청은 대상 없음(404)
        when(codefApiClient.requestProduct(anyString(), any()))
                .thenReturn("{\"result\":{\"code\":\"CF-12701\",\"message\":\"x\"},\"data\":{}}");

        refresh().andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.data.accounts[0].status").value("FAILED"));
        refresh().andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("새로고침할 증권 연동이 없으면 404, 쿨다운을 걸지 않음")
    void refreshWithoutAccounts() throws Exception {
        refresh().andExpect(status().isNotFound());

        assertThat(redis.hasKey(cooldownKey())).isFalse();
    }

    @Test
    @DisplayName("모든 연동이 이미 동기화 중이면 409, 쿨다운을 돌려준다")
    void refreshWhileSyncing() throws Exception {
        Long id = newStockAccount("0240");
        String lockKey = "codef:lock:" + id;
        redis.opsForValue().set(lockKey, "batch-worker");
        try {
            refresh().andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CODEF_SYNC_IN_PROGRESS"));
            assertThat(redis.hasKey(cooldownKey())).isFalse();
            assertThat(redis.opsForValue().get(lockKey)).isEqualTo("batch-worker");
        } finally {
            redis.delete(lockKey);
        }
    }

    // ---------- 배치 ----------

    @Test
    @DisplayName("배치 중 한 계정이 실패해도 나머지 계정은 동기화된다 — 계정별 sync_jobs")
    void batchContinuesAfterFailure() {
        Long ok1 = newStockAccount("0240");
        Long broken = newStockAccount("0243");
        Long ok2 = newStockAccount("0218");
        failFor(broken);

        StockSyncBatchService.BatchResult result = stockSyncBatchService.syncAllActive();

        assertThat(result.total()).isGreaterThanOrEqualTo(3);
        assertThat(lastJobStatus(ok1)).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(lastJobStatus(ok2)).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(lastJobStatus(broken)).isEqualTo(SyncJobStatus.FAILED);
        assertThat(connectedAccountRepository.findById(broken).orElseThrow().getStatus())
                .isEqualTo(ConnectedAccountStatus.AUTH_FAILED); // 인증 오류 → 상태 전이 (비활성화·삭제 아님)
    }

    @Test
    @DisplayName("배치는 ACTIVE 연동만 수집하고, 다른 동기화가 진행 중인 계정은 건너뛴다")
    void batchSkipsInactiveAndLocked() {
        Long active = newStockAccount("0240");
        Long expired = newStockAccount("0243");
        Long locked = newStockAccount("0218");
        transactionTemplate.executeWithoutResult(s ->
                connectedAccountRepository.findById(expired).orElseThrow().changeStatus(ConnectedAccountStatus.EXPIRED));
        redis.opsForValue().set("codef:lock:" + locked, "manual-worker");
        try {
            stockSyncBatchService.syncAllActive();

            assertThat(lastJobStatus(active)).isEqualTo(SyncJobStatus.SUCCESS);
            assertThat(jobs(expired)).isEmpty();
            assertThat(jobs(locked)).isEmpty();
            assertThat(redis.opsForValue().get("codef:lock:" + locked)).isEqualTo("manual-worker");
        } finally {
            redis.delete("codef:lock:" + locked);
        }
    }

    @Test
    @DisplayName("배치 트리거는 기본 비활성 — assetory.sync.batch.enabled가 없으면 스케줄러 빈이 없다")
    void batchSchedulerDisabledByDefault() {
        assertThat(context.getBeanNamesForType(StockSyncBatchScheduler.class)).isEmpty();
    }

    // ---------- helpers ----------

    private ResultActions refresh() throws Exception {
        return mockMvc.perform(post("/api/codef/sync/stock").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private String cooldownKey() {
        return "codef:refresh:cooldown:" + user.getId() + ":STOCK";
    }

    private Long newStockAccount(String orgCode) {
        return transactionTemplate.execute(s -> {
            var institution = institutionRepository.findByCodefOrgCode(orgCode).orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, "cid-" + UUID.randomUUID(), BusinessType.ST));
            assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901"));
            return account.getId();
        });
    }

    // 해당 연동 계정의 connectedId로 온 요청만 인증 실패로 응답
    @SuppressWarnings("unchecked")
    private void failFor(Long connectedAccountId) {
        String connectedId = connectedAccountRepository.findById(connectedAccountId).orElseThrow().getConnectedId();
        try {
            when(codefApiClient.requestProduct(anyString(), any())).thenAnswer(inv -> {
                HashMap<String, Object> params = inv.getArgument(1);
                return connectedId.equals(params.get("connectedId")) ? AUTH_FAIL : successBody;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<SyncJob> jobs(Long connectedAccountId) {
        return syncJobRepository.findAllByConnectedAccountIdOrderByIdAsc(connectedAccountId);
    }

    private SyncJobStatus lastJobStatus(Long connectedAccountId) {
        List<SyncJob> jobs = jobs(connectedAccountId);
        assertThat(jobs).isNotEmpty();
        return jobs.get(jobs.size() - 1).getStatus();
    }
}
