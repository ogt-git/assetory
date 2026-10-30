package com.project.assetory.fake;

import io.codef.api.EasyCodef;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * fake 프로필 CODEF — 실제 CodefApiClient 대신 고정 응답을 주는 FakeCodefApiClient를 우선 주입한다.
 * 실제 클라이언트 빈도 생기지만 호출되지 않는다(외부 호출 없음).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeCodefConfig {

    @Bean
    @Primary
    FakeCodefApiClient fakeCodefApiClient(EasyCodef easyCodef) {
        return new FakeCodefApiClient(easyCodef);
    }
}
