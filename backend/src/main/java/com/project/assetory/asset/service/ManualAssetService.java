package com.project.assetory.asset.service;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.dto.ManualAssetRequest;
import com.project.assetory.asset.dto.ManualAssetResponse;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.global.exception.ErrorCode;
import com.project.assetory.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManualAssetService {

    private final AssetRepository assetRepository;
    private final UserRepository userRepository;

    @Transactional
    public ManualAssetResponse save(Long userId, ManualAssetRequest request) {
        Asset asset = Asset.manual(userRepository.getReferenceById(userId),
                request.assetType(), request.name(), request.balance());
        Asset saved = assetRepository.save(asset);

        log.debug("[ManualAsset] 저장 userId={} type={}", userId, request.assetType());
        return ManualAssetResponse.from(saved);
    }

    @Transactional
    public ManualAssetResponse update(Long userId, Long assetId, ManualAssetRequest request) {
        Asset asset = findOwnedAsset(userId, assetId);
        asset.updateManual(request.assetType(), request.name(), request.balance());

        log.debug("[ManualAsset] 수정 userId={} assetId={}", userId, assetId);
        return ManualAssetResponse.from(asset);
    }

    @Transactional
    public void delete(Long userId, Long assetId) {
        Asset asset = findOwnedAsset(userId, assetId);
        assetRepository.delete(asset);

        log.debug("[ManualAsset] 삭제 userId={} assetId={}", userId, assetId);
    }

    @Transactional(readOnly = true)
    public List<ManualAssetResponse> getAll(Long userId) {
        return assetRepository.findAllByUserIdAndConnectedAccountIsNull(userId)
                .stream()
                .map(ManualAssetResponse::from)
                .toList();
    }

    private Asset findOwnedAsset(Long userId, Long assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.MANUAL_ASSET_NOT_FOUND.getMessage()));
        if (!asset.getUser().getId().equals(userId)) {
            throw new AccessDeniedException(ErrorCode.ACCESS_DENIED.getMessage());
        }
        return asset;
    }
}
