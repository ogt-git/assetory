package com.project.assetory.detection.service;

import com.project.assetory.analysis.domain.Direction;
import com.project.assetory.analysis.domain.StockEventAnalysis;
import com.project.assetory.analysis.domain.UserMarketEvent;
import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.repository.UserMarketEventRepository;
import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.detection.config.DetectionProperties;
import com.project.assetory.detection.domain.DetectionRule;
import com.project.assetory.detection.domain.StockDetectionSetting;
import com.project.assetory.detection.domain.StockEventCalculator;
import com.project.assetory.detection.repository.DetectionRuleRepository;
import com.project.assetory.detection.repository.UserDetectionSettingRepository;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import com.project.assetory.user.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 과거 이벤트 소급 — 연동 계좌 1개 (FR-DT-08, 05 §11.1, A5). 최초 연동 리스너가 시세 소급 직후 부른다.
 *
 * 그 계좌 보유 종목마다 stock_prices의 오늘 이하 최신 N행(assetory.detection.backfill-trading-days)에서
 * |change_rate| ≥ 적용 임계치인 날마다 공유 분석(PENDING 업서트)과 사용자 이벤트(is_backfilled=true)를 만든다.
 * - 규칙은 실시간 감지(A4)와 같다: 수신 끔이면 소급하지 않음, 내 임계치 · 제외 종목 적용 (StockDetectionSetting)
 * - 금액 3개(change_amount · position_ratio · profit_loss_rate)는 NULL — 과거 보유수량을 알 수 없다 (05 §5.3, 0 채움 금지)
 * - 알림 없음(notify_status NONE), 감지 기록(detection_events) 없음 (10주차 D3), asset_snapshots(has_event) 건드리지 않음
 * - 이미 같은 (사용자, 분석) 이벤트가 있으면 건너뛴다(실시간 이벤트 포함, 값 불변)
 * - 계좌마다 실행 — 두 번째 계좌도 그 종목을 소급한다 (A5 결정 H-2)
 *
 * backfill_completed: 소급이 예외 없이 끝났고 시세 소급 실패 종목이 0개(pricesComplete)일 때만 켠다. 끄지 않는다 (H-5).
 * 트랜잭션: REQUIRES_NEW 한 개 — 이벤트와 플래그가 함께 커밋 · 롤백된다. 16:00 배치와 같은 사용자 이벤트를 동시에 만들면
 * UNIQUE 위반으로 이 계좌 소급 전체가 롤백되고 플래그는 그대로다 (H-6). 재시도 수단은 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockEventBackfillService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final DetectionRuleRepository detectionRuleRepository;
    private final UserDetectionSettingRepository userDetectionSettingRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockEventAnalysisRepository stockEventAnalysisRepository;
    private final UserMarketEventRepository userMarketEventRepository;
    private final UserRepository userRepository;
    private final UserSettingsService userSettingsService;
    private final DetectionProperties detectionProperties;
    private final Clock clock;

    /**
     * @param assetId        증권 계좌 자산(assets.id, SECURITIES)
     * @param pricesComplete 시세 소급에서 실패한 종목이 없었는지 — true일 때만 backfill_completed를 켠다
     * @return 이번에 새로 만든 소급 이벤트 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int backfill(Long assetId, boolean pricesComplete) {
        Optional<Asset> asset = assetRepository.findById(assetId);
        if (asset.isEmpty()) {
            return 0;
        }
        Long userId = asset.get().getUser().getId();
        int created = createEvents(userId, assetId);
        if (pricesComplete) {
            userSettingsService.getOrCreate(userId).markBackfillCompleted();
        }
        return created;
    }

    private int createEvents(Long userId, Long assetId) {
        Optional<DetectionRule> rule = detectionRuleRepository.findByRuleCode(DetectionRule.STOCK_PRICE_CHANGE)
                .filter(DetectionRule::isActive);
        if (rule.isEmpty()) {
            return 0;
        }
        // 수신 끔 · 임계치 없음이면 소급하지 않는다 — 실시간 감지와 같은 규칙 (A5 결정 H-4)
        Optional<StockDetectionSetting> setting = StockDetectionSetting.resolve(rule.get(),
                userDetectionSettingRepository.findByUserIdAndDetectionRuleId(userId, rule.get().getId()));
        if (setting.isEmpty()) {
            return 0;
        }

        Set<String> tickers = new LinkedHashSet<>();
        for (Holding h : holdingRepository.findAllByAssetId(assetId)) {
            if (!setting.get().excludedTickers().contains(h.getTicker())) {
                tickers.add(h.getTicker());
            }
        }
        LocalDate today = LocalDate.now(clock);
        Limit range = Limit.of(detectionProperties.backfillTradingDays());
        User user = userRepository.getReferenceById(userId);
        int created = 0;
        for (String ticker : tickers) {
            for (StockPrice price : stockPriceRepository.findByTickerAndTradeDateLessThanEqualOrderByTradeDateDesc(ticker, today, range)) {
                if (!StockEventCalculator.exceeds(price.getChangeRate(), setting.get().threshold())) {
                    continue;
                }
                StockEventAnalysis analysis = stockEventAnalysisRepository.findOrCreatePending(
                        ticker, price.getStockName(), price.getTradeDate(), price.getChangeRate());
                if (userMarketEventRepository.findByUserIdAndAnalysisId(userId, analysis.getId()).isPresent()) {
                    continue;   // 이미 있는 이벤트(실시간 포함)는 그대로
                }
                userMarketEventRepository.save(UserMarketEvent.create(user, analysis, Direction.of(price.getChangeRate()),
                        setting.get().threshold(), null, null, null, true));
                created++;
            }
        }
        return created;
    }
}
