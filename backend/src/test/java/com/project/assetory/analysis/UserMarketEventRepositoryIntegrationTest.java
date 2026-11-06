package com.project.assetory.analysis;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.NotifyStatus;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// docs/05 §5.3 user_market_events — 공유 분석에 내 숫자를 붙인 개인 이벤트. UNIQUE (user_id, analysis_id)
class UserMarketEventRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);

    @Autowired UserMarketEventRepository eventRepository;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 (사용자, 분석) 이벤트를 두 번 저장하면 UNIQUE 위반 — 다른 사용자는 같은 분석을 재사용할 수 있다")
    void duplicateUserAnalysis_rejected() {
        User user = newUser();
        User other = newUser();
        StockEventAnalysis analysis = newAnalysis(DATE, "-6.5");
        eventRepository.saveAndFlush(live(user, analysis));

        assertThatThrownBy(() -> eventRepository.saveAndFlush(live(user, analysis)))
                .isInstanceOf(DataIntegrityViolationException.class);
        eventRepository.saveAndFlush(live(other, analysis));
    }

    @Test
    @DisplayName("기본값: notify_status=NONE · summary_provided=false · is_backfilled=false, event_date는 분석 날짜에서 복사")
    void defaults() {
        User user = newUser();
        StockEventAnalysis analysis = newAnalysis(DATE, "6.2");
        UserMarketEvent saved = eventRepository.saveAndFlush(live(user, analysis));

        UserMarketEvent found = eventRepository.findByUserIdAndAnalysisId(user.getId(), analysis.getId()).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getNotifyStatus()).isEqualTo(NotifyStatus.NONE);
        assertThat(found.isSummaryProvided()).isFalse();
        assertThat(found.isBackfilled()).isFalse();
        assertThat(found.getEventDate()).isEqualTo(DATE);
        // 분석 변동률 +6.2 → UP
        assertThat(found.getDirection()).isEqualTo(Direction.UP);
        assertThat(found.getAppliedThreshold()).isEqualByComparingTo("5");
        assertThat(found.getChangeAmount()).isEqualByComparingTo("-120000.50");
        assertThat(found.getPositionRatio()).isEqualByComparingTo("12.34");
        assertThat(found.getProfitLossRate()).isEqualByComparingTo("3.2100");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("소급 이벤트는 금액 필드 3개가 NULL인 채 저장된다 (0으로 채우지 않음)")
    void backfilled_amountsNull() {
        User user = newUser();
        StockEventAnalysis analysis = newAnalysis(DATE.minusDays(10), "-8");
        eventRepository.saveAndFlush(UserMarketEvent.create(user, analysis, Direction.DOWN, new BigDecimal("5"),
                null, null, null, true));

        UserMarketEvent found = eventRepository.findByUserIdAndAnalysisId(user.getId(), analysis.getId()).orElseThrow();
        assertThat(found.isBackfilled()).isTrue();
        assertThat(found.getNotifyStatus()).isEqualTo(NotifyStatus.NONE);
        Map<String, Object> row = jdbc.queryForMap("""
                select change_amount, position_ratio, profit_loss_rate, is_backfilled
                from user_market_events where id = ?""", found.getId());
        assertThat(row).containsEntry("change_amount", null).containsEntry("position_ratio", null)
                .containsEntry("profit_loss_rate", null);
    }

    @Test
    @DisplayName("날짜별 조회는 그 사용자 · 그 날짜의 이벤트만 돌려준다")
    void findByUserAndDate_ownerOnly() {
        User user = newUser();
        User other = newUser();
        StockEventAnalysis a1 = newAnalysis(DATE, "-6");
        StockEventAnalysis a2 = newAnalysis(DATE, "7");
        StockEventAnalysis otherDay = newAnalysis(DATE.plusDays(1), "9");
        UserMarketEvent e1 = eventRepository.saveAndFlush(live(user, a1));
        UserMarketEvent e2 = eventRepository.saveAndFlush(live(user, a2));
        eventRepository.saveAndFlush(live(user, otherDay));
        eventRepository.saveAndFlush(live(other, a1));

        List<UserMarketEvent> found = eventRepository.findAllByUserIdAndEventDate(user.getId(), DATE);

        assertThat(found).extracting(UserMarketEvent::getId).containsExactlyInAnyOrder(e1.getId(), e2.getId());
        assertThat(eventRepository.findAllByUserIdAndEventDate(other.getId(), DATE)).hasSize(1);
        assertThat(eventRepository.findByUserIdAndAnalysisId(other.getId(), a2.getId())).isEmpty();
    }

    @Test
    @DisplayName("인덱스 (user_id, event_date)와 UNIQUE (user_id, analysis_id)가 이름 그대로 있다")
    void indexes() {
        assertThat(indexColumns("idx_user_market_events_user_date")).containsExactly("user_id", "event_date");
        assertThat(indexColumns("uk_user_market_events_user_analysis")).containsExactly("user_id", "analysis_id");
    }

    // 실시간 감지 이벤트 (테스트 전용 더미 금액)
    private static UserMarketEvent live(User user, StockEventAnalysis analysis) {
        return UserMarketEvent.create(user, analysis, Direction.of(analysis.getChangeRate()), new BigDecimal("5"),
                new BigDecimal("-120000.50"), new BigDecimal("12.34"), new BigDecimal("3.2100"), false);
    }

    private StockEventAnalysis newAnalysis(LocalDate date, String changeRate) {
        String ticker = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        return analysisRepository.saveAndFlush(StockEventAnalysis.create(ticker, "테스트 종목", date, new BigDecimal(changeRate)));
    }

    private List<String> indexColumns(String indexName) {
        return jdbc.queryForList("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'user_market_events' and index_name = ?
                order by seq_in_index""", String.class, indexName);
    }

    // 테스트 전용 더미 계정 (실제 이메일 아님)
    private User newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
    }
}
