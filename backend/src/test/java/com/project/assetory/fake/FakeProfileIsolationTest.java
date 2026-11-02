package com.project.assetory.fake;

import com.project.assetory.e2e.FakeKisServer;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// fake 프로필 설정(@TestConfiguration)이 컴포넌트 스캔으로 기존 통합 테스트 컨텍스트에 섞이지 않는다
class FakeProfileIsolationTest extends IntegrationTestSupport {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("통합 테스트 컨텍스트에는 가짜 CODEF · 가짜 한투 · 데모 시드 · fake 설정 빈이 없고 fake 프로필도 꺼져 있다")
    void noFakeBeansInIntegrationContext() {
        assertThat(context.getBeanNamesForType(FakeCodefApiClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeKisServer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeInfraConfig.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeKisConfig.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeCodefConfig.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeSeedConfig.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoDataSeeder.class)).isEmpty();
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("fake");
    }
}
