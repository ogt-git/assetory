package com.project.assetory.fake;

import com.project.assetory.AssetoryApplication;
import org.springframework.boot.SpringApplication;

/**
 * fake 프로필 진입점 — 로컬 화면 확인 전용. `./gradlew bootTestRun`으로 띄운다(Docker 필요, 포트 8080).
 * 원래 애플리케이션에 가짜 인프라 · 한투 · CODEF 설정을 붙인다. 테스트 소스에만 있어 운영 jar에 포함되지 않는다.
 * fake 프로필만 켜므로 기본 프로필(local)과 application-local.yml은 읽지 않는다. 사용법은 docs/ops/fake-profile.md
 */
public class FakeAssetoryApplication {

    public static void main(String[] args) {
        SpringApplication.from(AssetoryApplication::main)
                .with(FakeInfraConfig.class, FakeKisConfig.class, FakeCodefConfig.class, FakeSeedConfig.class)
                .withAdditionalProfiles("fake")
                .run(args);
    }
}
