package com.project.assetory.asset.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.dto.StockAccountResponse;
import com.project.assetory.asset.dto.StockAccountResponse.HoldingResponse;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AssetQueryService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;

    @Transactional(readOnly = true)
    public List<StockAccountResponse> getStocks(Long userId) {
        List<Asset> accounts = assetRepository.findAllByUserIdAndAssetType(userId, AssetType.SECURITIES);

        return accounts.stream()
                .map(account -> {
                    List<HoldingResponse> holdings = holdingRepository.findAllByAssetId(account.getId())
                            .stream()
                            .map(h -> new HoldingResponse(h.getTicker(), h.getStockName(), h.getQuantity(),
                                    h.getEvalAmount(), h.getProfitLoss(), h.getProfitLossRate()))
                            .toList();

                    return new StockAccountResponse(
                            account.getName(),
                            account.getAccountNumberMasked(),
                            account.getBalance(),
                            holdings);
                })
                .toList();
    }
}
