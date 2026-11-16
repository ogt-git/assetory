package com.project.assetory.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * B0 근거 소스 실호출 검증 (DART D1~D5 · ECOS E1 · E2 · 네이버 API HUB N2~N7). 기본 `./gradlew test`에서는 제외되고
 * `./gradlew evidenceLiveTest`로만 돈다 (docs/ops/evidence-source-verification.md).
 * 키가 없는 소스는 그 소스만 "미실행"으로 기록하고 정상 종료한다. 결과는 build/evidence-live/에만 남긴다.
 * 키 · 요청 URL(DART · ECOS는 URL에 키가 들어간다) · 예외 메시지는 출력하지 않는다 — 실패는 예외 클래스 이름만 적는다.
 * 집계 위주로 남기고, 기사 제목은 N4 분류표(별도 파일)에만 최소한으로 넣는다. 관련 여부는 사용자가 직접 표시한다.
 */
@Tag("evidence-live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvidenceSourceLiveVerificationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Path OUT_DIR = Path.of("build", "evidence-live");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final StringBuilder REPORT = new StringBuilder();
    private static final StringBuilder N4_TABLE = new StringBuilder();
    /** N4 분류표에 키워드마다 넣는 D 당일 기사 수 */
    private static final int N4_PER_KEYWORD = 6;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DART_BASE = "https://opendart.fss.or.kr/api";
    private static final String ECOS_BASE = "https://ecos.bok.or.kr/api";
    private static final String NAVER_BASE = "https://naverapihub.apigw.ntruss.com";
    /** 테스트 전용 — 실제 키가 아닌 고정 문자열. 잘못된 키 응답(D3 · E2)을 보려고 쓴다 */
    private static final String INVALID_KEY = "invalid-key-for-test";
    /** 샘플 이벤트가 없을 때도 corp_code 커버리지를 볼 기본 종목 (대형주 · KOSDAQ · 일반 단어 이름 · 영문 이름 · ETF 2개) */
    private static final List<String> DEFAULT_TICKERS = List.of("000660", "086520", "001680", "035420", "122630", "069500");
    private static final List<String> DART_TYPES = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");

    private static RestClient rest;
    private static List<SampleEvent> samples;
    /** stock_code → corp_code (D1에서 채운다) */
    private static final Map<String, String> CORP_CODES = new HashMap<>();

    private record SampleEvent(String ticker, String name, LocalDate date) {
        String label() {
            return ticker + " " + date;
        }
    }

    @BeforeAll
    static void setUp() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        rest = RestClient.builder().requestFactory(factory).build();
        samples = parseSamples(System.getenv("EVIDENCE_SAMPLE_EVENTS"));
        line("# 근거 소스 실호출 검증 결과 — " + LocalDateTime.now(KST).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        line("- 키: DART " + present("DART_API_KEY") + " · ECOS " + present("ECOS_API_KEY")
                + " · 네이버 " + (hasEnv("NAVER_API_HUB_CLIENT_ID") && hasEnv("NAVER_API_HUB_CLIENT_SECRET") ? "있음" : "없음"));
        line("- 샘플 이벤트: " + (samples.isEmpty() ? "없음 (EVIDENCE_SAMPLE_EVENTS 미설정 — D4 · N4~N6 미실행)"
                : samples.size() + "건 — " + String.join(", ", samples.stream().map(SampleEvent::label).toList())));
    }

    @AfterAll
    static void writeReport() throws IOException {
        Files.createDirectories(OUT_DIR);
        String stamp = LocalDateTime.now(KST).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = OUT_DIR.resolve("verification-" + stamp + ".md");
        Files.writeString(file, REPORT.toString(), StandardCharsets.UTF_8);
        System.out.println("\n[evidence-live] 결과 파일: " + file.toAbsolutePath());
        if (!N4_TABLE.isEmpty()) {
            // 기사 제목이 들어 있는 파일 — 분류가 끝나면 지운다 (Phase 4)
            Path n4 = OUT_DIR.resolve("n4-classify-" + stamp + ".md");
            Files.writeString(n4, N4_TABLE.toString(), StandardCharsets.UTF_8);
            System.out.println("[evidence-live] N4 분류표: " + n4.toAbsolutePath());
        }
    }

    // ───────────────────────── DART ─────────────────────────

    @Test
    @Order(1)
    @DisplayName("D1 · D5 — corpCode.xml 크기 · 건수 · 샘플 종목과 ETF의 corp_code 커버리지")
    void d1_corpCode() {
        line("\n## D1 · D5 DART 고유번호 파일");
        String key = System.getenv("DART_API_KEY");
        if (isBlank(key)) {
            line("- 미실행 (DART_API_KEY 없음)");
            return;
        }
        call("D1", () -> {
            long started = System.currentTimeMillis();
            byte[] body = rest.get().uri(DART_BASE + "/corpCode.xml?crtfc_key={k}", key)
                    .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).body(byte[].class);
            long elapsed = System.currentTimeMillis() - started;
            if (body == null || body.length < 2 || body[0] != 'P' || body[1] != 'K') {
                line("- ZIP이 아님 — 응답 status: " + dartStatusOf(body));
                return;
            }
            line("- ZIP 크기: " + body.length + " bytes · 받는 데 " + elapsed + " ms");
            int[] counts = parseCorpCodes(body);
            line("- 전체 " + counts[0] + "건 · stock_code 있는 건 " + counts[1] + "건 · ZIP 안 파일 이름: " + (counts[2] == 1 ? "CORPCODE.xml" : "기타"));
            Set<String> tickers = new LinkedHashSet<>(DEFAULT_TICKERS);
            samples.forEach(s -> tickers.add(s.ticker()));
            line("\n| 종목코드 | corp_code 있음 |");
            line("|---|---|");
            tickers.forEach(t -> line("| " + t + " | " + (CORP_CODES.containsKey(t) ? "있음" : "없음") + " |"));
            line("\nD5: ETF(122630 · 069500)가 '없음'이면 ETF 이벤트는 DART 근거 대상이 아니다.");
        });
    }

    @Test
    @Order(2)
    @DisplayName("D2 — list.json 응답 필드 이름 · 시각 필드 유무 · rcept_no 앞 8자리 = rcept_dt 여부")
    void d2_listFields() {
        line("\n## D2 공시 목록 필드");
        String key = System.getenv("DART_API_KEY");
        if (isBlank(key)) {
            line("- 미실행 (DART_API_KEY 없음)");
            return;
        }
        String corpCode = CORP_CODES.getOrDefault("000660", null);
        if (corpCode == null) {
            line("- 미실행 (D1에서 000660 corp_code를 얻지 못함)");
            return;
        }
        call("D2", () -> {
            LocalDate to = LocalDate.now(KST);
            JsonNode body = dartList(key, Map.of("corp_code", corpCode, "bgn_de", to.minusDays(60).format(BASIC),
                    "end_de", to.format(BASIC), "page_count", "100"));
            line("- status: " + body.path("status").asText() + " · total_count: " + body.path("total_count").asText());
            Set<String> fields = new LinkedHashSet<>();
            int sameDate = 0;
            int rows = 0;
            for (JsonNode item : body.path("list")) {
                item.fieldNames().forEachRemaining(fields::add);
                rows++;
                String rceptNo = item.path("rcept_no").asText();
                if (rceptNo.length() >= 8 && rceptNo.substring(0, 8).equals(item.path("rcept_dt").asText())) sameDate++;
            }
            line("- 필드: " + String.join(", ", fields));
            line("- 시각 성격 필드(이름에 tm · time · hour 포함): "
                    + fields.stream().filter(f -> f.contains("tm") || f.contains("time") || f.contains("hour")).toList());
            line("- rcept_no 앞 8자리 = rcept_dt인 행: " + sameDate + " / " + rows);
        });
    }

    @Test
    @Order(3)
    @DisplayName("D3 — 오류 응답 실제 값: 잘못된 키 · corp_code 없이 3개월 초과 · 결과 없음")
    void d3_errors() {
        line("\n## D3 DART 오류 응답");
        String key = System.getenv("DART_API_KEY");
        if (isBlank(key)) {
            line("- 미실행 (DART_API_KEY 없음)");
            return;
        }
        LocalDate today = LocalDate.now(KST);
        line("\n| 경우 | status | message |");
        line("|---|---|---|");
        call("D3-a", () -> dartErrorRow("잘못된 키", dartList(INVALID_KEY,
                Map.of("bgn_de", today.minusDays(7).format(BASIC), "end_de", today.format(BASIC)))));
        call("D3-b", () -> dartErrorRow("corp_code 없이 1년 범위", dartList(key,
                Map.of("bgn_de", today.minusYears(1).format(BASIC), "end_de", today.format(BASIC)))));
        String corpCode = CORP_CODES.get("000660");
        if (corpCode != null) {
            call("D3-c", () -> dartErrorRow("결과 없음(2000년 1주)", dartList(key,
                    Map.of("corp_code", corpCode, "bgn_de", "20000103", "end_de", "20000107"))));
        }
        line("\n한도 초과(020)는 일부러 일으키지 않는다 — 문서 기준으로만 적는다.");
    }

    @Test
    @Order(4)
    @DisplayName("D4 — 샘플 이벤트 전후(D−4 ~ D+3) 공시 유형별 건수 · 보고서명 예시")
    void d4_disclosuresAroundEvents() {
        line("\n## D4 샘플 이벤트 전후 공시 유형별 건수");
        String key = System.getenv("DART_API_KEY");
        if (isBlank(key)) {
            line("- 미실행 (DART_API_KEY 없음)");
            return;
        }
        if (samples.isEmpty()) {
            line("- 미실행 (샘플 이벤트 없음)");
            return;
        }
        line("\n칸 = D 이전 / D 당일 / D 이후 건수 (rcept_dt 기준). 유형: A 정기 B 주요사항 C 발행 D 지분 E 기타 F 외부감사 G 펀드 H 자산유동화 I 거래소 J 공정위");
        line("\n| 이벤트 | " + String.join(" | ", DART_TYPES) + " |");
        line("|---|" + "---|".repeat(DART_TYPES.size()));
        Map<String, Set<String>> reportNames = new TreeMap<>();
        for (SampleEvent s : samples) {
            String corpCode = CORP_CODES.get(s.ticker());
            if (corpCode == null) {
                line("| " + s.label() + " | corp_code 없음 (D1 · D5 참고)" + " |".repeat(DART_TYPES.size()));
                continue;
            }
            call("D4 " + s.label(), () -> {
                StringBuilder row = new StringBuilder("| " + s.label() + " |");
                for (String type : DART_TYPES) {
                    pause(300);
                    JsonNode body = dartList(key, Map.of("corp_code", corpCode, "pblntf_ty", type,
                            "bgn_de", s.date().minusDays(4).format(BASIC), "end_de", s.date().plusDays(3).format(BASIC),
                            "page_count", "100"));
                    int before = 0, same = 0, after = 0;
                    for (JsonNode item : body.path("list")) {
                        LocalDate d = LocalDate.parse(item.path("rcept_dt").asText(), BASIC);
                        if (d.isBefore(s.date())) before++;
                        else if (d.isEqual(s.date())) same++;
                        else after++;
                        Set<String> names = reportNames.computeIfAbsent(type, t -> new LinkedHashSet<>());
                        if (names.size() < 8) names.add(item.path("report_nm").asText().trim());
                    }
                    row.append(' ').append(before).append('/').append(same).append('/').append(after).append(" |");
                }
                line(row.toString());
            });
        }
        line("\n보고서명 예시(유형 분류용, 유형당 최대 8개):");
        reportNames.forEach((type, names) -> line("- " + type + ": " + String.join(" · ", names)));
    }

    // ───────────────────────── ECOS ─────────────────────────

    @Test
    @Order(5)
    @DisplayName("E1 — 기준금리(722Y001/0101000) · 원/달러(731Y001/0000001) 일별 최근 값과 실행 시각 대비 최신 TIME")
    void e1_latestValues() {
        line("\n## E1 ECOS 최근 값");
        String key = System.getenv("ECOS_API_KEY");
        if (isBlank(key)) {
            line("- 미실행 (ECOS_API_KEY 없음)");
            return;
        }
        LocalDateTime now = LocalDateTime.now(KST);
        line("- 실행 시각(KST): " + now + " (" + now.getDayOfWeek() + ") — 16:00 직후 · 다음 날 아침에 각각 실행해 같은 날 값이 언제 생기는지 비교한다");
        line("\n| 통계 | 행 수 | 최근 TIME 3개 (값) | 최신 TIME = 오늘 |");
        line("|---|---|---|---|");
        for (String[] stat : List.of(new String[]{"722Y001", "0101000", "기준금리"}, new String[]{"731Y001", "0000001", "원/달러"})) {
            call("E1 " + stat[0], () -> {
                JsonNode body = ecosSearch(key, stat[0], now.toLocalDate().minusDays(10), now.toLocalDate(), stat[1]);
                JsonNode rows = body.path("StatisticSearch").path("row");
                if (!rows.isArray() || rows.isEmpty()) {
                    line("| " + stat[2] + " | 0 | RESULT " + body.path("RESULT").path("CODE").asText() + " | — |");
                    return;
                }
                List<String> latest = new ArrayList<>();
                for (int i = rows.size() - 1; i >= 0 && latest.size() < 3; i--) {
                    latest.add(rows.get(i).path("TIME").asText() + " (" + rows.get(i).path("DATA_VALUE").asText() + ")");
                }
                String last = rows.get(rows.size() - 1).path("TIME").asText();
                line("| " + stat[2] + " " + stat[0] + "/" + stat[1] + " | " + rows.size() + " | " + String.join(", ", latest)
                        + " | " + (last.equals(now.toLocalDate().format(BASIC)) ? "같음" : "다름") + " |");
            });
        }
        call("E1 필드", () -> {
            JsonNode rows = ecosSearch(key, "731Y001", now.toLocalDate().minusDays(10), now.toLocalDate(), "0000001")
                    .path("StatisticSearch").path("row");
            Set<String> fields = new LinkedHashSet<>();
            if (rows.isArray() && !rows.isEmpty()) rows.get(0).fieldNames().forEachRemaining(fields::add);
            line("\n- 행 필드: " + String.join(", ", fields) + " — 발표 시각 필드가 없으면 '그날 값 존재 여부'는 TIME으로만 판단한다");
        });
    }

    @Test
    @Order(6)
    @DisplayName("E2 — 휴일만 조회 · 잘못된 키 · 주기와 다른 날짜 형식의 RESULT 코드")
    void e2_holidayAndErrors() {
        line("\n## E2 ECOS 휴일 · 오류 응답");
        String key = System.getenv("ECOS_API_KEY");
        if (isBlank(key)) {
            line("- 미실행 (ECOS_API_KEY 없음)");
            return;
        }
        LocalDate sunday = LocalDate.now(KST);
        while (sunday.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) sunday = sunday.minusDays(1);
        LocalDate saturday = sunday.minusDays(1);
        line("\n| 경우 | RESULT CODE | 행 수 |");
        line("|---|---|---|");
        LocalDate sat = saturday;
        LocalDate sun = sunday;
        call("E2-a", () -> ecosRow("주말만(" + sat + "~" + sun + ") 원/달러", ecosSearch(key, "731Y001", sat, sun, "0000001")));
        call("E2-b", () -> ecosRow("잘못된 키", ecosSearch(INVALID_KEY, "731Y001", sat.minusDays(7), sun, "0000001")));
        call("E2-c", () -> ecosRow("일 주기에 월 형식 날짜", rest.get()
                .uri(ECOS_BASE + "/StatisticSearch/{k}/json/kr/1/10/731Y001/D/202610/202611/0000001", key)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).body(JsonNode.class)));
    }

    // ───────────────────────── 네이버 API HUB ─────────────────────────

    @Test
    @Order(7)
    @DisplayName("N2 · N3 · N7 — 정렬 · 날짜 필터 유무(도달 가능한 가장 오래된 pubDate) · 필드 형식")
    void n2n3n7_searchBehavior() {
        line("\n## N2 · N3 · N7 네이버 뉴스 검색 동작 · 필드");
        if (!naverReady()) return;
        String keyword = samples.isEmpty() ? "SK하이닉스" : samples.get(0).name();
        line("- 검색어: 샘플 1번 종목명 (없으면 SK하이닉스)");
        call("N2", () -> {
            for (String sort : List.of("date", "sim")) {
                JsonNode body = naverSearch(keyword, 1, 30, sort);
                List<ZonedDateTime> dates = pubDates(body);
                boolean descending = true;
                for (int i = 1; i < dates.size(); i++) if (dates.get(i).isAfter(dates.get(i - 1))) descending = false;
                line("- N2 sort=" + sort + ": total " + body.path("total").asText() + " · 30건 pubDate 내림차순 " + (descending ? "예" : "아니오"));
            }
        });
        call("N3", () -> {
            ZonedDateTime oldest = null;
            int fetched = 0;
            int lastStart = 0;
            for (int start = 1; start <= 1000; start += 100) {
                pause(150);
                JsonNode body = naverSearch(keyword, start, 100, "date");
                List<ZonedDateTime> dates = pubDates(body);
                if (dates.isEmpty()) break;
                fetched += dates.size();
                lastStart = start;
                oldest = dates.get(dates.size() - 1);
            }
            line("- N3 날짜 필터 파라미터: 문서에 없음 → sort=date로 start 최대 " + lastStart + "까지 " + fetched
                    + "건 받았을 때 가장 오래된 pubDate: " + oldest + " (이보다 옛날 이벤트는 소급 검색 불가)");
            line("- N3 start=1001 요청: " + naverErrorOf(keyword, 1001));
        });
        call("N7", () -> {
            JsonNode body = naverSearch(keyword, 1, 100, "date");
            Set<String> topFields = new LinkedHashSet<>();
            body.fieldNames().forEachRemaining(topFields::add);
            Set<String> itemFields = new LinkedHashSet<>();
            int bold = 0, entity = 0, emptyOriginal = 0, naverLink = 0, parsedDates = 0, items = 0;
            Map<String, Integer> linkHosts = new TreeMap<>();
            for (JsonNode item : body.path("items")) {
                items++;
                item.fieldNames().forEachRemaining(itemFields::add);
                String title = item.path("title").asText() + item.path("description").asText();
                if (title.contains("<b>")) bold++;
                if (title.matches("(?s).*&(quot|amp|lt|gt|apos|#\\d+);.*")) entity++;
                if (item.path("originallink").asText().isBlank()) emptyOriginal++;
                String linkHost = hostOf(item.path("link").asText());
                if (linkHost.endsWith("naver.com")) naverLink++;
                linkHosts.merge(linkHost.endsWith("naver.com") ? linkHost : "기타(언론사)", 1, Integer::sum);
                if (parsePubDate(item.path("pubDate").asText()) != null) parsedDates++;
            }
            line("- N7 최상위 필드: " + String.join(", ", topFields));
            line("- N7 item 필드: " + String.join(", ", itemFields));
            line("- N7 " + items + "건 중: 제목·요약에 <b> 태그 " + bold + " · HTML 엔티티 " + entity + " · originallink 빈 값 " + emptyOriginal
                    + " · link가 naver.com " + naverLink + " · pubDate RFC 1123 파싱 성공 " + parsedDates);
            line("- N7 link 호스트 분포: " + linkHosts);
        });
    }

    @Test
    @Order(8)
    @DisplayName("N5 · N6 · N4 — 샘플 이벤트별 키워드 4종 건수 · 게시 시각 분포 · 분류표")
    void n4n5n6_events() {
        line("\n## N5 · N6 샘플 이벤트별 뉴스");
        if (!naverReady()) return;
        if (samples.isEmpty()) {
            line("- 미실행 (샘플 이벤트 없음)");
            return;
        }
        line("\n기간 = D−1 00:00 ~ D+3 00:00 (KST, 달력일). '제목에 종목명' = 태그 제거한 제목에 종목명 포함. 도달 = 1,000건 안에서 기간 시작까지 내려갔는지.");
        line("\n| 이벤트 | 키워드 | total | 기간 내 | 제목에 종목명 | 원문 URL 중복 제거 후 | 도달 | D 당일 | D 당일 중 제목에 종목명 |");
        line("|---|---|---|---|---|---|---|---|---|");
        Map<String, int[]> distribution = new LinkedHashMap<>();
        N4_TABLE.append("# N4 분류표 — 관련 여부는 사용자가 직접 표시 (O / X / ?). 분류 후 이 파일은 지운다\n\n")
                .append("키워드(종목코드 · 종목명 + 주가 · 종목명)마다 D 당일(00:00 ~ 24:00) 기사에서 시간대가 고르게 최대 ")
                .append(N4_PER_KEYWORD).append("건을 뽑는다. 같은 기사가 여러 키워드에 나올 수 있다.\n\n")
                .append("| 이벤트 | 키워드 | 게시 시각 | 제목에 종목명 | 제목 | 언론사 도메인 | 관련 |\n|---|---|---|---|---|---|---|\n");
        for (SampleEvent s : samples) {
            ZonedDateTime from = s.date().minusDays(1).atStartOfDay(KST);
            ZonedDateTime to = s.date().plusDays(3).atStartOfDay(KST);
            List<String> keywords = List.of(s.name(), s.name() + " 주가", s.name() + " 특징주", s.ticker());
            for (String keyword : keywords) {
                call("N5 " + s.label(), () -> {
                    List<JsonNode> inRange = new ArrayList<>();
                    boolean reached = false;
                    String total = "";
                    for (int start = 1; start <= 1000; start += 100) {
                        pause(150);
                        JsonNode body = naverSearch(keyword, start, 100, "date");
                        if (start == 1) total = body.path("total").asText();
                        JsonNode items = body.path("items");
                        if (!items.isArray() || items.isEmpty()) {
                            reached = true;
                            break;
                        }
                        ZonedDateTime last = null;
                        for (JsonNode item : items) {
                            ZonedDateTime published = parsePubDate(item.path("pubDate").asText());
                            if (published == null) continue;
                            last = published;
                            if (!published.isBefore(from) && published.isBefore(to)) inRange.add(item);
                        }
                        if (last != null && last.isBefore(from)) {
                            reached = true;
                            break;
                        }
                    }
                    long titleHasName = inRange.stream().filter(i -> plain(i.path("title").asText()).contains(s.name())).count();
                    long distinct = inRange.stream().map(i -> i.path("originallink").asText().isBlank()
                            ? i.path("link").asText() : i.path("originallink").asText()).distinct().count();
                    ZonedDateTime dayStart = s.date().atStartOfDay(KST);
                    ZonedDateTime dayEnd = s.date().plusDays(1).atStartOfDay(KST);
                    List<JsonNode> sameDay = inRange.stream().filter(i -> {
                        ZonedDateTime p = parsePubDate(i.path("pubDate").asText());
                        return !p.isBefore(dayStart) && p.isBefore(dayEnd);
                    }).toList();
                    long sameDayTitleHasName = sameDay.stream().filter(i -> plain(i.path("title").asText()).contains(s.name())).count();
                    line("| " + s.label() + " | " + keywordKind(keyword, s) + " | " + total + " | " + inRange.size() + " | "
                            + titleHasName + " | " + distinct + " | " + (reached ? "예" : "아니오") + " | "
                            + sameDay.size() + " | " + sameDayTitleHasName + " |");
                    if (keyword.equals(s.name())) {
                        distribution.put(s.label(), bucket(inRange, s.date()));
                    }
                    if (!keyword.endsWith(" 특징주")) {
                        // 최신순 목록에서 같은 간격으로 뽑아 하루 전체 시간대가 섞이게 한다
                        int n = Math.min(N4_PER_KEYWORD, sameDay.size());
                        for (int k = 0; k < n; k++) {
                            JsonNode i = sameDay.get(k * sameDay.size() / n);
                            String title = plain(i.path("title").asText());
                            N4_TABLE.append("| ").append(s.label()).append(" | ").append(keywordKind(keyword, s)).append(" | ")
                                    .append(parsePubDate(i.path("pubDate").asText()).toLocalDateTime()).append(" | ")
                                    .append(title.contains(s.name()) ? "예" : "아니오").append(" | ")
                                    .append(title.replace("|", "/")).append(" | ")
                                    .append(hostOf(i.path("originallink").asText())).append(" |  |\n");
                        }
                    }
                });
            }
        }
        line("\n### N6 게시 시각 분포 (키워드 = 종목명)");
        line("\n| 이벤트 | D−1 | D 15:30 전 | D 15:30~16:00 | D 16:00 ~ D+1 09:00 | D+1 09:00 이후 |");
        line("|---|---|---|---|---|---|");
        distribution.forEach((label, c) -> line("| " + label + " | " + c[0] + " | " + c[1] + " | " + c[2] + " | " + c[3] + " | " + c[4] + " |"));
        line("\nD+1은 달력일 기준이다 — 금요일 이벤트는 다음 거래일(월) 09:00과 다르니 판정할 때 감안한다.");
    }

    // ───────────────────────── 보조 ─────────────────────────

    private static int[] bucket(List<JsonNode> items, LocalDate date) {
        ZonedDateTime dayStart = date.atStartOfDay(KST);
        ZonedDateTime close = date.atTime(LocalTime.of(15, 30)).atZone(KST);
        ZonedDateTime batch = date.atTime(LocalTime.of(16, 0)).atZone(KST);
        ZonedDateTime nextOpen = date.plusDays(1).atTime(LocalTime.of(9, 0)).atZone(KST);
        int[] c = new int[5];
        for (JsonNode item : items) {
            ZonedDateTime p = parsePubDate(item.path("pubDate").asText());
            if (p == null) continue;
            if (p.isBefore(dayStart)) c[0]++;
            else if (p.isBefore(close)) c[1]++;
            else if (p.isBefore(batch)) c[2]++;
            else if (p.isBefore(nextOpen)) c[3]++;
            else c[4]++;
        }
        return c;
    }

    private static String keywordKind(String keyword, SampleEvent s) {
        if (keyword.equals(s.name())) return "종목명";
        if (keyword.equals(s.ticker())) return "종목코드";
        return keyword.substring(s.name().length()).trim().isEmpty() ? keyword : "종목명 + " + keyword.substring(s.name().length()).trim();
    }

    private static JsonNode dartList(String key, Map<String, String> params) throws IOException {
        StringBuilder uri = new StringBuilder(DART_BASE + "/list.json?crtfc_key={crtfc_key}");
        Map<String, String> vars = new LinkedHashMap<>(params);
        vars.put("crtfc_key", key);
        params.keySet().forEach(name -> uri.append('&').append(name).append("={").append(name).append('}'));
        String body = rest.get().uri(uri.toString(), vars)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).body(String.class);
        return MAPPER.readTree(body == null ? "{}" : body);
    }

    private static void dartErrorRow(String label, JsonNode body) {
        line("| " + label + " | " + body.path("status").asText() + " | " + body.path("message").asText() + " |");
    }

    private static String dartStatusOf(byte[] body) {
        if (body == null) return "본문 없음";
        try {
            return MAPPER.readTree(body).path("status").asText("알 수 없음");
        } catch (IOException e) {
            // JSON이 아니면 XML 오류 응답 — status 태그만 꺼낸다
            String text = new String(body, StandardCharsets.UTF_8);
            int i = text.indexOf("<status>");
            return i < 0 ? "알 수 없음" : text.substring(i + 8, Math.min(text.length(), i + 11));
        }
    }

    /** CORP_CODES를 채우고 [전체 건수, stock_code 있는 건수, CORPCODE.xml이면 1]을 돌려준다 */
    private static int[] parseCorpCodes(byte[] zip) throws Exception {
        int total = 0, listed = 0, named = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            if (entry != null && entry.getName().equalsIgnoreCase("CORPCODE.xml")) named = 1;
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            XMLStreamReader reader = factory.createXMLStreamReader(in, "UTF-8");
            String corpCode = null, stockCode = null, current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    current = reader.getLocalName();
                    if ("list".equals(current)) {
                        corpCode = null;
                        stockCode = null;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && current != null) {
                    String text = reader.getText().trim();
                    if (text.isEmpty()) continue;
                    if ("corp_code".equals(current)) corpCode = text;
                    if ("stock_code".equals(current)) stockCode = text;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("list".equals(reader.getLocalName())) {
                        total++;
                        if (stockCode != null && !stockCode.isBlank()) {
                            listed++;
                            CORP_CODES.put(stockCode, corpCode);
                        }
                    }
                    current = null;
                }
            }
        }
        return new int[]{total, listed, named};
    }

    private static JsonNode ecosSearch(String key, String stat, LocalDate from, LocalDate to, String item) throws IOException {
        String body = rest.get().uri(ECOS_BASE + "/StatisticSearch/{k}/json/kr/1/100/{s}/D/{f}/{t}/{i}",
                        key, stat, from.format(BASIC), to.format(BASIC), item)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).body(String.class);
        return MAPPER.readTree(body == null ? "{}" : body);
    }

    private static void ecosRow(String label, JsonNode body) {
        JsonNode rows = body.path("StatisticSearch").path("row");
        String code = body.path("RESULT").path("CODE").asText(rows.isArray() ? "정상" : "알 수 없음");
        line("| " + label + " | " + code + " | " + (rows.isArray() ? rows.size() : 0) + " |");
    }

    private static boolean naverReady() {
        if (!hasEnv("NAVER_API_HUB_CLIENT_ID") || !hasEnv("NAVER_API_HUB_CLIENT_SECRET")) {
            line("- 미실행 (NAVER_API_HUB_CLIENT_ID · NAVER_API_HUB_CLIENT_SECRET 없음)");
            return false;
        }
        return true;
    }

    private static JsonNode naverSearch(String query, int start, int display, String sort) throws IOException {
        String body = rest.get()
                .uri(NAVER_BASE + "/search/v1/news?query={q}&display={d}&start={s}&sort={o}", query, display, start, sort)
                .headers(naverHeaders())
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).body(String.class);
        return MAPPER.readTree(body == null ? "{}" : body);
    }

    private static String naverErrorOf(String query, int start) {
        try {
            int[] status = new int[1];
            String body = rest.get()
                    .uri(NAVER_BASE + "/search/v1/news?query={q}&display=10&start={s}", query, start)
                    .headers(naverHeaders())
                    .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> status[0] = res.getStatusCode().value())
                    .body(String.class);
            JsonNode node = MAPPER.readTree(body == null ? "{}" : body);
            return "HTTP " + (status[0] == 0 ? 200 : status[0]) + " · errorCode " + node.path("errorCode").asText("없음");
        } catch (Exception e) {
            return "호출 실패 (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static Consumer<org.springframework.http.HttpHeaders> naverHeaders() {
        return h -> {
            h.set("X-NCP-APIGW-API-KEY-ID", System.getenv("NAVER_API_HUB_CLIENT_ID"));
            h.set("X-NCP-APIGW-API-KEY", System.getenv("NAVER_API_HUB_CLIENT_SECRET"));
        };
    }

    private static List<ZonedDateTime> pubDates(JsonNode body) {
        List<ZonedDateTime> dates = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            ZonedDateTime p = parsePubDate(item.path("pubDate").asText());
            if (p != null) dates.add(p);
        }
        return dates;
    }

    private static ZonedDateTime parsePubDate(String text) {
        try {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(KST);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String plain(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "없음" : host;
        } catch (IllegalArgumentException e) {
            return "없음";
        }
    }

    /** "종목코드:종목명:yyyy-MM-dd" 쉼표 구분. 형식이 틀린 항목은 건너뛴다 */
    private static List<SampleEvent> parseSamples(String raw) {
        if (isBlank(raw)) return List.of();
        List<SampleEvent> result = new ArrayList<>();
        Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(entry -> {
            String[] parts = entry.split(":");
            if (parts.length != 3) return;
            try {
                result.add(new SampleEvent(parts[0].trim(), parts[1].trim(), LocalDate.parse(parts[2].trim())));
            } catch (DateTimeParseException ignored) {
                // 형식이 틀린 항목은 건너뛴다
            }
        });
        return result;
    }

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    /** 실패해도 다음 항목으로 넘어간다. 예외 메시지에는 요청 URL(키 포함)이 들어갈 수 있어 클래스 이름만 남긴다 */
    private static void call(String label, Check check) {
        try {
            check.run();
        } catch (Exception e) {
            line("- " + label + " 호출 실패 (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void line(String text) {
        REPORT.append(text).append('\n');
        System.out.println(text);
    }

    private static boolean hasEnv(String name) {
        return !isBlank(System.getenv(name));
    }

    private static String present(String name) {
        return hasEnv(name) ? "있음" : "없음";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
