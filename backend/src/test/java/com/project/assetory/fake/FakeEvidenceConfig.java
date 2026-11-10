package com.project.assetory.fake;

import com.project.assetory.e2e.FakeDartServer;
import com.project.assetory.e2e.FakeEcosServer;
import com.project.assetory.e2e.FakeNaverNewsServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * fake 프로필 근거 · 지표 — 가짜 DART · 네이버 · ECOS 서버(B5)를 띄우고 assetory.dart · naver · ecos.base-url을 그 서버로 바꾼다.
 * 실제 DartApiClient · NaverNewsApiClient · EcosApiClient와 고유번호 캐시는 그대로 거친다. 데이터는 데모 시드가 등록한다.
 * 키는 application-fake.yml의 fake-only-* 더미 값을 그대로 받아 서버가 같은 값을 기대하게 한다(yml 한 곳에서만 정한다).
 * @TestConfiguration이라 기존 @SpringBootTest 스캔에 섞이지 않는다(FakeProfileIsolationTest) — FakeAssetoryApplication이 명시적으로 붙인다
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEvidenceConfig {

    @Bean(destroyMethod = "stop")
    FakeDartServer fakeDartServer(@Value("${assetory.dart.api-key}") String apiKey) {
        return new FakeDartServer(apiKey);
    }

    @Bean(destroyMethod = "stop")
    FakeNaverNewsServer fakeNaverNewsServer(@Value("${assetory.naver.client-id}") String clientId,
                                            @Value("${assetory.naver.client-secret}") String clientSecret) {
        return new FakeNaverNewsServer(clientId, clientSecret);
    }

    @Bean(destroyMethod = "stop")
    FakeEcosServer fakeEcosServer(@Value("${assetory.ecos.api-key}") String apiKey) {
        return new FakeEcosServer(apiKey);
    }

    @Bean
    DynamicPropertyRegistrar fakeEvidenceBaseUrls(FakeDartServer dart, FakeNaverNewsServer naver, FakeEcosServer ecos) {
        return registry -> {
            registry.add("assetory.dart.base-url", dart::baseUrl);
            registry.add("assetory.naver.base-url", naver::baseUrl);
            registry.add("assetory.ecos.base-url", ecos::baseUrl);
        };
    }
}
