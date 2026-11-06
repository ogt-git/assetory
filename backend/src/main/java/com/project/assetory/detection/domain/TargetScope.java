package com.project.assetory.detection.domain;

import java.util.List;

/** docs/05 §8.2 user_detection_settings.target_scope (JSON) — 감지 대상 범위. 지금은 제외 종목 목록만 */
public record TargetScope(List<String> excludedTickers) {
}
