package com.project.assetory.asset.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetClass;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.DepositDetail;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.dto.AssetOverviewResponse;
import com.project.assetory.asset.dto.AssetOverviewResponse.HoldingItem;
import com.project.assetory.asset.dto.AssetOverviewResponse.SecuritiesAccount;
import com.project.assetory.asset.dto.AssetOverviewResponse.Totals;
import com.project.assetory.asset.dto.ManualAssetResponse;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.DepositDetailRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 통합 자산 조회 (FR-AS-01) — 로그인한 사용자의 현재 자산 (저장된 값 조회만, 외부 호출 없음).
 * 대상: 활성(is_active) 자산 중 해지되지 않은 연동의 증권 계좌 + 수기 자산.
 * 합계: SECURITIES = 종목 평가금액 합, CASH = 증권 예수금 + 수기 CASH, DEPOSIT = 수기 DEPOSIT·SAVINGS.
 * 아직 수집되지 않은 값(null)은 0으로 채우지 않고 합계에서 빼며 incomplete로 표시한다.
 */
@Service
@RequiredArgsConstructor
public class AssetQueryService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final DepositDetailRepository depositDetailRepository;

    @Transactional(readOnly = true)
    public AssetOverviewResponse getOverview(Long userId) {
        List<Asset> assets = assetRepository.findAllByUserIdAndActiveTrueOrderByIdAsc(userId).stream()
                .filter(AssetQueryService::isVisible)
                .toList();

        List<Asset> securities = assets.stream().filter(a -> !a.isManual() && a.getAssetType() == AssetType.SECURITIES).toList();
        List<Asset> manual = assets.stream().filter(Asset::isManual).toList();

        Map<Long, List<Holding>> holdingsByAsset = securities.isEmpty() ? Map.of()
                : holdingRepository.findAllByAssetIdInOrderByIdAsc(securities.stream().map(Asset::getId).toList())
                        .stream().collect(Collectors.groupingBy(h -> h.getAsset().getId()));
        Map<Long, DepositDetail> details = manual.isEmpty() ? Map.of()
                : depositDetailRepository.findAllById(manual.stream().map(Asset::getId).toList()).stream()
                        .collect(Collectors.toMap(DepositDetail::getAssetId, Function.identity()));

        Sum securitiesSum = new Sum();
        Sum depositSum = new Sum();
        Sum cashSum = new Sum();

        List<SecuritiesAccount> accounts = new ArrayList<>();
        for (Asset asset : securities) {
            ConnectedAccount connection = asset.getConnectedAccount();
            List<HoldingItem> items = holdingsByAsset.getOrDefault(asset.getId(), List.of()).stream()
                    .map(h -> {
                        securitiesSum.add(h.getEvalAmount());
                        return new HoldingItem(h.getTicker(), h.getStockName(), h.getQuantity(),
                                h.getEvalAmount(), h.getProfitLoss(), h.getProfitLossRate());
                    })
                    .toList();
            cashSum.add(asset.getBalance()); // 예수금
            accounts.add(new SecuritiesAccount(asset.getId(), connection.getId(), asset.getName(),
                    asset.getAccountNumberMasked(), connection.getStatus().name(), asset.getBalance(), items,
                    connection.getLastSyncedAt()));
        }

        List<ManualAssetResponse> manualAssets = new ArrayList<>();
        for (Asset asset : manual) {
            (asset.getAssetClass() == AssetClass.DEPOSIT ? depositSum : cashSum).add(asset.getBalance());
            manualAssets.add(ManualAssetResponse.from(asset, details.get(asset.getId())));
        }

        BigDecimal total = securitiesSum.value.add(depositSum.value).add(cashSum.value);
        boolean incomplete = securitiesSum.missing || depositSum.missing || cashSum.missing;
        return new AssetOverviewResponse(accounts, manualAssets,
                new Totals(securitiesSum.value, depositSum.value, cashSum.value, total, incomplete));
    }

    // 해지된 연동의 자산은 제외 (해지 시 is_active=false가 되지만 한 번 더 확인)
    private static boolean isVisible(Asset asset) {
        return asset.isManual() || asset.getConnectedAccount().getStatus() != ConnectedAccountStatus.DISCONNECTED;
    }

    private static final class Sum {
        private BigDecimal value = BigDecimal.ZERO;
        private boolean missing;

        void add(BigDecimal amount) {
            if (amount == null) {
                missing = true;
            } else {
                value = value.add(amount);
            }
        }
    }
}
