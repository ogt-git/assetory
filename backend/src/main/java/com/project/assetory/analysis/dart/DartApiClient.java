package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.exception.DartApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenDART 호출 — 공시검색(list.json) · 고유번호(corpCode.xml). 근거 수집(FR-CA-01)에서만 쓴다.
 *
 * 키 유출 방지 (AGENTS 9장): DART는 키를 쿼리(crtfc_key)로 받는다.
 * - URI 템플릿 변수로만 요청을 만든다 — http.client.requests uri 태그 · 로그에 완성 URL이 남지 않게
 * - RestClientException(I/O 메시지에 요청 URL이 들어간다)은 cause로 붙이지 않고 유형만 담아 다시 던진다
 * - 로그에는 api · 실패 유형 · DART status · 소요 시간만
 * 같은 실행 안에서 재시도하지 않는다 — 실패는 수집기 실패가 되고, 분석은 PENDING으로 남아 다음 실행 때 다시 수집한다(B1).
 */
@Slf4j
@Component
public class DartApiClient {

    private static final String LIST_URI = "/list.json?crtfc_key={key}&corp_code={corpCode}&bgn_de={bgnDe}&end_de={endDe}"
            + "&pblntf_ty={type}&page_no={pageNo}&page_count={pageCount}";
    private static final String CORP_CODE_URI = "/corpCode.xml?crtfc_key={key}";
    /** 공시검색 page_count 최대값 (개발가이드) */
    static final int PAGE_COUNT = 100;
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient listClient;
    private final RestClient corpCodeClient;
    private final DartProperties properties;
    private final DartMetrics metrics;

    public DartApiClient(@Qualifier("dartListRestClient") RestClient listClient,
                         @Qualifier("dartCorpCodeRestClient") RestClient corpCodeClient,
                         DartProperties properties, DartMetrics metrics) {
        this.listClient = listClient;
        this.corpCodeClient = corpCodeClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 한 회사 · 한 유형의 접수일 범위 공시 — total_page까지 모두 받아 합친다. 013(없음)은 빈 목록.
     * 페이지 상한을 넘거나 한 페이지라도 실패하면 전체 실패 (일부만 돌려주지 않는다 — B2 확정 사항 7)
     */
    public List<DartDisclosure> list(String corpCode, LocalDate bgnDe, LocalDate endDe, String type) {
        requireKey(DartMetrics.API_LIST);
        List<DartDisclosure> result = new ArrayList<>();
        for (int pageNo = 1; ; pageNo++) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("key", properties.apiKey());
            vars.put("corpCode", corpCode);
            vars.put("bgnDe", bgnDe.format(BASIC));
            vars.put("endDe", endDe.format(BASIC));
            vars.put("type", type);
            vars.put("pageNo", pageNo);
            vars.put("pageCount", PAGE_COUNT);
            DartListPage page = call(DartMetrics.API_LIST, () -> DartListParser.parse(
                    listClient.get().uri(LIST_URI, vars).retrieve()
                            .onStatus(HttpStatusCode::isError, (req, res) -> { })
                            .body(String.class)));
            result.addAll(page.items());
            if (pageNo >= page.totalPage()) {
                return List.copyOf(result);
            }
            if (pageNo >= properties.maxPages()) {
                log.error("[DART] 페이지 상한 초과 api={} maxPages={} totalPage={}", DartMetrics.API_LIST,
                        properties.maxPages(), page.totalPage());
                throw new DartApiException(DartErrorType.TOO_MANY_PAGES, null);
            }
        }
    }

    /** 상장사 stock_code → corp_code 전체 (ZIP 1회) */
    public Map<String, String> corpCodes() {
        requireKey(DartMetrics.API_CORP_CODE);
        return call(DartMetrics.API_CORP_CODE, () -> DartCorpCodeParser.parse(
                corpCodeClient.get().uri(CORP_CODE_URI, Map.of("key", properties.apiKey())).retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> { })
                        .body(byte[].class)));
    }

    private <T> T call(String api, java.util.function.Supplier<T> request) {
        long start = System.nanoTime();
        try {
            T result = request.get();
            metrics.record(api, true, start);
            return result;
        } catch (RestClientException e) {
            metrics.record(api, false, start);
            log.warn("[DART] 호출 실패 api={} type={} error={} elapsedMs={}", api, DartErrorType.NETWORK_ERROR,
                    e.getClass().getSimpleName(), elapsedMs(start));
            // cause를 붙이지 않는다 — 메시지에 crtfc_key가 든 요청 URL이 있다
            throw new DartApiException(DartErrorType.NETWORK_ERROR, null);
        } catch (DartApiException e) {
            metrics.record(api, false, start);
            if (e.getErrorType().isRetryable()) {
                log.warn("[DART] 실패 응답 api={} type={} status={} elapsedMs={}", api, e.getErrorType(), e.getDartCode(), elapsedMs(start));
            } else {
                log.error("[DART] 실패 응답(재시도 안 함) api={} type={} status={} elapsedMs={}", api, e.getErrorType(),
                        e.getDartCode(), elapsedMs(start));
            }
            throw e;
        }
    }

    private void requireKey(String api) {
        if (!properties.hasApiKey()) {
            log.error("[DART] 키 미설정 api={} — DART_API_KEY", api);
            throw new DartApiException(DartErrorType.CONFIG_MISSING, null);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
