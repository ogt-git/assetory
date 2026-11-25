package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.exception.DartApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// corp_code 메모리 캐시 (결정 D4, B2 확정 사항 9) — KST 날짜 기준 하루 1회 수신, 동시에 한 번만
class DartCorpCodeCacheTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Map<String, String> MAP_V1 = Map.of("999901", "00999901");
    private static final Map<String, String> MAP_V2 = Map.of("999901", "00999901", "999903", "00999903");

    private DartApiClient client;
    private MutableClock clock;
    private DartCorpCodeCache cache;

    @BeforeEach
    void setUp() {
        client = mock(DartApiClient.class);
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 16, 5, 0, 0, KST).toInstant());
        cache = new DartCorpCodeCache(client, clock);
    }

    @Test
    @DisplayName("1. 같은 날 여러 번 조회 → 수신 1회")
    void sameDay_fetchOnce() {
        when(client.corpCodes()).thenReturn(MAP_V1);

        for (int i = 0; i < 5; i++) {
            assertThat(cache.corpCodeOf("999901")).contains("00999901");
        }
        verify(client, times(1)).corpCodes();
    }

    @Test
    @DisplayName("2. KST 날짜가 바뀌면 다시 받는다 — 자정 직전 · 직후로 확인")
    void dateChange_refetch() {
        when(client.corpCodes()).thenReturn(MAP_V1, MAP_V2);
        clock.set(ZonedDateTime.of(2026, 11, 5, 23, 59, 0, 0, KST).toInstant());
        assertThat(cache.corpCodeOf("999903")).isEmpty();

        clock.set(ZonedDateTime.of(2026, 11, 6, 0, 1, 0, 0, KST).toInstant());

        assertThat(cache.corpCodeOf("999903")).contains("00999903");
        verify(client, times(2)).corpCodes();
    }

    @Test
    @DisplayName("3. 동시 10개 스레드의 첫 조회 → 수신 1회, 모두 같은 결과")
    void concurrentFirstLookup_fetchOnce() throws Exception {
        when(client.corpCodes()).thenAnswer(inv -> {
            Thread.sleep(200); // 받는 동안 다른 스레드가 들어오게
            return MAP_V1;
        });
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<String>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.corpCodeOf("999901");
                }));
            }
            start.countDown();
            for (Future<Optional<String>> f : results) {
                assertThat(f.get(5, TimeUnit.SECONDS)).contains("00999901");
            }
        } finally {
            pool.shutdownNow();
        }
        verify(client, times(1)).corpCodes();
    }

    @Test
    @DisplayName("4-a. 첫 수신 실패 → 실패 (맵이 없는데 0건으로 굳히지 않는다)")
    void firstFetchFails_throws() {
        when(client.corpCodes()).thenThrow(new DartApiException(DartErrorType.NETWORK_ERROR, null));

        assertThatThrownBy(() -> cache.corpCodeOf("999901")).isInstanceOf(DartApiException.class);
    }

    @Test
    @DisplayName("4-b. 첫 수신 실패 뒤 다음 조회에서 다시 시도해 성공하면 그 맵을 쓴다")
    void firstFetchFails_thenRetries() {
        when(client.corpCodes())
                .thenThrow(new DartApiException(DartErrorType.NETWORK_ERROR, null))
                .thenReturn(MAP_V1);

        assertThatThrownBy(() -> cache.corpCodeOf("999901")).isInstanceOf(DartApiException.class);
        assertThat(cache.corpCodeOf("999901")).contains("00999901");
        verify(client, times(2)).corpCodes();
    }

    @Test
    @DisplayName("4-c. 이전 맵이 있고 재수신 실패 → 이전 맵으로 답하고, 그날은 다시 받지 않는다")
    void refetchFails_usesPreviousMap() {
        when(client.corpCodes())
                .thenReturn(MAP_V1)
                .thenThrow(new DartApiException(DartErrorType.SERVER_ERROR, "800"));
        cache.corpCodeOf("999901");
        clock.set(ZonedDateTime.of(2026, 11, 6, 9, 0, 0, 0, KST).toInstant());

        assertThat(cache.corpCodeOf("999901")).contains("00999901");
        assertThat(cache.corpCodeOf("999901")).contains("00999901");
        verify(client, times(2)).corpCodes();

        // 다음 날에는 다시 시도한다 (직전 스텁이 예외라 when(...) 대신 doReturn으로 바꾼다)
        doReturn(MAP_V2).when(client).corpCodes();
        clock.set(ZonedDateTime.of(2026, 11, 7, 9, 0, 0, 0, KST).toInstant());
        assertThat(cache.corpCodeOf("999903")).contains("00999903");
    }

    @Test
    @DisplayName("5. 맵에 없는 종목(비상장 · ETF 등) → 빈 결과, 정상")
    void unknownTicker_empty() {
        when(client.corpCodes()).thenReturn(MAP_V1);

        assertThat(cache.corpCodeOf("000000")).isEmpty();
    }

    /** 테스트 전용 — 시각을 바꿀 수 있는 시계 */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
