package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.PastEventRow;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANL-10 지난 이벤트 (FR-CA-13, Q-9 D4 · D6) — 같은 사용자 · 같은 종목 · 지금 보는 날짜보다 앞선 이벤트, 최신순 최대 N건, 소급 포함.
 * 테스트 전용 — 계정 · 종목 · 금액은 더미 값
 */
class PastEventsQueryIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate CURRENT = LocalDate.of(2026, 11, 16);

    @Autowired UserMarketEventRepository eventRepository;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("이전 이벤트 7건이면 최신순 5건 — 날짜 · 방향 · 변동률")
    void latestFive() {
        User user = newUser();
        String ticker = newTicker();
        for (int i = 1; i <= 7; i++) {
            save(user, ticker, CURRENT.minusDays(i * 3L), i % 2 == 0 ? "6.5" : "-5.5", false);
        }

        List<PastEventRow> rows = past(user, ticker, 5);

        assertThat(rows).extracting(PastEventRow::eventDate).containsExactly(CURRENT.minusDays(3), CURRENT.minusDays(6),
                CURRENT.minusDays(9), CURRENT.minusDays(12), CURRENT.minusDays(15));
        assertThat(rows.get(0).direction()).isEqualTo(Direction.DOWN);
        assertThat(rows.get(0).changeRate()).isEqualByComparingTo("-5.5");
        assertThat(rows.get(1).direction()).isEqualTo(Direction.UP);
        assertThat(rows.get(1).changeRate()).isEqualByComparingTo("6.5");
    }

    @Test
    @DisplayName("지금 보는 날짜 · 그 이후 날짜는 빠진다")
    void excludesCurrentAndLater() {
        User user = newUser();
        String ticker = newTicker();
        save(user, ticker, CURRENT, "-7.0", false);
        save(user, ticker, CURRENT.plusDays(2), "6.0", false);
        save(user, ticker, CURRENT.minusDays(1), "5.1", false);

        assertThat(past(user, ticker, 5)).extracting(PastEventRow::eventDate).containsExactly(CURRENT.minusDays(1));
    }

    @Test
    @DisplayName("다른 사용자 · 다른 종목의 이벤트는 빠진다")
    void onlyMineAndSameTicker() {
        User me = newUser();
        User other = newUser();
        String ticker = newTicker();
        String otherTicker = newTicker();
        save(me, ticker, CURRENT.minusDays(2), "-6.0", false);
        save(other, ticker, CURRENT.minusDays(4), "6.0", false);
        save(me, otherTicker, CURRENT.minusDays(5), "7.0", false);

        assertThat(past(me, ticker, 5)).extracting(PastEventRow::eventDate).containsExactly(CURRENT.minusDays(2));
    }

    @Test
    @DisplayName("소급 이벤트도 포함한다 (D4)")
    void includesBackfilled() {
        User user = newUser();
        String ticker = newTicker();
        save(user, ticker, CURRENT.minusDays(10), "-8.0", true);
        save(user, ticker, CURRENT.minusDays(1), "5.5", false);

        assertThat(past(user, ticker, 5)).extracting(PastEventRow::eventDate)
                .containsExactly(CURRENT.minusDays(1), CURRENT.minusDays(10));
    }

    @Test
    @DisplayName("이전 이벤트가 없으면 빈 목록")
    void empty() {
        User user = newUser();
        String ticker = newTicker();
        save(user, ticker, CURRENT, "-6.0", false);

        assertThat(past(user, ticker, 5)).isEmpty();
    }

    private List<PastEventRow> past(User user, String ticker, int max) {
        return eventRepository.findPastEvents(user.getId(), ticker, CURRENT, Limit.of(max));
    }

    // 분석 행은 (종목, 날짜)마다 하나 — 같은 분석을 다른 사용자가 공유할 수 있게 있으면 재사용
    private void save(User user, String ticker, LocalDate date, String changeRate, boolean backfilled) {
        StockEventAnalysis analysis = analysisRepository.findByTickerAndEventDate(ticker, date).orElseGet(() ->
                analysisRepository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", date, new BigDecimal(changeRate))));
        BigDecimal amount = backfilled ? null : new BigDecimal("-100000.00");
        eventRepository.saveAndFlush(UserMarketEvent.create(user, analysis, Direction.of(analysis.getChangeRate()),
                new BigDecimal("5"), amount, backfilled ? null : new BigDecimal("10.00"),
                backfilled ? null : new BigDecimal("1.0000"), backfilled));
    }

    private static String newTicker() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }

    // 테스트 전용 더미 계정 (실제 이메일 아님)
    private User newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
    }
}
