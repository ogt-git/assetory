package com.project.assetory.connect.domain;

// docs/05 §4.2 (ACTIVE / AUTH_FAILED / EXPIRED) + DISCONNECTED(연동 해지, D-21 승인)
public enum ConnectedAccountStatus {
    ACTIVE,
    AUTH_FAILED,
    EXPIRED,
    DISCONNECTED
}
