package com.project.assetory.market.ecos;

import com.project.assetory.market.domain.DatedValue;
import com.project.assetory.market.exception.EcosApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한국은행 ECOS StatisticSearch — 거시지표(FR-MK-03) 일별 값. 요청 경로는 개발 명세서 기준(실응답 미대조, B0 E1 · E2).
 *
 * 키 유출 방지 (AGENTS 9장, B2 DartApiClient와 같은 방식): ECOS는 키를 URL **경로**로 받는다.
 * - URI 템플릿의 경로 변수로만 요청을 만든다 — http.client.requests uri 태그 · 로그에 완성 URL이 남지 않게
 * - RestClientException(I/O 메시지에 요청 URL이 들어간다)은 cause로 붙이지 않고 유형만 담아 다시 던진다
 * - 로그에는 실패 유형 · 결과 코드 · HTTP 상태 · 소요 시간만
 * 같은 실행 안에서 재시도하지 않는다 — 실패한 지표는 다음 실행의 업서트 범위에서 다시 받는다
 */
@Slf4j
@Component
public class EcosApiClient {

    private static final String SEARCH_URI =
            "/StatisticSearch/{key}/json/kr/{startNo}/{endNo}/{statCode}/{cycle}/{from}/{to}/{itemCode}";
    /** 요청 건수 1~100 — 일 주기 45일 범위는 최대 46행이라 한 번에 받는다 */
    static final int MAX_ROWS = 100;
    private static final String DAILY = "D";
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final EcosProperties properties;
    private final EcosMetrics metrics;

    public EcosApiClient(@Qualifier("ecosRestClient") RestClient restClient, EcosProperties properties, EcosMetrics metrics) {
        this.restClient = restClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 일 주기 통계 1항목의 [from, to] 값 — 응답 순서 그대로(정렬 · 계산은 IndicatorSeries). INFO-200은 빈 목록 */
    public List<DatedValue> search(String statCode, String itemCode, LocalDate from, LocalDate to) {
        if (!properties.hasApiKey()) {
            log.error("[ECOS] 키 미설정 api={} — ECOS_API_KEY", EcosMetrics.API_STATISTIC_SEARCH);
            throw new EcosApiException(EcosErrorType.CONFIG_MISSING, null);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("key", properties.apiKey());
        vars.put("startNo", 1);
        vars.put("endNo", MAX_ROWS);
        vars.put("statCode", statCode);
        vars.put("cycle", DAILY);
        vars.put("from", from.format(BASIC));
        vars.put("to", to.format(BASIC));
        vars.put("itemCode", itemCode);
        long startNanos = System.nanoTime();
        ResponseEntity<String> response;
        try {
            response = restClient.get().uri(SEARCH_URI, vars).retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            metrics.record(false, startNanos);
            log.warn("[ECOS] 호출 실패 type={} error={} elapsedMs={}", EcosErrorType.NETWORK_ERROR, e.getClass().getSimpleName(),
                    elapsedMs(startNanos));
            // cause를 붙이지 않는다 — 메시지에 키가 든 요청 URL이 있다
            throw new EcosApiException(EcosErrorType.NETWORK_ERROR, null);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            metrics.record(false, startNanos);
            int status = response.getStatusCode().value();
            EcosErrorType type = status >= 500 ? EcosErrorType.SERVER_ERROR : EcosErrorType.UNKNOWN_CODE;
            log.warn("[ECOS] 실패 응답 type={} http={} elapsedMs={}", type, status, elapsedMs(startNanos));
            throw new EcosApiException(type, "HTTP-" + status);
        }
        try {
            List<DatedValue> values = EcosResponseParser.parse(response.getBody(), itemCode);
            metrics.record(true, startNanos);
            return values;
        } catch (EcosApiException e) {
            metrics.record(false, startNanos);
            if (e.getErrorType().isRetryable()) {
                log.warn("[ECOS] 실패 응답 type={} code={} elapsedMs={}", e.getErrorType(), e.getEcosCode(), elapsedMs(startNanos));
            } else {
                log.error("[ECOS] 실패 응답(재시도 안 함) type={} code={} elapsedMs={}", e.getErrorType(), e.getEcosCode(),
                        elapsedMs(startNanos));
            }
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
