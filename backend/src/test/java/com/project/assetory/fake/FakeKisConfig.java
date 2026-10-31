package com.project.assetory.fake;

import com.project.assetory.e2e.FakeKisServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * fake 프로필 한투 — E2E용 가짜 한투 서버(FakeKisServer)를 띄우고 assetory.kis.base-url을 그 서버로 바꾼다.
 * 실제 KisApiClient · 토큰 관리는 그대로 거친다. 시세는 데모 시드가 등록한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeKisConfig {

    @Bean(destroyMethod = "stop")
    FakeKisServer fakeKisServer() {
        return new FakeKisServer();
    }

    @Bean
    DynamicPropertyRegistrar fakeKisBaseUrl(FakeKisServer server) {
        return registry -> registry.add("assetory.kis.base-url", server::baseUrl);
    }
}
