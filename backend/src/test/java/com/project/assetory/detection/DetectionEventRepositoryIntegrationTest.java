package com.project.assetory.detection;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.detection.domain.DetectionEvent;
import com.project.assetory.detection.domain.DetectionEventKey;
import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.Severity;
import com.project.assetory.detection.repository.DetectionEventRepository;
import com.project.assetory.detection.repository.DetectionRuleRepository;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// docs/05 §8.3 detection_events — 감지 기록(알림 파이프라인 입력). UNIQUE (user_id, event_key) = 감지 멱등성 (FR-DT-06)
class DetectionEventRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime DETECTED_AT = DATE.atTime(16, 5);

    @Autowired DetectionEventRepository eventRepository;
    @Autowired DetectionRuleRepository ruleRepository;
    @Autowired UserMarketEventRepository userMarketEventRepository;
    @Autowired StockEventAnalysisRepository analysisRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 (사용자, 키)를 두 번 저장하면 UNIQUE 위반, 다른 사용자는 같은 키로 저장할 수 있다")
    void duplicateUserKey_rejected() {
        User user = newUser();
        User other = newUser();
        String key = DetectionEventKey.stock(uniqueTicker(), DATE);
        eventRepository.saveAndFlush(event(user, null, key));

        assertThatThrownBy(() -> eventRepository.saveAndFlush(event(user, null, key)))
                .isInstanceOf(DataIntegrityViolationException.class);
        eventRepository.saveAndFlush(event(other, null, key));
    }

    @Test
    @DisplayName("payload JSON을 저장하고 다시 읽으면 같은 값")
    void payloadRoundTrip() {
        User user = newUser();
        String ticker = uniqueTicker();
        String key = DetectionEventKey.stock(ticker, DATE);
        eventRepository.saveAndFlush(event(user, null, key, payload(ticker)));

        DetectionEvent found = eventRepository.findByUserIdAndEventKey(user.getId(), key).orElseThrow();
        assertThat(found.getPayload()).containsEntry("ticker", ticker)
                .containsEntry("stockName", "테스트 종목")
                .containsEntry("direction", "DOWN")
                .containsEntry("eventDate", "2026-11-05");
        assertThat(new BigDecimal(found.getPayload().get("changeRate").toString())).isEqualByComparingTo("-6.1234");
        assertThat(found.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(found.getAppliedThreshold()).isEqualByComparingTo("5");
        assertThat(found.getDetectedAt()).isEqualTo(DETECTED_AT);
        assertThat(found.getEventKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("사용자 이벤트 연결이 NULL이어도 저장되고, 있으면 연결된다")
    void userMarketEventLinkOptional() {
        User user = newUser();
        String keyNoLink = DetectionEventKey.stock(uniqueTicker(), DATE);
        DetectionEvent noLink = eventRepository.saveAndFlush(event(user, null, keyNoLink));

        StockEventAnalysis analysis = analysisRepository.saveAndFlush(
                StockEventAnalysis.create(uniqueTicker(), "테스트 종목", DATE, new BigDecimal("-6.1234")));
        UserMarketEvent ume = userMarketEventRepository.saveAndFlush(UserMarketEvent.create(user, analysis, Direction.DOWN,
                new BigDecimal("5"), new BigDecimal("-10000"), new BigDecimal("1.00"), new BigDecimal("-2.0000"), false));
        String keyLinked = DetectionEventKey.stock(analysis.getTicker(), DATE);
        DetectionEvent linked = eventRepository.saveAndFlush(event(user, ume, keyLinked));

        assertThat(jdbc.queryForObject("select user_market_event_id from detection_events where id = ?",
                Long.class, noLink.getId())).isNull();
        assertThat(jdbc.queryForObject("select user_market_event_id from detection_events where id = ?",
                Long.class, linked.getId())).isEqualTo(ume.getId());
    }

    @Test
    @DisplayName("exists · find는 그 사용자의 키만 찾는다")
    void existsAndFind_ownerScoped() {
        User user = newUser();
        User other = newUser();
        String key = DetectionEventKey.stock(uniqueTicker(), DATE);
        DetectionEvent saved = eventRepository.saveAndFlush(event(user, null, key));

        assertThat(eventRepository.existsByUserIdAndEventKey(user.getId(), key)).isTrue();
        assertThat(eventRepository.existsByUserIdAndEventKey(other.getId(), key)).isFalse();
        assertThat(eventRepository.existsByUserIdAndEventKey(user.getId(), key + "X")).isFalse();
        assertThat(eventRepository.findByUserIdAndEventKey(user.getId(), key)).get()
                .extracting(DetectionEvent::getId).isEqualTo(saved.getId());
        assertThat(eventRepository.findByUserIdAndEventKey(other.getId(), key)).isEmpty();
    }

    @Test
    @DisplayName("UNIQUE (user_id, event_key) 이름 그대로, payload는 json 컬럼")
    void schema() {
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'detection_events' and index_name = ?
                order by seq_in_index""", String.class, "uk_detection_events_user_key");
        assertThat(columns).containsExactly("user_id", "event_key");
        assertThat(jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_schema = database() and table_name = 'detection_events' and column_name = 'payload'""",
                String.class)).isEqualTo("json");
    }

    private DetectionEvent event(User user, UserMarketEvent ume, String key) {
        return event(user, ume, key, payload("005930"));
    }

    private DetectionEvent event(User user, UserMarketEvent ume, String key, Map<String, Object> payload) {
        DetectionRule rule = ruleRepository.findByRuleCode("STOCK_PRICE_CHANGE").orElseThrow();
        return DetectionEvent.create(user, rule, ume, key, rule.getSeverity(), new BigDecimal("5"), payload, DETECTED_AT);
    }

    // 결정 3 형식 (테스트 전용 더미 값)
    private static Map<String, Object> payload(String ticker) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticker", ticker);
        payload.put("stockName", "테스트 종목");
        payload.put("changeRate", new BigDecimal("-6.1234"));
        payload.put("direction", "DOWN");
        payload.put("eventDate", "2026-11-05");
        return payload;
    }

    private static String uniqueTicker() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }

    // 테스트 전용 더미 계정 (실제 이메일 아님)
    private User newUser() {
        String email = uniqueEmail();
        return userRepository.save(User.create(email, HashUtil.sha256Base64(email), "dummy-hash", "테스터", "2026-10"));
    }
}
