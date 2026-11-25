package com.project.assetory.global.config;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * JVM 기본 시간대를 Asia/Seoul로 고정한다 (기술 스택: 시간대 Asia/Seoul).
 * 서버 OS 시간대(CI · 클라우드는 보통 UTC)와 상관없이 LocalDateTime.now() · JdbcTemplate · native 쿼리 시각이
 * hibernate.jdbc.time_zone(Asia/Seoul)과 같은 기준이 되게 한다 — 다르면 JPA로 쓴 시각과 JDBC로 쓴 시각이 9시간 어긋난다.
 * main()에서 Spring 기동 전에 호출한다. 테스트 JVM은 build.gradle의 -Duser.timezone으로 같은 값을 쓴다.
 */
public final class AppTimeZone {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private AppTimeZone() {}

    public static void apply() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }
}
