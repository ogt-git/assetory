package com.project.assetory.e2e;

import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.AssetType;
import com.project.assetory.asset.domain.DepositDetail;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.DepositDetailRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.market.domain.StockPrice;
import com.project.assetory.market.repository.StockPriceRepository;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * E2E 시나리오 데이터 — 테스트 전용 더미 값만 쓴다(실제 계좌 · 키 · 이메일 아님).
 *
 * <pre>
 * U1 연동 + 수기  : ACTIVE 증권(예수금 1,000,000 · 005930×10 · 069500(ETF)×5 · 111111(거래정지)×3) + CASH 500,000 + DEPOSIT 2,000,000 → COMPLETE
 * U2 수기만       : CASH 300,000 (직전 COMPLETE 280,000) → COMPLETE, 전일 대비 +20,000
 * U3 인증 만료    : AUTH_FAILED 증권 + CASH 100,000 → PARTIAL (CODEF 호출 없음)
 * U4 오늘 실패    : ACTIVE 증권(어제 값 예수금 999,999 · 000660×4 가 DB에 남음, 오늘 CODEF 실패) + CASH 50,000 → PARTIAL, 어제 값 미사용
 * U5 해지만       : DISCONNECTED 증권 → 기록 없음
 * </pre>
 */
public final class E2eFixture {

    public static final String CODEF_ORG = "0240";
    // 테스트 전용 더미 계좌번호
    public static final String ACCOUNT_NUMBER = "12345678901";

    public record Linked(Long userId, Long accountId, Long securitiesAssetId, String connectedId) {}

    public record Seeded(Linked u1, Long u1CashId, Long u1DepositId,
                         Long u2, Long u2CashId,
                         Linked u3, Linked u4, Long u4CashId, Linked u5) {}

    private final Supplier<User> newUser;
    private final UserRepository userRepository;
    private final InstitutionRepository institutionRepository;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final AssetRepository assetRepository;
    private final DepositDetailRepository depositDetailRepository;
    private final StockPriceRepository stockPriceRepository;
    private final TransactionTemplate tx;

    public E2eFixture(Supplier<User> newUser, UserRepository userRepository, InstitutionRepository institutionRepository,
                      ConnectedAccountRepository connectedAccountRepository, AssetRepository assetRepository,
                      DepositDetailRepository depositDetailRepository, StockPriceRepository stockPriceRepository,
                      TransactionTemplate tx) {
        this.newUser = newUser;
        this.userRepository = userRepository;
        this.institutionRepository = institutionRepository;
        this.connectedAccountRepository = connectedAccountRepository;
        this.assetRepository = assetRepository;
        this.depositDetailRepository = depositDetailRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.tx = tx;
    }

    public Seeded seed() {
        Linked u1 = linked(ConnectedAccountStatus.ACTIVE);
        Long u1Cash = manual(u1.userId(), AssetType.CASH, "생활비 통장", "500000");
        Long u1Deposit = deposit(u1.userId(), "2000000");

        User u2 = newUser.get();
        Long u2Cash = manual(u2.getId(), AssetType.CASH, "비상금", "300000");

        Linked u3 = linked(ConnectedAccountStatus.AUTH_FAILED);
        manual(u3.userId(), AssetType.CASH, "현금", "100000");

        Linked u4 = linked(ConnectedAccountStatus.ACTIVE);
        Long u4Cash = manual(u4.userId(), AssetType.CASH, "현금", "50000");

        Linked u5 = linked(ConnectedAccountStatus.DISCONNECTED);

        return new Seeded(u1, u1Cash, u1Deposit, u2.getId(), u2Cash, u3, u4, u4Cash, u5);
    }

    // 지난 거래일에 수집해 둔 시세 — 거래정지 종목 평가에 쓰인다
    public void yesterdayPrice(String ticker, String name, LocalDate date, String close) {
        tx.executeWithoutResult(status -> {
            StockPrice price = StockPrice.create(ticker, date);
            price.update(name, new BigDecimal(close), new BigDecimal(close), BigDecimal.ZERO, 0L);
            stockPriceRepository.save(price);
        });
    }

    private Linked linked(ConnectedAccountStatus status) {
        User user = newUser.get();
        String connectedId = "e2e-cid-" + UUID.randomUUID();
        Long[] ids = tx.execute(s -> {
            var institution = institutionRepository.findByCodefOrgCode(CODEF_ORG).orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(user, institution, connectedId, BusinessType.ST));
            account.changeStatus(status);
            Asset asset = Asset.securitiesAccount(user, account, institution, ACCOUNT_NUMBER);
            if (status == ConnectedAccountStatus.DISCONNECTED) {
                asset.deactivate();
            }
            return new Long[]{account.getId(), assetRepository.save(asset).getId()};
        });
        return new Linked(user.getId(), ids[0], ids[1], connectedId);
    }

    private Long manual(Long userId, AssetType type, String name, String balance) {
        return tx.execute(s -> assetRepository.save(
                Asset.manual(userRepository.getReferenceById(userId), type, name, new BigDecimal(balance))).getId());
    }

    private Long deposit(Long userId, String balance) {
        return tx.execute(s -> {
            Asset asset = assetRepository.save(
                    Asset.manual(userRepository.getReferenceById(userId), AssetType.DEPOSIT, "정기예금", new BigDecimal(balance)));
            depositDetailRepository.save(DepositDetail.create(asset, new BigDecimal("3.500"),
                    LocalDate.of(2029, 7, 1), LocalDate.of(2030, 7, 1), new BigDecimal(balance), 12, false));
            return asset.getId();
        });
    }
}
