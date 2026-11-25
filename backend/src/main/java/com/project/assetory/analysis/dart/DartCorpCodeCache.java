package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.exception.DartApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 종목코드 → DART 고유번호(corp_code) 메모리 캐시 — 테이블 없이 하루 1회 받는다 (결정 D4, B2 확정 사항 9).
 * - 기준 날짜(KST, 주입한 Clock)가 바뀌면 다시 받는다. 받는 동안 들어온 요청은 기다렸다가 같은 결과를 쓴다(동시에 한 번만)
 * - 재수신 실패: 이전 맵이 있으면 그것으로 답하고 WARN, 그날은 다시 받지 않는다(상장사 목록은 하루 사이 거의 바뀌지 않는다).
 *   맵이 아예 없으면 실패 — 0건으로 답하면 모든 종목이 "공시 없음"으로 굳는다. 다음 조회 때 다시 시도한다
 * - 맵에 없는 종목(비상장 · ETF 등)은 빈 결과 = 정상 0건
 */
@Slf4j
@Component
public class DartCorpCodeCache {

    private final DartApiClient client;
    private final Clock clock;
    private final Object fetchLock = new Object();

    /** 마지막으로 받은 맵과 그 날짜, 마지막 수신 시도 날짜 — 통째로 바꿔 끼워 읽는 쪽은 잠금 없이 본다 */
    private volatile Snapshot snapshot;

    private record Snapshot(Map<String, String> codes, LocalDate fetchedOn, LocalDate attemptedOn) {
    }

    public DartCorpCodeCache(DartApiClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public Optional<String> corpCodeOf(String ticker) {
        return Optional.ofNullable(codes().get(ticker));
    }

    private Map<String, String> codes() {
        LocalDate today = LocalDate.now(clock);
        Snapshot current = snapshot;
        if (current != null && today.equals(current.attemptedOn())) {
            return current.codes();
        }
        synchronized (fetchLock) {
            current = snapshot;
            if (current != null && today.equals(current.attemptedOn())) {
                return current.codes();
            }
            try {
                Map<String, String> fetched = client.corpCodes();
                snapshot = new Snapshot(fetched, today, today);
                log.info("[DART] 고유번호 수신 date={} listed={}", today, fetched.size());
                return fetched;
            } catch (DartApiException e) {
                if (current == null) {
                    throw e;
                }
                snapshot = new Snapshot(current.codes(), current.fetchedOn(), today);
                log.warn("[DART] 고유번호 재수신 실패 — 이전 맵 사용 fetchedOn={} type={}", current.fetchedOn(), e.getErrorType());
                return current.codes();
            }
        }
    }
}
