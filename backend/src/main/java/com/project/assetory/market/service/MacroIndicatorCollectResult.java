package com.project.assetory.market.service;

import com.project.assetory.market.domain.IndicatorCode;

import java.util.Map;

/**
 * 지수 · 지표 수집 1회 결과 — 로그 · 테스트용. 지표마다 따로 성공 · 실패한다(B4 확정 사항 6)
 */
public record MacroIndicatorCollectResult(Map<IndicatorCode, Outcome> outcomes) {

    /**
     * @param saved   저장(신규 + 갱신) 행 수. 실패면 0 — 그 지표의 저장은 전부 롤백됐다
     * @param failure 실패 유형(KIS 오류 유형 · 예외 클래스 이름). 성공이면 null. 메시지 원문은 담지 않는다
     */
    public record Outcome(boolean success, int saved, String failure) {
        static Outcome ok(int saved) {
            return new Outcome(true, saved, null);
        }

        static Outcome failed(String failure) {
            return new Outcome(false, 0, failure);
        }
    }

    public Outcome outcome(IndicatorCode code) {
        return outcomes.get(code);
    }
}
