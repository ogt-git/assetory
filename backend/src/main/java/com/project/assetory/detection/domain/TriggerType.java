package com.project.assetory.detection.domain;

// docs/05 §8.1 detection_rules.trigger_type
public enum TriggerType {
    STOCK_CHANGE,
    ASSET_CHANGE,
    DEPOSIT_MATURITY,
    GOAL_PROGRESS,
    BRIEFING,
    MONTHLY_REPORT
}
