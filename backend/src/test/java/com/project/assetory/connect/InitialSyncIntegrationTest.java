package com.project.assetory.connect;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.SyncJob;
import com.project.assetory.connect.domain.SyncJobStatus;
import com.project.assetory.connect.domain.SyncJobType;
import com.project.assetory.connect.event.ConnectedAccountLinkedEvent;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.connect.repository.SyncJobRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InitialSyncIntegrationTest extends IntegrationTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired SyncJobRepository syncJobRepository;

    @BeforeEach
    void stubCodef() throws Exception {
        when(codefApiClient.encryptRSA(anyString())).thenReturn("rsa-encrypted");
        when(codefApiClient.createAccount(any())).thenAnswer(inv -> objectMapper.writeValueAsString(Map.of(
                "result", Map.of("code", "CF-00000", "message", "성공"),
                "data", Map.of("connectedId", "cid-" + UUID.randomUUID()))));
        when(codefApiClient.requestProduct(anyString(), any())).thenReturn(fixture("stock-financial-assets-success.json"));
    }

    @Test
    @DisplayName("증권 연결 API → 커밋 뒤 비동기로 BACKFILL 수집, 보유 종목 저장")
    void connectTriggersBackfill() throws Exception {
        String at = signupAndGetAccessToken();

        String body = mockMvc.perform(multipart("/api/codef/connect")
                        .param("organization", "0240").param("businessType", "ST").param("loginType", "1")
                        .param("id", "login-id").param("password", "login-pw").param("accountNumber", "12345678901")
                        .header("Authorization", "Bearer " + at))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("data").get("id").asLong();

        awaitUntil(() -> jobs(id).stream().anyMatch(j -> j.getStatus() == SyncJobStatus.SUCCESS), WAIT);
        SyncJob job = jobs(id).get(0);
        assertThat(job.getJobType()).isEqualTo(SyncJobType.BACKFILL);
        assertThat(job.getCollectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("트랜잭션 안에서 이벤트를 발행해도 커밋 전에는 수집이 실행되지 않고, 커밋 뒤에 실행된다")
    void runsOnlyAfterCommit() throws Exception {
        Connection connection = createSecuritiesConnection();
        long id = connection.id();

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new ConnectedAccountLinkedEvent(id, BusinessType.ST));
            sleep(1500); // 리스너가 즉시 실행된다면 이 사이에 CODEF 호출·작업 기록이 생김
            assertThat(jobs(id)).isEmpty();
            try {
                verifyNoRequestFor(connection.connectedId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        awaitUntil(() -> !jobs(id).isEmpty() && jobs(id).get(0).getStatus() == SyncJobStatus.SUCCESS, WAIT);
        assertThat(jobs(id).get(0).getJobType()).isEqualTo(SyncJobType.BACKFILL);
    }

    @Test
    @DisplayName("연동 저장 트랜잭션이 롤백되면 수집이 실행되지 않는다")
    void doesNotRunOnRollback() throws Exception {
        Connection connection = createSecuritiesConnection();
        long id = connection.id();

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new ConnectedAccountLinkedEvent(id, BusinessType.ST));
            status.setRollbackOnly();
        });

        sleep(2000);
        assertThat(jobs(id)).isEmpty();
        verifyNoRequestFor(connection.connectedId());
    }

    @Test
    @DisplayName("은행(BK) 연결 이벤트는 증권 수집을 실행하지 않는다 (은행 수집은 이후 개발)")
    void bankEventIgnored() throws Exception {
        long id = createSecuritiesConnection().id();

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new ConnectedAccountLinkedEvent(id, BusinessType.BK)));

        sleep(2000);
        assertThat(jobs(id)).isEmpty();
    }

    // ---------- helpers ----------

    private record Connection(long id, String connectedId) {}

    // 다른 테스트가 남긴 비동기 수집이 공유 목을 호출할 수 있어서, 이 연동(connectedId)에 대한 호출만 확인한다
    private void verifyNoRequestFor(String connectedId) throws Exception {
        verify(codefApiClient, never()).requestProduct(anyString(),
                argThat(params -> params != null && connectedId.equals(params.get("connectedId"))));
    }

    private Connection createSecuritiesConnection() throws Exception {
        String email = signupNew();
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        String connectedId = "cid-initial-" + UUID.randomUUID();
        Long[] id = new Long[1];
        transactionTemplate.executeWithoutResult(status -> {
            var institution = institutionRepository.findByCodefOrgCode("0240").orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, connectedId, BusinessType.ST));
            assetRepository.save(Asset.securitiesAccount(user, account, institution, "12345678901"));
            id[0] = account.getId();
        });
        return new Connection(id[0], connectedId);
    }

    private List<SyncJob> jobs(long connectedAccountId) {
        return syncJobRepository.findAllByConnectedAccountIdOrderByIdAsc(connectedAccountId);
    }

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/codef/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
