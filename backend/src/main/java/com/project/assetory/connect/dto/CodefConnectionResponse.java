package com.project.assetory.connect.dto;

import com.project.assetory.connect.domain.ConnectedAccount;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class CodefConnectionResponse {

    private final Long id;
    private final String organizationCode;
    private final String businessType;
    private final LocalDateTime createdAt;

    public CodefConnectionResponse(ConnectedAccount account) {
        this.id = account.getId();
        this.organizationCode = account.getInstitution().getCodefOrgCode();
        this.businessType = account.getBusinessType().name();
        this.createdAt = account.getCreatedAt();
    }
}
