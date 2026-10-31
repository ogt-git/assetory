package com.project.assetory.snapshot.service;

import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.snapshot.domain.AssetDetailSnapshot;
import com.project.assetory.snapshot.domain.AssetSnapshot;
import com.project.assetory.snapshot.domain.DayChange;
import com.project.assetory.snapshot.domain.MarketChange;
import com.project.assetory.snapshot.domain.SnapshotInput;
import com.project.assetory.snapshot.domain.SnapshotJudge;
import com.project.assetory.snapshot.domain.SnapshotJudgement;
import com.project.assetory.snapshot.domain.SnapshotJudgement.Detail;
import com.project.assetory.snapshot.domain.SnapshotStatus;
import com.project.assetory.snapshot.repository.AssetDetailSnapshotRepository;
import com.project.assetory.snapshot.repository.AssetSnapshotRepository;
import com.project.assetory.user.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 일별 스냅샷 저장 (FR-AS-02, 05 §4.7 · §4.8 · §11.2) — 정기 배치 한 실행의 수집 결과로 판정해 저장한다.
 * 수동 새로고침 경로에서는 호출하지 않는다(스냅샷은 정기 배치에서만).
 * - asset_snapshots: UNIQUE (user_id, snapshot_date) upsert. COMPLETE면 직전 COMPLETE 대비 변화 · 시장가격 영향 · 기타 자산 변화를 미리 계산해 둔다
 * - asset_detail_snapshots: 성공한 자산 · 종목만. ticker NULL 행의 중복은 이 조회 후 갱신으로 막는다(MySQL UNIQUE는 NULL을 막지 못함)
 *   변화액 기준은 총자산과 같은 직전 COMPLETE 날짜 — 사이의 PARTIAL detail 행과 비교하지 않는다(AGENTS 5-C)
 * - 같은 날 다시 저장하면: 기존 COMPLETE는 더 나쁜 결과(PARTIAL · FAILED)로 덮어쓰지 않는다. 그 외(같은 등급 · 더 좋은 결과)는 덮어쓰고,
 *   이번에 실패한 자산의 기존 detail 행은 지운다(실패 자산 행 없음)
 */
@Slf4j
@Service
public class SnapshotSaveService {

    private final AssetSnapshotRepository snapshotRepository;
    private final AssetDetailSnapshotRepository detailRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public SnapshotSaveService(AssetSnapshotRepository snapshotRepository, AssetDetailSnapshotRepository detailRepository,
                               UserRepository userRepository, AssetRepository assetRepository,
                               TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.snapshotRepository = snapshotRepository;
        this.detailRepository = detailRepository;
        this.userRepository = userRepository;
        this.assetRepository = assetRepository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return 저장한 스냅샷. 판정 대상 자산이 하나도 없으면 empty — 행을 만들지 않는다(COMPLETE 공집합 금지)
     */
    public Optional<AssetSnapshot> save(Long userId, SnapshotInput input) {
        Optional<SnapshotJudgement> judged = SnapshotJudge.judge(input);
        if (judged.isEmpty()) {
            log.info("[Snapshot] 기록할 자산 없음 userId={} date={}", userId, input.snapshotDate());
            return Optional.empty();
        }
        SnapshotJudgement judgement = judged.get();
        LocalDate date = input.snapshotDate();
        boolean[] kept = {false};

        AssetSnapshot saved = transactionTemplate.execute(status -> {
            Optional<AssetSnapshot> previousComplete = snapshotRepository
                    .findFirstByUserIdAndStatusAndSnapshotDateBeforeOrderBySnapshotDateDesc(userId, SnapshotStatus.COMPLETE, date);
            DayChange dayChange = SnapshotJudge.dayChange(judgement,
                    previousComplete.map(AssetSnapshot::getSnapshotDate).orElse(null),
                    previousComplete.map(AssetSnapshot::getTotalAsset).orElse(null));

            Optional<AssetSnapshot> existing = snapshotRepository.findByUserIdAndSnapshotDate(userId, date);
            if (existing.isPresent() && isDowngrade(existing.get().getStatus(), judgement.status())) {
                log.info("[Snapshot] 기존 COMPLETE 유지 — 더 나쁜 결과로 덮어쓰지 않음 userId={} date={} new={}",
                        userId, date, judgement.status());
                kept[0] = true;
                return existing.get();
            }
            // 기준일(직전 COMPLETE) detail — 같은 사용자 행만 한 번에 조회해 시장가격 영향 · detail 변화액에 같이 쓴다
            LocalDate baseDate = previousComplete.map(AssetSnapshot::getSnapshotDate).orElse(null);
            Map<DetailKey, Detail> baseDetails = baseDetails(userId, baseDate);
            MarketChange marketChange = SnapshotJudge.marketChange(
                    List.copyOf(baseDetails.values()), judgement.details(), dayChange);

            AssetSnapshot snapshot = existing
                    .orElseGet(() -> AssetSnapshot.create(userRepository.getReferenceById(userId), date));
            snapshot.apply(judgement, input.collectedAt(), dayChange, marketChange);
            snapshotRepository.save(snapshot);

            saveDetails(userId, date, baseDetails, judgement.details());
            return snapshot;
        });

        if (!kept[0]) {
            // 실제로 저장한 상태만 센다 (기존 COMPLETE를 유지한 재실행은 세지 않음)
            Counter.builder("snapshot.status").tag("status", judgement.status().name()).register(meterRegistry).increment();
            log.info("[Snapshot] 저장 userId={} date={} status={}", userId, date, judgement.status());
        }
        return Optional.ofNullable(saved);
    }

    // 기존 COMPLETE를 PARTIAL · FAILED로 바꾸는 경우만 막는다 — 같은 등급 · 더 좋은 결과는 덮어쓴다
    private static boolean isDowngrade(SnapshotStatus existing, SnapshotStatus incoming) {
        return existing == SnapshotStatus.COMPLETE && incoming != SnapshotStatus.COMPLETE;
    }

    // 기준일 detail 행 (asset_id, ticker) → 값. 직전 COMPLETE가 없으면 빈 맵
    private Map<DetailKey, Detail> baseDetails(Long userId, LocalDate baseDate) {
        Map<DetailKey, Detail> base = new HashMap<>();
        if (baseDate == null) {
            return base;
        }
        for (AssetDetailSnapshot row : detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, baseDate)) {
            Long assetId = row.getAsset().getId();
            base.put(new DetailKey(assetId, row.getTicker()),
                    new Detail(assetId, row.getTicker(), row.getAmount(), row.getQuantity(), row.getPrice()));
        }
        return base;
    }

    private void saveDetails(Long userId, LocalDate date, Map<DetailKey, Detail> baseDetails, List<Detail> details) {
        Map<DetailKey, AssetDetailSnapshot> existing = new HashMap<>();
        for (AssetDetailSnapshot row : detailRepository.findAllByAssetUserIdAndSnapshotDate(userId, date)) {
            existing.put(new DetailKey(row.getAsset().getId(), row.getTicker()), row);
        }

        Set<DetailKey> current = new HashSet<>();
        for (Detail detail : details) {
            DetailKey key = new DetailKey(detail.assetId(), detail.ticker());
            current.add(key);
            AssetDetailSnapshot row = existing.get(key);
            if (row == null) {
                row = AssetDetailSnapshot.create(assetRepository.getReferenceById(detail.assetId()), detail.ticker(), date);
            }
            // 같은 (asset_id, ticker)의 기준일 행 대비 — 직전 COMPLETE가 없거나 그날 행이 없으면 NULL
            Detail base = baseDetails.get(key);
            row.apply(detail.amount(), detail.quantity(), detail.price(),
                    base == null ? null : detail.amount().subtract(base.amount()));
            detailRepository.save(row);
        }

        // 재저장 — 이번 실행에서 실패한 자산 · 종목의 기존 행을 남기면 "실패 자산은 행 없음"과 어긋난다
        existing.entrySet().stream()
                .filter(entry -> !current.contains(entry.getKey()))
                .forEach(entry -> detailRepository.delete(entry.getValue()));
    }

    private record DetailKey(Long assetId, String ticker) {
        DetailKey {
            Objects.requireNonNull(assetId);
        }
    }
}
