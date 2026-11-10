package com.project.assetory.analysis.naver;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * pubDate(RFC 1123, 예: "Thu, 05 Nov 2026 16:20:00 +0900") → KST LocalDateTime. 영문 요일 · 월이라 Locale.ENGLISH로 읽는다.
 * 해석할 수 없으면 IllegalArgumentException — 시각을 추측하지 않는다
 */
public final class NaverPubDate {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

    private NaverPubDate() {
    }

    public static LocalDateTime toKst(String pubDate) {
        if (pubDate == null) {
            throw new IllegalArgumentException("pubDate 없음");
        }
        try {
            return ZonedDateTime.parse(pubDate.strip(), RFC_1123).withZoneSameInstant(KST).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("pubDate 형식이 아니다");
        }
    }
}
