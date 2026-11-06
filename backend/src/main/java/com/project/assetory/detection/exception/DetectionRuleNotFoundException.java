package com.project.assetory.detection.exception;

// 없는 ruleCode 또는 비활성 규칙 — 둘을 구분하지 않고 같은 응답 (404 DETECTION_RULE_NOT_FOUND)
public class DetectionRuleNotFoundException extends RuntimeException {

    public DetectionRuleNotFoundException() {
        super("알림 규칙 없음");
    }
}
