package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요약 한도 차감 후보 · summary_provided (Q-8, ANL-6, FR-AI-02 · 03) — 05 §5.3 user_market_events.
 * - 후보: 분석에 연결된 소급 아닌 이벤트의 활성 사용자, 이벤트 id 오름차순(D2). 한도 판단 · 차감은 AI-4
 * - 갱신: 요약이 생기면 연결 이벤트 전체(소급 포함) true(D3-①), 요약이 이미 있으면 생성 시 true(D3-②, 기준 ANALYZED — D5)
 * - 경합(d): 이벤트 생성이 분석을 공유 락으로 읽어, 동시에 ANALYZED가 되어도 이벤트가 false로 남지 않는다
 * 테스트 전용 — 계정 · 종목 · 금액 · 요약 문구는 더미 값
 */
class UserMarketEventSummaryIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    // 테스트 전용 고정 시각 — 일괄 갱신의 updated_at 확인용(초 단위로 비교)
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 2, 3, 4, 5);

    @Autowired UserMarketEventRepository eventRepository;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Nested
    @DisplayName("차감 후보 조회 — 소급 아닌 · 활성 사용자, 이벤트 id 오름차순 (D2)")
    class Candidates {

        @Test
        @DisplayName("사용자 생성 순서와 무관하게 이벤트 id 오름차순으로 나온다")
        void orderedByEventId() {
            User a = newUser();
            User b = newUser();
            User c = newUser();
            StockEventAnalysis analysis = newAnalysis("-6.5");
            eventRepository.saveAndFlush(live(c, analysis));
            eventRepository.saveAndFlush(live(a, analysis));
            eventRepository.saveAndFlush(live(b, analysis));

            assertThat(eventRepository.findQuotaCandidateUserIds(analysis.getId()))
                    .containsExactly(c.getId(), a.getId(), b.getId());
        }

        @Test
        @DisplayName("소급 이벤트의 사용자는 빠진다")
        void excludesBackfilled() {
            User live = newUser();
            User backfill = newUser();
            StockEventAnalysis analysis = newAnalysis("7.1");
            eventRepository.saveAndFlush(backfilled(backfill, analysis));
            eventRepository.saveAndFlush(live(live, analysis));

            assertThat(eventRepository.findQuotaCandidateUserIds(analysis.getId())).containsExactly(live.getId());
        }

        @Test
        @DisplayName("소급 이벤트만 연결된 분석 · 이벤트가 없는 분석은 빈 목록")
        void emptyWhenNoLiveEvent() {
            StockEventAnalysis backfillOnly = newAnalysis("-5.4");
            eventRepository.saveAndFlush(backfilled(newUser(), backfillOnly));
            StockEventAnalysis none = newAnalysis("5.9");

            assertThat(eventRepository.findQuotaCandidateUserIds(backfillOnly.getId())).isEmpty();
            assertThat(eventRepository.findQuotaCandidateUserIds(none.getId())).isEmpty();
        }

        @Test
        @DisplayName("다른 분석의 이벤트는 섞이지 않는다")
        void onlyThatAnalysis() {
            User a = newUser();
            User b = newUser();
            StockEventAnalysis mine = newAnalysis("-8.0");
            StockEventAnalysis other = newAnalysis("6.0");
            eventRepository.saveAndFlush(live(a, mine));
            eventRepository.saveAndFlush(live(b, other));

            assertThat(eventRepository.findQuotaCandidateUserIds(mine.getId())).containsExactly(a.getId());
        }

        @Test
        @DisplayName("활성(ACTIVE)이 아닌 사용자(휴면 · 탈퇴)는 빠진다")
        void excludesInactiveUsers() {
            User active = newUser();
            User dormant = newUser();
            User withdrawn = newUser();
            StockEventAnalysis analysis = newAnalysis("-9.1");
            eventRepository.saveAndFlush(live(dormant, analysis));
            eventRepository.saveAndFlush(live(active, analysis));
            eventRepository.saveAndFlush(live(withdrawn, analysis));
            jdbc.update("update users set status = 'DORMANT' where id = ?", dormant.getId());
            jdbc.update("update users set status = 'WITHDRAWN' where id = ?", withdrawn.getId());

            assertThat(eventRepository.findQuotaCandidateUserIds(analysis.getId())).containsExactly(active.getId());
        }
    }

    @Nested
    @DisplayName("summary_provided 일괄 갱신 (D3-①, ANL-6)")
    class MarkProvided {

        @Test
        @DisplayName("연결된 이벤트 전체(소급 포함)가 true, 반환값은 바꾼 행 수 · updated_at은 넘긴 시각")
        void marksAllLinked() {
            StockEventAnalysis analysis = newAnalysis("-6.2");
            UserMarketEvent e1 = eventRepository.saveAndFlush(live(newUser(), analysis));
            UserMarketEvent e2 = eventRepository.saveAndFlush(backfilled(newUser(), analysis));

            int changed = tx.execute(s -> eventRepository.markSummaryProvided(analysis.getId(), NOW));

            assertThat(changed).isEqualTo(2);
            assertThat(provided(e1.getId())).isTrue();
            assertThat(provided(e2.getId())).isTrue();
            assertThat(updatedAt(e1.getId())).isEqualTo(NOW);
            assertThat(updatedAt(e2.getId())).isEqualTo(NOW);
        }

        @Test
        @DisplayName("다시 실행하면 0행 · 값과 updated_at은 그대로 (멱등)")
        void idempotent() {
            StockEventAnalysis analysis = newAnalysis("5.3");
            UserMarketEvent e = eventRepository.saveAndFlush(live(newUser(), analysis));
            tx.execute(s -> eventRepository.markSummaryProvided(analysis.getId(), NOW));

            int again = tx.execute(s -> eventRepository.markSummaryProvided(analysis.getId(), NOW.plusDays(1)));

            assertThat(again).isZero();
            assertThat(provided(e.getId())).isTrue();
            assertThat(updatedAt(e.getId())).isEqualTo(NOW);
        }

        @Test
        @DisplayName("다른 분석의 이벤트는 그대로 false")
        void otherAnalysisUntouched() {
            User user = newUser();
            StockEventAnalysis target = newAnalysis("-7.7");
            StockEventAnalysis other = newAnalysis("6.6");
            eventRepository.saveAndFlush(live(user, target));
            UserMarketEvent untouched = eventRepository.saveAndFlush(live(user, other));

            tx.execute(s -> eventRepository.markSummaryProvided(target.getId(), NOW));

            assertThat(provided(untouched.getId())).isFalse();
        }

        @Test
        @DisplayName("같은 트랜잭션에서 미리 읽어 둔 이벤트도 갱신 뒤 다시 읽으면 true (영속성 컨텍스트 비움)")
        void visibleInSameTransaction() {
            StockEventAnalysis analysis = newAnalysis("-5.8");
            UserMarketEvent saved = eventRepository.saveAndFlush(live(newUser(), analysis));

            Boolean reread = tx.execute(s -> {
                UserMarketEvent loaded = eventRepository.findById(saved.getId()).orElseThrow();
                assertThat(loaded.isSummaryProvided()).isFalse();
                eventRepository.markSummaryProvided(analysis.getId(), NOW);
                return eventRepository.findById(saved.getId()).orElseThrow().isSummaryProvided();
            });

            assertThat(reread).isTrue();
        }
    }

    @Nested
    @DisplayName("생성 시 반영 (D3-② · D5) — 감지 · 소급이 같이 쓰는 findOrCreatePending → create 경로")
    class OnCreate {

        @Test
        @DisplayName("요약이 이미 있는(ANALYZED) 분석에 나중에 생긴 이벤트는 처음부터 true")
        void analyzedAnalysis_createdTrue() {
            User user = newUser();
            StockEventAnalysis analysis = newAnalysis("-6.9");
            markAnalyzed(analysis.getId());

            Long eventId = tx.execute(s -> {
                StockEventAnalysis found = analysisRepository.findOrCreatePending(analysis.getTicker(),
                        analysis.getStockName(), DATE, analysis.getChangeRate());
                return eventRepository.save(live(userRepository.getReferenceById(user.getId()), found)).getId();
            });

            assertThat(provided(eventId)).isTrue();
        }

        @Test
        @DisplayName("경합(d) — 이벤트 생성 중 다른 트랜잭션이 ANALYZED + 일괄 갱신을 해도 이벤트는 true로 끝난다")
        void concurrentAnalyzed_endsTrue() throws Exception {
            User user = newUser();
            StockEventAnalysis analysis = newAnalysis("7.4");
            CountDownLatch analysisRead = new CountDownLatch(1);
            AtomicLong eventSavedBeforeCommitAt = new AtomicLong();
            AtomicLong analyzedUpdatedAt = new AtomicLong();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // T1: 이벤트 생성 — 분석을 읽은 뒤 잠시 멈췄다가 저장 · 커밋
                Future<Long> creator = pool.submit(() -> {
                    return tx.execute(s -> {
                        StockEventAnalysis found = analysisRepository.findOrCreatePending(analysis.getTicker(),
                                analysis.getStockName(), DATE, analysis.getChangeRate());
                        analysisRead.countDown();
                        sleep(500);
                        Long id = eventRepository.save(live(userRepository.getReferenceById(user.getId()), found)).getId();
                        // 커밋 직전 시각 — 공유 락은 이 뒤의 커밋에서 풀린다
                        eventSavedBeforeCommitAt.set(System.nanoTime());
                        return id;
                    });
                });
                // T2: 요약 완료(AI-5 흉내) — ANALYZED로 바꾼 뒤 같은 트랜잭션에서 일괄 갱신
                Future<?> summarizer = pool.submit(() -> {
                    await(analysisRead);
                    tx.executeWithoutResult(s -> {
                        jdbc.update("update stock_event_analyses set analysis_status = 'ANALYZED', ai_summary = ? where id = ?",
                                "테스트 전용 요약", analysis.getId());
                        analyzedUpdatedAt.set(System.nanoTime());
                        eventRepository.markSummaryProvided(analysis.getId(), NOW);
                    });
                });
                Long eventId = creator.get(30, TimeUnit.SECONDS);
                summarizer.get(30, TimeUnit.SECONDS);

                assertThat(provided(eventId)).isTrue();
                // 공유 락 때문에 ANALYZED UPDATE는 이벤트 트랜잭션이 커밋 직전 지점을 지난 뒤에야 끝난다
                // (락이 없으면 T1이 500ms 기다리는 동안 끝나 이 시각보다 앞선다)
                assertThat(analyzedUpdatedAt.get()).isGreaterThanOrEqualTo(eventSavedBeforeCommitAt.get());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private boolean provided(Long eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select summary_provided from user_market_events where id = ?",
                Boolean.class, eventId));
    }

    private LocalDateTime updatedAt(Long eventId) {
        return jdbc.queryForObject("select updated_at from user_market_events where id = ?", Timestamp.class, eventId)
                .toLocalDateTime().withNano(0);
    }

    // 테스트 전용 — 요약 서비스(AI-5)가 아직 없어 상태 · 요약을 직접 넣는다
    private void markAnalyzed(Long analysisId) {
        jdbc.update("update stock_event_analyses set analysis_status = 'ANALYZED', ai_summary = ? where id = ?",
                "테스트 전용 요약", analysisId);
    }

    private static UserMarketEvent backfilled(User user, StockEventAnalysis analysis) {
        return UserMarketEvent.create(user, analysis, Direction.of(analysis.getChangeRate()), new BigDecimal("5"),
                null, null, null, true);
    }

    // 실시간 감지 이벤트 (테스트 전용 더미 금액)
    private static UserMarketEvent live(User user, StockEventAnalysis analysis) {
        return UserMarketEvent.create(user, analysis, Direction.of(analysis.getChangeRate()), new BigDecimal("5"),
                new BigDecimal("-120000.50"), new BigDecimal("12.34"), new BigDecimal("3.2100"), false);
    }

    private StockEventAnalysis newAnalysis(String changeRate) {
        String ticker = "Q8" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return analysisRepository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", DATE, new BigDecimal(changeRate)));
    }

    // 테스트 전용 더미 계정 (실제 이메일 아님)
    private User newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("분석 읽기 신호를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
