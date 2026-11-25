package com.project.assetory.analysis.naver;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 네이버 테스트 공용 — 테스트 전용 더미 키 · 설정 · 페이지 응답 생성
final class NaverTestSupport {

    static final String BASE_URL = "https://naverapihub.test";
    /** 테스트 전용 가짜 키 — 실제 키가 아니다. 로그 · 예외 · 메트릭에 이 문자열이 나오면 유출이다 */
    static final String TEST_CLIENT_ID = "TEST-ONLY-NAVER-ID-4b1e";
    static final String TEST_CLIENT_SECRET = "TEST-ONLY-NAVER-SECRET-9d2c";

    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

    private NaverTestSupport() {
    }

    static NaverProperties properties(String clientId, String clientSecret, int maxPages) {
        return new NaverProperties(BASE_URL, clientId, clientSecret, maxPages);
    }

    /**
     * 최신순 한 페이지 — first부터 1시간씩 과거로 count건. 테스트 전용 더미 기사
     */
    static String page(int start, int count, LocalDateTime first) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDateTime at = first.minusHours(i);
            String pubDate = at.atZone(ZoneId.of("Asia/Seoul")).format(RFC_1123);
            int n = start + i;
            items.add("""
                    {"title":"<b>테스트전자</b> 기사 %d","originallink":"https://press.example.com/a/%d",
                     "link":"https://n.news.naver.com/a/%d","description":"테스트 요약 %d","pubDate":"%s"}"""
                    .formatted(n, n, n, n, pubDate));
        }
        return """
                {"lastBuildDate":"Thu, 05 Nov 2026 18:40:00 +0900","total":5000,"start":%d,"display":%d,"items":[%s]}"""
                .formatted(start, count, String.join(",", items));
    }
}
