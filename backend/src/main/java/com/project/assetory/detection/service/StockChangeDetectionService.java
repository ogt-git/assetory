package com.project.assetory.detection.service;

import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.detection.domain.DetectionEvent;
import com.project.assetory.detection.domain.DetectionEventKey;
import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.StockEventCalculator;
import com.project.assetory.detection.domain.StockEventCalculator.DetailRow;
import com.project.assetory.detection.domain.StockEventCalculator.HoldingKey;
import com.project.assetory.detection.domain.StockEventCalculator.Input;
import com.project.assetory.detection.domain.StockEventCalculator.PriceRow;
import com.project.assetory.detection.domain.StockEventCalculator.StockEvent;
import com.project.assetory.detection.domain.StockDetectionSetting;
import com.project.assetory.detection.repository.DetectionEventRepository;
import com.project.assetory.detection.repository.DetectionRuleRepository;
import com.project.assetory.detection.repository.UserDetectionSettingRepository;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 종목 급등락 감지 — 사용자 1명 × 날짜 1개 (FR-DT-03 · 06 · 07, 05 §11.3, A4).
 * 일일 배치가 스냅샷 저장 뒤 그날 COMPLETE인 사용자마다 부른다(D2 — 여기서도 COMPLETE가 아니면 아무것도 하지 않음).
 *
 * 순서: 규칙 · 내 설정(수신 끔이면 끝) → 그날 기록의 종목 행 · 그날 시세 → StockEventCalculator로 대상 · 숫자 계산
 *   → 종목마다 공유 분석(없으면 PENDING 업서트) · 사용자 이벤트(없으면 생성) · 감지 기록(키가 없으면 생성) → has_event
 * 멱등: 세 테이블 모두 "이미 있으면 재사용". 다시 실행해도 행이 늘지 않고, 임계치가 낮아졌으면 새로 걸린 종목만 더한다(삭제 없음).
 *
 * 트랜잭션: REQUIRES_NEW — 호출자에 트랜잭션이 있어도 그와 분리된다. 예외가 나면 이 사용자의 감지 전체(has_event 포함)만
 * 롤백되고 호출자(배치)는 감지 오류로 세고 다음 사용자로 넘어간다. 스냅샷은 이미 따로 커밋돼 있어 영향이 없다.
 * 연동 직후 소급(A5)과 같은 사용자 이벤트를 동시에 만들면 UNIQUE 위반으로 이 경로를 탄다 — 재실행으로 복구된다.
 *
 * 알림 · notify_status 변경 · 일 상한 · 묶음은 12주차, 근거 수집 · 상태 전환은 B1 · 11주차.
 * 한계(10주차 D1): 16:00 잠정 변동률로 감지한다. 다음 날 07:30 보정은 이벤트 · change_amount를 고치지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StockChangeDetectionService {

    private final DetectionRuleRepository detectionRuleRepository;
    private final UserDetectionSettingRepository userDetectionSettingRepository;
    private final AssetSnapshotRepository assetSnapshotRepository;
    private final AssetDetailSnapshotRepository assetDetailSnapshotRepository;
    private final StockPriceRepository stockPriceRepository;
    private final HoldingRepository holdingRepository;
    private final StockEventAnalysisRepository stockEventAnalysisRepository;
    private final UserMarketEventRepository userMarketEventRepository;
    private final DetectionEventRepository detectionEventRepository;
    private final UserRepository userRepository;

    /**
     * @param date       배치 기준일(snapshotDate) — 이벤트 날짜
     * @param detectedAt 배치 기준 시각(T) — detection_events.detected_at
     * @return 이번에 새로 만든 사용자 이벤트 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int detect(Long userId, LocalDate date, LocalDateTime detectedAt) {
        Optional<DetectionRule> ruleFound = detectionRuleRepository.findByRuleCode(DetectionRule.STOCK_PRICE_CHANGE)
                .filter(DetectionRule::isActive);
        if (ruleFound.isEmpty()) {
            return 0;
        }
        DetectionRule rule = ruleFound.get();
        // 수신 끔 · 임계치 없음이면 감지 자체를 하지 않는다 (A4 결정 E-4) — 소급(A5)과 같은 규칙
        Optional<StockDetectionSetting> resolved = StockDetectionSetting.resolve(rule,
                userDetectionSettingRepository.findByUserIdAndDetectionRuleId(userId, rule.getId()));
        if (resolved.isEmpty()) {
            return 0;
        }
        BigDecimal threshold = resolved.get().threshold();
        Set<String> excluded = resolved.get().excludedTickers();

        AssetSnapshot snapshot = assetSnapshotRepository.findByUserIdAndSnapshotDate(userId, date).orElse(null);
        if (snapshot == null || snapshot.getStatus() != SnapshotStatus.COMPLETE) {
            return 0;   // COMPLETE 기록만 감지 (10주차 D2, AGENTS 5-C)
        }

        List<DetailRow> rows = assetDetailSnapshotRepository.findAllByAssetUserIdAndSnapshotDate(userId, date).stream()
                .map(StockChangeDetectionService::toRow)
                .toList();
        Set<String> tickers = rows.stream().map(DetailRow::ticker).filter(t -> t != null && !excluded.contains(t))
                .collect(Collectors.toSet());
        if (tickers.isEmpty()) {
            return 0;
        }
        Map<String, PriceRow> prices = new HashMap<>();
        for (StockPrice p : stockPriceRepository.findAllByTickerInAndTradeDate(tickers, date)) {
            prices.put(p.getTicker(), new PriceRow(p.getTicker(), p.getStockName(), p.getClosePrice(),
                    p.getPrevClosePrice(), p.getChangeRate()));
        }
        // 대부분의 날은 임계치를 넘는 종목이 없다 — 그때는 holdings를 읽지 않는다
        boolean anyMove = prices.values().stream().anyMatch(p -> StockEventCalculator.exceeds(p.changeRate(), threshold));
        if (!anyMove) {
            return 0;
        }
        Map<HoldingKey, BigDecimal> avgBuyPrices = new HashMap<>();
        for (Holding h : holdingRepository.findAllByAssetUserId(userId)) {
            if (h.getAvgBuyPrice() != null) {
                avgBuyPrices.put(new HoldingKey(h.getAsset().getId(), h.getTicker()), h.getAvgBuyPrice());
            }
        }

        List<StockEvent> events = StockEventCalculator.detect(
                new Input(rows, prices, avgBuyPrices, snapshot.getTotalAsset(), threshold, excluded));
        if (events.isEmpty()) {
            return 0;
        }

        User user = userRepository.getReferenceById(userId);
        int created = 0;
        for (StockEvent event : events) {
            StockEventAnalysis analysis = stockEventAnalysisRepository.findOrCreatePending(
                    event.ticker(), event.stockName(), date, event.changeRate());
            Optional<UserMarketEvent> existing = userMarketEventRepository.findByUserIdAndAnalysisId(userId, analysis.getId());
            UserMarketEvent userEvent;
            if (existing.isPresent()) {
                userEvent = existing.get();
            } else {
                userEvent = userMarketEventRepository.save(UserMarketEvent.create(user, analysis, event.direction(), threshold,
                        event.changeAmount(), event.positionRatio(), event.profitLossRate(), false));
                created++;
            }
            String key = DetectionEventKey.stock(event.ticker(), date);
            if (!detectionEventRepository.existsByUserIdAndEventKey(userId, key)) {
                detectionEventRepository.save(DetectionEvent.create(user, rule, userEvent, key, rule.getSeverity(),
                        threshold, payload(event, date), detectedAt));
            }
        }
        snapshot.markEvent();
        return created;
    }

    // 급등락 payload (A3 결정 3) — 알림 문구가 다른 테이블을 다시 읽지 않게 근거 수치를 담는다
    private static Map<String, Object> payload(StockEvent event, LocalDate date) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticker", event.ticker());
        payload.put("stockName", event.stockName());
        payload.put("changeRate", event.changeRate());
        payload.put("direction", event.direction().name());
        payload.put("eventDate", date.toString());
        return payload;
    }

    // 자산 id는 LAZY 프록시에서 초기화 없이 읽는다
    private static DetailRow toRow(AssetDetailSnapshot d) {
        return new DetailRow(d.getAsset().getId(), d.getTicker(), d.getQuantity(), d.getPrice(), d.getAmount());
    }
}
