package com.project.assetory.connect.dto;

import com.project.assetory.connect.domain.ConnectedAccount;
import lombok.Getter;

import java.time.LocalDateTime;

// connectedId 등 기관 인증 정보는 응답에 넣지 않는다
@Getter
public class CodefConnectionResponse {

    private final Long id;
    private final String organizationCode;
    private final String institutionName;
    private final String businessType;
    private final String status;
    private final LocalDateTime lastSyncedAt;
    private final LocalDateTime createdAt;

    public CodefConnectionResponse(ConnectedAccount account) {
        this.id = account.getId();
        this.organizationCode = account.getInstitution().getCodefOrgCode();
        this.institutionName = account.getInstitution().getName();
        this.businessType = account.getBusinessType().name();
        this.status = account.getStatus().name();
        this.lastSyncedAt = account.getLastSyncedAt();
        this.createdAt = account.getCreatedAt();
    }
}
