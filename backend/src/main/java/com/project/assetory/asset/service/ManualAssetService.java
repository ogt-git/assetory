package com.project.assetory.asset.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.DepositDetail;
import com.project.assetory.asset.dto.ManualAssetRequest;
import com.project.assetory.asset.dto.ManualAssetRequest.DepositDetailRequest;
import com.project.assetory.asset.dto.ManualAssetResponse;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.DepositDetailRepository;
import com.project.assetory.global.exception.ErrorCode;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 수기 자산 CRUD (FR-AS-07, D-7, D-19, D-23)
 * - 유형은 DEPOSIT / SAVINGS / CASH만. DEPOSIT·SAVINGS는 deposit_details 필수, assets와 한 트랜잭션으로 저장
 * - 수정·삭제는 수기 자산(connected_account_id NULL)만. 연동 자산은 거부
 * - 삭제는 soft delete(is_active=false) — 이후 자산별 일별 기록이 asset_id를 참조하므로 행을 지우지 않는다
 * - 다른 사용자·삭제된 자산은 존재 여부를 드러내지 않도록 404로 응답한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualAssetService {

    static final Set<AssetType> MANUAL_TYPES = Set.of(AssetType.DEPOSIT, AssetType.SAVINGS, AssetType.CASH);

    private final AssetRepository assetRepository;
    private final DepositDetailRepository depositDetailRepository;
    private final UserRepository userRepository;

    @Transactional
    public ManualAssetResponse save(Long userId, ManualAssetRequest request) {
        validate(request);
        Asset asset = assetRepository.save(Asset.manual(userRepository.getReferenceById(userId),
                request.assetType(), request.name(), request.balance()));
        DepositDetail detail = needsDetail(request.assetType())
                ? depositDetailRepository.save(newDetail(asset, request.depositDetail()))
                : null;

        log.debug("[ManualAsset] 저장 userId={} assetId={} type={}", userId, asset.getId(), request.assetType());
        return ManualAssetResponse.from(asset, detail);
    }

    @Transactional
    public ManualAssetResponse update(Long userId, Long assetId, ManualAssetRequest request) {
        Asset asset = findOwnedManualAsset(userId, assetId);
        validate(request);
        asset.updateManual(request.assetType(), request.name(), request.balance());

        // 유형이 바뀔 수 있으므로 상세 행을 맞춘다 (예적금 → 현금이면 삭제, 현금 → 예적금이면 생성)
        DepositDetail detail = depositDetailRepository.findById(assetId).orElse(null);
        if (needsDetail(request.assetType())) {
            DepositDetailRequest d = request.depositDetail();
            if (detail == null) {
                detail = depositDetailRepository.save(newDetail(asset, d));
            } else {
                detail.update(d.interestRate(), d.startDate(), d.maturityDate(), d.principal(), d.saveTerm(), d.autoRenew());
            }
        } else if (detail != null) {
            depositDetailRepository.delete(detail);
            detail = null;
        }

        log.debug("[ManualAsset] 수정 userId={} assetId={}", userId, assetId);
        return ManualAssetResponse.from(asset, detail);
    }

    @Transactional
    public void delete(Long userId, Long assetId) {
        Asset asset = findOwnedManualAsset(userId, assetId);
        asset.deactivate();

        log.debug("[ManualAsset] 삭제(비활성) userId={} assetId={}", userId, assetId);
    }

    @Transactional(readOnly = true)
    public List<ManualAssetResponse> getAll(Long userId) {
        List<Asset> assets = assetRepository.findAllByUserIdAndConnectedAccountIsNullAndActiveTrueOrderByIdAsc(userId);
        Map<Long, DepositDetail> details = depositDetailRepository
                .findAllById(assets.stream().map(Asset::getId).toList()).stream()
                .collect(Collectors.toMap(DepositDetail::getAssetId, Function.identity()));
        return assets.stream()
                .map(asset -> ManualAssetResponse.from(asset, details.get(asset.getId())))
                .toList();
    }

    // 소유자 검증 + 수기 자산만 + 삭제(비활성)된 자산 제외
    private Asset findOwnedManualAsset(Long userId, Long assetId) {
        Asset asset = assetRepository.findByIdAndUserId(assetId, userId)
                .filter(Asset::isActive)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.MANUAL_ASSET_NOT_FOUND.getMessage()));
        if (!asset.isManual()) {
            throw new IllegalStateException("연동 자산은 수정·삭제할 수 없습니다. 연동 해지를 이용해주세요.");
        }
        return asset;
    }

    private void validate(ManualAssetRequest request) {
        if (!MANUAL_TYPES.contains(request.assetType())) {
            throw new IllegalArgumentException("수기 자산 유형은 DEPOSIT, SAVINGS, CASH만 가능합니다.");
        }
        DepositDetailRequest detail = request.depositDetail();
        if (!needsDetail(request.assetType())) {
            if (detail != null) {
                throw new IllegalArgumentException("현금(CASH)은 예적금 상세 정보를 받지 않습니다.");
            }
            return;
        }
        if (detail == null) {
            throw new IllegalArgumentException("예금·적금은 금리·시작일·만기일·원금·기간·자동 재예치 정보가 필요합니다.");
        }
        if (!detail.maturityDate().isAfter(detail.startDate())) {
            throw new IllegalArgumentException("만기일은 시작일보다 뒤여야 합니다.");
        }
    }

    private static boolean needsDetail(AssetType type) {
        return type == AssetType.DEPOSIT || type == AssetType.SAVINGS;
    }

    private static DepositDetail newDetail(Asset asset, DepositDetailRequest d) {
        return DepositDetail.create(asset, d.interestRate(), d.startDate(), d.maturityDate(),
                d.principal(), d.saveTerm(), d.autoRenew());
    }
}
