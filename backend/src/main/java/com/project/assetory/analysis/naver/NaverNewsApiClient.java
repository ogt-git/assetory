package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.exception.NaverApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NAVER API HUB 뉴스 검색 — 근거 수집(FR-CA-02)에서만 쓴다. 날짜 필터 파라미터가 없어 sort=date(최신순)로 받는다.
 *
 * 페이지 반복은 이 클라이언트에 둔다(B2 DartApiClient.list와 같은 위치) — "언제 다음 페이지를 부를지"가 응답 형식(start · display 상한)에 묶여 있어서.
 * 수집기는 기간 · 제목 필터 · 변환만 한다.
 *
 * 키 유출 방지 (AGENTS 9장): Client ID · Secret은 헤더로만 보낸다. 검색어는 URI 템플릿 변수(한글 · 공백 · & 인코딩).
 * RestClientException은 cause로 붙이지 않고 유형만 담아 다시 던진다. 로그에는 실패 유형 · HTTP 상태 · errorCode · 소요 시간만.
 * 같은 실행 안에서 재시도하지 않는다 — 실패는 수집기 실패가 되고, 분석은 PENDING으로 남는다(B1).
 */
@Slf4j
@Component
public class NaverNewsApiClient {

    private static final String NEWS_URI = "/search/v1/news?query={query}&display={display}&start={start}&sort={sort}";
    /** display 최대 · start 최대 (문서) */
    static final int DISPLAY = 100;
    static final int MAX_START = 1000;
    private static final String SORT_DATE = "date";

    private final RestClient restClient;
    private final NaverProperties properties;
    private final NaverMetrics metrics;

    public NaverNewsApiClient(@Qualifier("naverRestClient") RestClient restClient, NaverProperties properties, NaverMetrics metrics) {
        this.restClient = restClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 최신순으로 받다가 기간 시작(from)보다 오래된 기사가 나오거나 마지막 페이지면 멈춘다.
     * max-pages 또는 start 1,000에 걸리면 실패가 아니라 limitReached=true로 끝낸다(확정 사항 6).
     * 한 페이지라도 실패하면 전체 실패 — 일부만 돌려주지 않는다(확정 사항 7)
     */
    public NaverNewsPages fetchSince(String query, LocalDate from) {
        List<NaverNewsItem> items = new ArrayList<>();
        for (int page = 1; ; page++) {
            int start = 1 + (page - 1) * DISPLAY;
            if (page > properties.maxPages() || start > MAX_START) {
                return new NaverNewsPages(List.copyOf(items), true);
            }
            List<NaverNewsItem> received = search(query, start).items();
            items.addAll(received);
            if (received.size() < DISPLAY
                    || received.get(received.size() - 1).publishedAt().toLocalDate().isBefore(from)) {
                return new NaverNewsPages(List.copyOf(items), false);
            }
        }
    }

    /** 1회 호출 */
    public NaverNewsSearchResult search(String query, int start) {
        if (!properties.hasCredentials()) {
            log.error("[NAVER] 키 미설정 api={} — NAVER_API_HUB_CLIENT_ID · NAVER_API_HUB_CLIENT_SECRET", NaverMetrics.API_NEWS);
            throw new NaverApiException(NaverErrorType.CONFIG_MISSING, null, null);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("query", query);
        vars.put("display", DISPLAY);
        vars.put("start", start);
        vars.put("sort", SORT_DATE);
        long startNanos = System.nanoTime();
        ResponseEntity<String> response;
        try {
            response = restClient.get().uri(NEWS_URI, vars)
                    .headers(h -> {
                        h.set("X-NCP-APIGW-API-KEY-ID", properties.clientId());
                        h.set("X-NCP-APIGW-API-KEY", properties.clientSecret());
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            metrics.record(false, startNanos);
            log.warn("[NAVER] 호출 실패 api={} type={} error={} elapsedMs={}", NaverMetrics.API_NEWS, NaverErrorType.NETWORK_ERROR,
                    e.getClass().getSimpleName(), elapsedMs(startNanos));
            throw new NaverApiException(NaverErrorType.NETWORK_ERROR, null, null);
        }

        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            metrics.record(false, startNanos);
            NaverApiException e = new NaverApiException(NaverErrorType.fromHttpStatus(status), status,
                    NaverNewsParser.errorCode(response.getBody()));
            logFailure(e, startNanos);
            throw e;
        }
        try {
            NaverNewsSearchResult result = NaverNewsParser.parse(response.getBody());
            metrics.record(true, startNanos);
            return result;
        } catch (NaverApiException e) {
            metrics.record(false, startNanos);
            logFailure(e, startNanos);
            throw e;
        }
    }

    private static void logFailure(NaverApiException e, long startNanos) {
        if (e.getErrorType().isRetryable()) {
            log.warn("[NAVER] 실패 응답 api={} type={} http={} code={} elapsedMs={}", NaverMetrics.API_NEWS, e.getErrorType(),
                    e.getHttpStatus(), e.getErrorCode(), elapsedMs(startNanos));
        } else {
            log.error("[NAVER] 실패 응답(재시도 안 함) api={} type={} http={} code={} elapsedMs={}", NaverMetrics.API_NEWS,
                    e.getErrorType(), e.getHttpStatus(), e.getErrorCode(), elapsedMs(startNanos));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
