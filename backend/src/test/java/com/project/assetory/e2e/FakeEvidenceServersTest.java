package com.project.assetory.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가짜 DART · 네이버 · ECOS 서버 자체 (B5 Phase 1) — 운영 클라이언트 없이 HTTP로 직접 부른다.
 * 응답 형식은 B2~B4 픽스처와 같은 공식 문서 기준(실응답 미대조). 키는 모두 테스트 전용 더미 값이다
 */
class FakeEvidenceServersTest {

    private static final String DART_KEY = "fake-only-dart-key";
    private static final String NAVER_ID = "fake-only-naver-id";
    private static final String NAVER_SECRET = "fake-only-naver-secret";
    private static final String ECOS_KEY = "fake-only-ecos-key";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final FakeDartServer DART = new FakeDartServer(DART_KEY);
    private static final FakeNaverNewsServer NAVER = new FakeNaverNewsServer(NAVER_ID, NAVER_SECRET);
    private static final FakeEcosServer ECOS = new FakeEcosServer(ECOS_KEY);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterAll
    static void stop() {
        DART.stop();
        NAVER.stop();
        ECOS.stop();
    }

    @Nested
    @DisplayName("FakeDartServer")
    class Dart {

        @BeforeEach
        void reset() {
            DART.reset();
            DART.corp("999901", "00999901", "테스트전자");
            DART.disclosure("00999901", "B", "20261105900001", "주요사항보고서(자기주식취득결정)", LocalDate.of(2026, 11, 5));
            DART.disclosure("00999901", "B", "20261101900002", "주요사항보고서(유상증자결정)", LocalDate.of(2026, 11, 1));
            DART.disclosure("00999901", "I", "20261105900003", "단일판매ㆍ공급계약체결", LocalDate.of(2026, 11, 5));
            DART.disclosure("00999902", "B", "20261105900004", "다른 회사 공시", LocalDate.of(2026, 11, 5));
        }

        @Test
        @DisplayName("1. 회사 · 유형 · 접수일 범위로 거르고 status 000, 없으면 013")
        void filter() throws Exception {
            JsonNode body = json(get(DART.baseUrl() + "/list.json?crtfc_key=" + DART_KEY
                    + "&corp_code=00999901&bgn_de=20261104&end_de=20261106&pblntf_ty=B&page_no=1&page_count=100"));

            assertThat(body.path("status").asText()).isEqualTo("000");
            assertThat(body.path("list")).hasSize(1);
            assertThat(body.path("list").get(0).path("rcept_no").asText()).isEqualTo("20261105900001");
            assertThat(body.path("list").get(0).path("rcept_dt").asText()).isEqualTo("20261105");

            JsonNode none = json(get(DART.baseUrl() + "/list.json?crtfc_key=" + DART_KEY
                    + "&corp_code=00999901&bgn_de=20261106&end_de=20261107&pblntf_ty=B&page_no=1&page_count=100"));
            assertThat(none.path("status").asText()).isEqualTo("013");
        }

        @Test
        @DisplayName("1-b. page_no · page_count로 나누고 total_page를 알려 준다")
        void paging() throws Exception {
            for (int i = 10; i < 13; i++) {
                DART.disclosure("00999901", "D", "202611059000" + i, "지분공시 " + i, LocalDate.of(2026, 11, 5));
            }
            String base = DART.baseUrl() + "/list.json?crtfc_key=" + DART_KEY
                    + "&corp_code=00999901&bgn_de=20261101&end_de=20261130&pblntf_ty=D&page_count=2&page_no=";

            JsonNode p1 = json(get(base + "1"));
            JsonNode p2 = json(get(base + "2"));

            assertThat(p1.path("total_count").asInt()).isEqualTo(3);
            assertThat(p1.path("total_page").asInt()).isEqualTo(2);
            assertThat(p1.path("list")).hasSize(2);
            assertThat(p2.path("page_no").asInt()).isEqualTo(2);
            assertThat(p2.path("list")).hasSize(1);
        }

        @Test
        @DisplayName("2 · 3. 키 불일치 → 010, 모드 전환 010 · 020 · 연결 끊김")
        void modes() throws Exception {
            String url = DART.baseUrl() + "/list.json?crtfc_key=wrong&corp_code=00999901&bgn_de=20261101&end_de=20261130"
                    + "&pblntf_ty=B&page_no=1&page_count=100";
            assertThat(json(get(url)).path("status").asText()).isEqualTo("010");

            String ok = url.replace("crtfc_key=wrong", "crtfc_key=" + DART_KEY);
            DART.mode(FakeDartServer.Mode.BAD_KEY);
            assertThat(json(get(ok)).path("status").asText()).isEqualTo("010");
            DART.mode(FakeDartServer.Mode.RATE_LIMIT);
            assertThat(json(get(ok)).path("status").asText()).isEqualTo("020");
            DART.mode(FakeDartServer.Mode.NETWORK_DOWN);
            assertThatThrownBy(() -> get(ok)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("4. corpCode.xml → 실제 ZIP(CORPCODE.xml), 등록한 상장사 + 비상장 1건(stock_code 공백)")
        void corpCodeZip() throws Exception {
            byte[] zip = getBytes(DART.baseUrl() + "/corpCode.xml?crtfc_key=" + DART_KEY);

            assertThat(zip[0]).isEqualTo((byte) 'P');
            assertThat(zip[1]).isEqualTo((byte) 'K');
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry entry = in.getNextEntry();
                assertThat(entry.getName()).isEqualTo("CORPCODE.xml");
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(xml).contains("<corp_code>00999901</corp_code>").contains("<stock_code>999901</stock_code>")
                        .contains("<stock_code> </stock_code>");
            }
            // 키가 다르면 ZIP이 아닌 오류 응답
            assertThat(new String(getBytes(DART.baseUrl() + "/corpCode.xml?crtfc_key=wrong"), StandardCharsets.UTF_8)).contains("010");
        }

        @Test
        @DisplayName("6. 엔드포인트별 호출 횟수 · 마지막 요청 기록, 초기화")
        void counts() throws Exception {
            get(DART.baseUrl() + "/corpCode.xml?crtfc_key=" + DART_KEY);
            get(DART.baseUrl() + "/list.json?crtfc_key=" + DART_KEY + "&corp_code=00999901&bgn_de=20261101&end_de=20261130"
                    + "&pblntf_ty=B&page_no=1&page_count=100");

            assertThat(DART.requests("corp-code")).isEqualTo(1);
            assertThat(DART.requests("list")).isEqualTo(1);
            assertThat(DART.lastQuery("list")).contains("pblntf_ty=B");
            DART.reset();
            assertThat(DART.requests("list")).isZero();
            assertThat(DART.lastQuery("list")).isNull();
        }
    }

    @Nested
    @DisplayName("FakeNaverNewsServer")
    class Naver {

        @BeforeEach
        void reset() {
            NAVER.reset();
            for (int i = 0; i < 5; i++) {
                NAVER.article("테스트 전자", "<b>테스트 전자</b> 기사 " + i, "https://press.example.com/a/" + i,
                        "https://n.news.naver.com/a/" + i, "테스트 요약 " + i,
                        ZonedDateTime.of(2026, 11, 5, 9 + i, 0, 0, 0, KST));
            }
            NAVER.article("다른 종목", "다른 기사", "https://press.example.com/b/1", "https://n.news.naver.com/b/1", "요약",
                    ZonedDateTime.of(2026, 11, 5, 9, 0, 0, 0, KST));
        }

        @Test
        @DisplayName("1 · 5. 한글 · 공백 query를 디코딩해 매칭, pubDate 최신순, start · display 페이지")
        void search() throws Exception {
            JsonNode page1 = json(naver("테스트 전자", 1, 2, NAVER_ID, NAVER_SECRET).body());
            JsonNode page2 = json(naver("테스트 전자", 3, 2, NAVER_ID, NAVER_SECRET).body());

            assertThat(page1.path("total").asInt()).isEqualTo(5);
            assertThat(page1.path("items")).hasSize(2);
            assertThat(page1.path("items").get(0).path("title").asText()).isEqualTo("<b>테스트 전자</b> 기사 4");
            assertThat(page1.path("items").get(0).path("pubDate").asText()).isEqualTo("Thu, 05 Nov 2026 13:00:00 +0900");
            assertThat(page2.path("start").asInt()).isEqualTo(3);
            assertThat(page2.path("items").get(0).path("title").asText()).isEqualTo("<b>테스트 전자</b> 기사 2");
            assertThat(json(naver("없는 종목", 1, 100, NAVER_ID, NAVER_SECRET).body()).path("items")).isEmpty();
            assertThat(NAVER.lastQuery()).isEqualTo("없는 종목");
        }

        @Test
        @DisplayName("2 · 3. 헤더 키 불일치 → 401, 모드 401 · 429 · 연결 끊김")
        void modes() throws Exception {
            assertThat(naver("테스트 전자", 1, 10, NAVER_ID, "wrong").statusCode()).isEqualTo(401);
            assertThat(naver("테스트 전자", 1, 10, "wrong", NAVER_SECRET).statusCode()).isEqualTo(401);

            NAVER.mode(FakeNaverNewsServer.Mode.UNAUTHORIZED);
            assertThat(naver("테스트 전자", 1, 10, NAVER_ID, NAVER_SECRET).statusCode()).isEqualTo(401);
            NAVER.mode(FakeNaverNewsServer.Mode.RATE_LIMIT);
            assertThat(naver("테스트 전자", 1, 10, NAVER_ID, NAVER_SECRET).statusCode()).isEqualTo(429);
            NAVER.mode(FakeNaverNewsServer.Mode.NETWORK_DOWN);
            assertThatThrownBy(() -> naver("테스트 전자", 1, 10, NAVER_ID, NAVER_SECRET)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("6. 호출 횟수 기록 · 초기화")
        void counts() throws Exception {
            naver("테스트 전자", 1, 10, NAVER_ID, NAVER_SECRET);
            naver("테스트 전자", 11, 10, NAVER_ID, NAVER_SECRET);

            assertThat(NAVER.requests()).isEqualTo(2);
            NAVER.reset();
            assertThat(NAVER.requests()).isZero();
            assertThat(json(naver("테스트 전자", 1, 10, NAVER_ID, NAVER_SECRET).body()).path("items")).isEmpty();
        }

        private HttpResponse<String> naver(String query, int start, int display, String id, String secret) throws Exception {
            URI uri = URI.create(NAVER.baseUrl() + "/search/v1/news?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&display=" + display + "&start=" + start + "&sort=date");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("X-NCP-APIGW-API-KEY-ID", id).header("X-NCP-APIGW-API-KEY", secret).GET().build();
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("FakeEcosServer")
    class Ecos {

        @BeforeEach
        void reset() {
            ECOS.reset();
            ECOS.value("731Y001", "0000001", LocalDate.of(2026, 11, 3), new BigDecimal("1393.3"));
            ECOS.value("731Y001", "0000001", LocalDate.of(2026, 11, 4), new BigDecimal("1400"));
            ECOS.value("731Y001", "0000001", LocalDate.of(2026, 11, 5), new BigDecimal("1407"));
            ECOS.value("722Y001", "0101000", LocalDate.of(2026, 11, 5), new BigDecimal("2.5"));
        }

        @Test
        @DisplayName("1. 경로에서 통계표 · 기간 · 항목을 읽어 거르고 날짜 오름차순으로, 없으면 INFO-200")
        void filter() throws Exception {
            JsonNode body = json(get(ecos(ECOS_KEY, "731Y001", "20261104", "20261130", "0000001")));

            List<String> times = new ArrayList<>();
            body.path("StatisticSearch").path("row").forEach(r -> times.add(r.path("TIME").asText()));
            assertThat(times).containsExactly("20261104", "20261105");
            assertThat(body.path("StatisticSearch").path("row").get(0).path("ITEM_CODE1").asText()).isEqualTo("0000001");
            assertThat(body.path("StatisticSearch").path("row").get(0).path("DATA_VALUE").asText()).isEqualTo("1400");

            assertThat(json(get(ecos(ECOS_KEY, "731Y001", "20261201", "20261231", "0000001"))).path("RESULT").path("CODE").asText())
                    .isEqualTo("INFO-200");
        }

        @Test
        @DisplayName("2 · 3. 경로 키 불일치 → INFO-100, 모드 INFO-100 · INFO-200 · ERROR-602 · 연결 끊김")
        void modes() throws Exception {
            assertThat(code(ecos("wrong", "731Y001", "20261101", "20261130", "0000001"))).isEqualTo("INFO-100");
            String ok = ecos(ECOS_KEY, "731Y001", "20261101", "20261130", "0000001");
            ECOS.mode(FakeEcosServer.Mode.BAD_KEY);
            assertThat(code(ok)).isEqualTo("INFO-100");
            ECOS.mode(FakeEcosServer.Mode.NO_DATA);
            assertThat(code(ok)).isEqualTo("INFO-200");
            ECOS.mode(FakeEcosServer.Mode.RATE_LIMIT);
            assertThat(code(ok)).isEqualTo("ERROR-602");
            ECOS.mode(FakeEcosServer.Mode.NETWORK_DOWN);
            assertThatThrownBy(() -> get(ok)).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("6. 호출 횟수 · 마지막 통계표 기록, 초기화")
        void counts() throws Exception {
            get(ecos(ECOS_KEY, "722Y001", "20261101", "20261130", "0101000"));

            assertThat(ECOS.requests()).isEqualTo(1);
            assertThat(ECOS.lastStatCode()).isEqualTo("722Y001");
            ECOS.reset();
            assertThat(ECOS.requests()).isZero();
        }

        private String ecos(String key, String stat, String from, String to, String item) {
            return ECOS.baseUrl() + "/StatisticSearch/" + key + "/json/kr/1/100/" + stat + "/D/" + from + "/" + to + "/" + item;
        }

        private String code(String url) throws Exception {
            return json(get(url)).path("RESULT").path("CODE").asText();
        }
    }

    private static String get(String url) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private static byte[] getBytes(String url) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private static JsonNode json(String body) throws IOException {
        return JSON.readTree(body);
    }
}
