package com.project.assetory.fake;

import com.project.assetory.e2e.FakeDartServer;
import com.project.assetory.e2e.FakeEcosServer;
import com.project.assetory.e2e.FakeNaverNewsServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// FakeEvidenceConfig만 따로 띄워 본다(DB · Docker 없이) — fake 프로필 yml의 더미 키로 가짜 서버 3개가 뜨고 base-url이 그 주소로 바뀐다
class FakeEvidenceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=fake")
            .withUserConfiguration(FakeEvidenceConfig.class);

    // DynamicPropertyRegistrar는 SpringApplication.from(...).with(...)(bootTestRun) · 테스트 컨텍스트에서만 적용되고
    // ApplicationContextRunner에서는 적용되지 않는다 → 등록기를 직접 불러 무엇을 등록하는지 확인한다. 실제 교체는 bootTestRun에서 확인(Phase 5)
    @Test
    @DisplayName("가짜 DART · 네이버 · ECOS 서버가 fake yml 더미 키로 뜨고, 등록기가 assetory.dart · naver · ecos.base-url을 각 서버 주소로 등록한다")
    void wiresFakeServers() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FakeDartServer dart = context.getBean(FakeDartServer.class);
            FakeNaverNewsServer naver = context.getBean(FakeNaverNewsServer.class);
            FakeEcosServer ecos = context.getBean(FakeEcosServer.class);
            java.util.Map<String, Object> registered = new java.util.HashMap<>();
            context.getBean(org.springframework.test.context.DynamicPropertyRegistrar.class)
                    .accept((name, supplier) -> registered.put(name, supplier.get()));
            assertThat(registered).containsOnly(
                    java.util.Map.entry("assetory.dart.base-url", dart.baseUrl()),
                    java.util.Map.entry("assetory.naver.base-url", naver.baseUrl()),
                    java.util.Map.entry("assetory.ecos.base-url", ecos.baseUrl()));
            var env = context.getEnvironment();
            assertThat(env.getProperty("assetory.dart.api-key")).startsWith("fake-only-");
            assertThat(env.getProperty("assetory.naver.client-id")).startsWith("fake-only-");
            assertThat(env.getProperty("assetory.naver.client-secret")).startsWith("fake-only-");
            assertThat(env.getProperty("assetory.ecos.api-key")).startsWith("fake-only-");
        });
    }
}
