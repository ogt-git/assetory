package com.project.assetory.market.kis;

import com.project.assetory.market.exception.KisApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.project.assetory.market.kis.KisTestSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 토큰은 만료 전에 다시 발급하지 않는다 — 발급 1분 1회 제한 · 발급마다 알림톡 (05 §9 kis:access-token)
class KisTokenManagerTest {

    private MockRestServiceServer server;
    private KisTestSupport.FakeRedis redis;
    private KisTokenManager tokenManager;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(KisTestSupport.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        redis = new KisTestSupport.FakeRedis();
        meterRegistry = new SimpleMeterRegistry();
        tokenManager = new KisTokenManager(builder.build(), redis.template, KisTestSupport.properties(2),
                new KisMetrics(meterRegistry));
    }

    @Test
    @DisplayName("Redis에 유효한 토큰이 있으면 발급 API를 부르지 않는다")
    void cachedToken_noIssue() {
        redis.values.put(KisTokenManager.TOKEN_KEY, "cached-token");

        assertThat(tokenManager.getToken()).isEqualTo("cached-token");
        server.verify();
    }

    @Test
    @DisplayName("토큰이 없으면 1회 발급하고, 만료 시각에서 여유를 뺀 TTL로 저장한다")
    void noToken_issueOnceAndStore() {
        server.expect(ExpectedCount.once(), requestTo(KisTestSupport.BASE_URL + "/oauth2/tokenP"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"grant_type\":\"client_credentials\"}"))
                .andRespond(withSuccess(fixture("token-success.json"), MediaType.APPLICATION_JSON));

        assertThat(tokenManager.getToken()).isEqualTo("test-only-kis-token-1");
        assertThat(tokenManager.getToken()).isEqualTo("test-only-kis-token-1");

        server.verify();
        assertThat(redis.values).containsEntry(KisTokenManager.TOKEN_KEY, "test-only-kis-token-1");
        // expires_in 86400초 − 여유 10분
        assertThat(redis.ttls.get(KisTokenManager.TOKEN_KEY)).isEqualTo(Duration.ofSeconds(86400 - 600));
        assertThat(meterRegistry.get("external.api.calls").tags("provider", "kis", "api", "token", "result", "success")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 여러 요청이 와도 발급은 1회")
    void concurrent_issueOnce() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(KisTestSupport.BASE_URL + "/oauth2/tokenP"))
                .andRespond(withSuccess(fixture("token-success.json"), MediaType.APPLICATION_JSON));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(tokenManager::getToken);
            for (Future<String> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isEqualTo("test-only-kis-token-1");
            }
        } finally {
            pool.shutdownNow();
        }
        server.verify();
    }

    @Test
    @DisplayName("만료 토큰으로 재발급을 요청하면 다시 발급한다. 다른 스레드가 이미 새 토큰을 받았으면 그것을 쓴다")
    void reissue() {
        redis.values.put(KisTokenManager.TOKEN_KEY, "expired-token");
        server.expect(ExpectedCount.once(), requestTo(KisTestSupport.BASE_URL + "/oauth2/tokenP"))
                .andRespond(withSuccess(fixture("token-success.json"), MediaType.APPLICATION_JSON));

        assertThat(tokenManager.reissue("expired-token")).isEqualTo("test-only-kis-token-1");
        // 같은 만료 토큰으로 늦게 들어온 요청 — 이미 바뀌었으므로 발급하지 않는다
        assertThat(tokenManager.reissue("expired-token")).isEqualTo("test-only-kis-token-1");
        server.verify();
    }

    @Test
    @DisplayName("발급이 실패하면 재시도하지 않고 실패로 올린다. 앱키 · 시크릿은 메시지에 없다")
    void issueFailure_noRetry() {
        server.expect(ExpectedCount.once(), requestTo(KisTestSupport.BASE_URL + "/oauth2/tokenP"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON).body(fixture("token-issue-fail.json")));

        assertThatThrownBy(() -> tokenManager.getToken())
                .isInstanceOf(KisApiException.class)
                .satisfies(e -> {
                    KisApiException ex = (KisApiException) e;
                    assertThat(ex.getErrorType()).isEqualTo(KisErrorType.TOKEN_ISSUE_FAILED);
                    assertThat(ex.getKisCode()).isEqualTo("EGW00133");
                    assertThat(ex.getMessage())
                            .doesNotContain(KisTestSupport.APP_KEY)
                            .doesNotContain(KisTestSupport.APP_SECRET);
                });
        server.verify();
        assertThat(redis.values).doesNotContainKey(KisTokenManager.TOKEN_KEY);
    }

    @Test
    @DisplayName("앱키가 없으면 발급을 시도하지 않고 설정 누락으로 실패한다 — 키 없이도 기동은 된다")
    void missingKey_failsWithoutCall() {
        KisProperties noKey = new KisProperties(KisTestSupport.BASE_URL, "", "", 0,
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofMinutes(10),
                new KisProperties.RateLimit(2, 0));
        RestClient.Builder builder = RestClient.builder().baseUrl(KisTestSupport.BASE_URL);
        MockRestServiceServer noCallServer = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager manager = new KisTokenManager(builder.build(), redis.template, noKey,
                new KisMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(manager::getToken)
                .isInstanceOf(KisApiException.class)
                .extracting(e -> ((KisApiException) e).getErrorType())
                .isEqualTo(KisErrorType.CONFIG_MISSING);
        noCallServer.verify();
    }
}
