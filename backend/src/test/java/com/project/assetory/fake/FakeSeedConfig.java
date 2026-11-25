package com.project.assetory.fake;

import com.project.assetory.analysis.repository.StockEventAnalysisRepository;
import com.project.assetory.analysis.service.EvidenceCollectService;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.DepositDetailRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.detection.service.StockChangeDetectionService;
import com.project.assetory.e2e.FakeDartServer;
import com.project.assetory.e2e.FakeEcosServer;
import com.project.assetory.e2e.FakeKisServer;
import com.project.assetory.e2e.FakeNaverNewsServer;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.market.service.MacroIndicatorCollectService;
import com.project.assetory.snapshot.service.SnapshotSaveService;
import com.project.assetory.user.repository.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** fake 프로필 데모 데이터 — 앱이 뜬 직후 DemoDataSeeder가 한 번 실행된다 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeSeedConfig {

    @Bean
    DemoDataSeeder demoDataSeeder(Clock clock, FakeKisServer kisServer, FakeCodefApiClient codefApiClient,
                                  PasswordEncoder passwordEncoder, UserRepository userRepository,
                                  InstitutionRepository institutionRepository,
                                  ConnectedAccountRepository connectedAccountRepository, AssetRepository assetRepository,
                                  DepositDetailRepository depositDetailRepository, HoldingRepository holdingRepository,
                                  StockPriceRepository stockPriceRepository, SnapshotSaveService snapshotSaveService,
                                  StockChangeDetectionService stockChangeDetectionService, TransactionTemplate tx,
                                  FakeEcosServer ecosServer, FakeDartServer dartServer, FakeNaverNewsServer naverServer,
                                  MacroIndicatorCollectService macroIndicatorCollectService,
                                  EvidenceCollectService evidenceCollectService,
                                  StockEventAnalysisRepository analysisRepository) {
        // 근거 · 지수 데모(B5) — 빈이 아니라 시드 안에서만 쓰는 객체
        DemoEvidenceSeeder evidenceSeeder = new DemoEvidenceSeeder(kisServer, ecosServer, dartServer, naverServer,
                macroIndicatorCollectService, evidenceCollectService, analysisRepository);
        return new DemoDataSeeder(clock, kisServer, codefApiClient, passwordEncoder, userRepository,
                institutionRepository, connectedAccountRepository, assetRepository, depositDetailRepository,
                holdingRepository, stockPriceRepository, snapshotSaveService, stockChangeDetectionService, tx, evidenceSeeder);
    }
}
