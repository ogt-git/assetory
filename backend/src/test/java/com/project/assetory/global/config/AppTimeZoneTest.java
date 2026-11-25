package com.project.assetory.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppTimeZoneTest {

    private TimeZone original;

    @BeforeEach
    void saveOriginal() {
        original = TimeZone.getDefault();
    }

    @AfterEach
    void restoreOriginal() {
        TimeZone.setDefault(original);
    }

    @Test
    @DisplayName("서버가 UTC로 떠도 apply() 뒤 JVM 기본 시간대는 Asia/Seoul")
    void appliesSeoulOverUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        AppTimeZone.apply();

        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    @DisplayName("기준 시간대 상수는 Asia/Seoul")
    void zoneConstant() {
        assertThat(AppTimeZone.ZONE).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
