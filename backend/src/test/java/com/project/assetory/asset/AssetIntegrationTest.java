package com.project.assetory.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.assetory.asset.domain.Asset;
import com.project.assetory.asset.domain.Holding;
import com.project.assetory.asset.repository.AssetRepository;
import com.project.assetory.asset.repository.DepositDetailRepository;
import com.project.assetory.asset.repository.HoldingRepository;
import com.project.assetory.connect.domain.BusinessType;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.connect.repository.InstitutionRepository;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.domain.User;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetIntegrationTest extends IntegrationTestSupport {

    private static final String ACCOUNT_NUMBER = "12345678901";

    @Autowired UserRepository userRepository;
    @Autowired InstitutionRepository institutionRepository;
    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired DepositDetailRepository depositDetailRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private Login me;
    private Login other;

    @BeforeEach
    void setUp() throws Exception {
        me = newLogin();
        other = newLogin();
    }

    // ---------- 수기 자산 ----------

    @Test
    @DisplayName("예금 생성 → 201, asset_class DEPOSIT, 예적금 상세 저장, 기관·연동 없음")
    void createDeposit() throws Exception {
        String body = create(me, deposit("DEPOSIT", "정기예금", "10000000")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assetType").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.assetClass").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.balance").value(10000000))
                .andExpect(jsonPath("$.data.depositDetail.interestRate").value(3.5))
                .andExpect(jsonPath("$.data.depositDetail.maturityDate").value("2027-01-01"))
                .andReturn().getResponse().getContentAsString();

        long id = json(body).get("data").get("id").asLong();
        Asset asset = assetRepository.findById(id).orElseThrow();
        assertThat(asset.isManual()).isTrue();
        assertThat(asset.getInstitution()).isNull();
        assertThat(depositDetailRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("적금 생성 → asset_class DEPOSIT, 현금 생성 → asset_class CASH·상세 없음")
    void createSavingsAndCash() throws Exception {
        create(me, deposit("SAVINGS", "자유적금", "1200000")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assetClass").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.depositDetail.saveTerm").value(12));

        String body = create(me, cash("비상금", "500000")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assetClass").value("CASH"))
                .andExpect(jsonPath("$.data.depositDetail").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(depositDetailRepository.findById(json(body).get("data").get("id").asLong())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPOSIT", "SAVINGS"})
    @DisplayName("예금·적금은 예적금 상세가 없으면 400")
    void depositDetailRequired(String type) throws Exception {
        Map<String, Object> request = deposit(type, "예금", "1000000");
        request.remove("depositDetail");

        create(me, request).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("현금에 예적금 상세를 보내면 400")
    void cashRejectsDetail() throws Exception {
        Map<String, Object> request = deposit("CASH", "현금", "1000");

        create(me, request).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHECKING", "SECURITIES", "REAL_ESTATE"})
    @DisplayName("허용하지 않는 유형(D-19 외)은 400")
    void rejectsOtherTypes(String type) throws Exception {
        create(me, cash("자산", "1000", type)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("만기일이 시작일보다 뒤가 아니면 400")
    void maturityAfterStart() throws Exception {
        Map<String, Object> request = deposit("DEPOSIT", "예금", "1000000");
        detail(request).put("maturityDate", "2026-01-01"); // 시작일과 같음

        create(me, request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("만기일은 시작일보다 뒤여야 합니다."));
    }

    @Test
    @DisplayName("금액·원금은 0보다 커야 하고 금리는 0 이상이어야 한다")
    void amountValidation() throws Exception {
        create(me, cash("현금", "0")).andExpect(status().isBadRequest());

        Map<String, Object> zeroPrincipal = deposit("DEPOSIT", "예금", "1000000");
        detail(zeroPrincipal).put("principal", "0");
        create(me, zeroPrincipal).andExpect(status().isBadRequest());

        Map<String, Object> negativeRate = deposit("DEPOSIT", "예금", "1000000");
        detail(negativeRate).put("interestRate", "-0.1");
        create(me, negativeRate).andExpect(status().isBadRequest());

        Map<String, Object> zeroRate = deposit("DEPOSIT", "예금", "1000000");
        detail(zeroRate).put("interestRate", "0");
        create(me, zeroRate).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("수정 — 예금 → 현금으로 바꾸면 예적금 상세가 지워진다")
    void updateChangesType() throws Exception {
        long id = createdId(me, deposit("DEPOSIT", "예금", "1000000"));

        update(me, id, cash("현금으로", "300000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetClass").value("CASH"))
                .andExpect(jsonPath("$.data.balance").value(300000));

        assertThat(depositDetailRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 자산은 수정·삭제할 수 없다 (404, 값 그대로)")
    void otherUserCannotModify() throws Exception {
        long id = createdId(me, cash("내 현금", "100000"));

        update(other, id, cash("탈취", "1")).andExpect(status().isNotFound());
        remove(other, id).andExpect(status().isNotFound());

        Asset asset = assetRepository.findById(id).orElseThrow();
        assertThat(asset.getName()).isEqualTo("내 현금");
        assertThat(asset.isActive()).isTrue();
    }

    @Test
    @DisplayName("연동 자산은 수기 API로 수정·삭제할 수 없다 (400)")
    void linkedAssetRejected() throws Exception {
        Long linkedAssetId = newSecuritiesAccount(me, "0240", ConnectedAccountStatus.ACTIVE, "1000", "50000");

        update(me, linkedAssetId, cash("변경", "1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE"));
        remove(me, linkedAssetId).andExpect(status().isBadRequest());

        assertThat(assetRepository.findById(linkedAssetId).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("삭제는 soft delete — 행은 남고(is_active=false) 목록·조회·수정에서 빠진다")
    void softDelete() throws Exception {
        long id = createdId(me, deposit("DEPOSIT", "예금", "1000000"));

        remove(me, id).andExpect(status().isOk());

        Asset asset = assetRepository.findById(id).orElseThrow();
        assertThat(asset.isActive()).isFalse();
        assertThat(depositDetailRepository.findById(id)).isPresent();
        authed(get("/api/assets/manual"), me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        authed(get("/api/assets"), me).andExpect(jsonPath("$.data.manualAssets.length()").value(0));
        update(me, id, cash("되살리기", "1")).andExpect(status().isNotFound());
        remove(me, id).andExpect(status().isNotFound());
    }

    // ---------- 통합 자산 조회 ----------

    @Test
    @DisplayName("증권 + 수기 통합 응답과 자산군별 합계 (SECURITIES=평가금액 합, CASH=예수금+현금, DEPOSIT=예적금)")
    void overviewTotals() throws Exception {
        newSecuritiesAccount(me, "0240", ConnectedAccountStatus.ACTIVE, "1500000", "1100000", "880000");
        create(me, deposit("DEPOSIT", "정기예금", "10000000")).andExpect(status().isCreated());
        create(me, deposit("SAVINGS", "적금", "1200000")).andExpect(status().isCreated());
        create(me, cash("비상금", "500000")).andExpect(status().isCreated());

        authed(get("/api/assets"), me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitiesAccounts.length()").value(1))
                .andExpect(jsonPath("$.data.securitiesAccounts[0].institutionName").value("삼성증권"))
                .andExpect(jsonPath("$.data.securitiesAccounts[0].depositReceived").value(1500000))
                .andExpect(jsonPath("$.data.securitiesAccounts[0].holdings.length()").value(2))
                .andExpect(jsonPath("$.data.securitiesAccounts[0].connectionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.manualAssets.length()").value(3))
                .andExpect(jsonPath("$.data.manualAssets[0].depositDetail.interestRate").value(3.5))
                .andExpect(jsonPath("$.data.totals.securities").value(1980000))
                .andExpect(jsonPath("$.data.totals.cash").value(2000000))
                .andExpect(jsonPath("$.data.totals.deposit").value(11200000))
                .andExpect(jsonPath("$.data.totals.total").value(15180000))
                .andExpect(jsonPath("$.data.totals.incomplete").value(false));
    }

    @Test
    @DisplayName("비활성(삭제·해지) 자산은 목록과 합계에서 빠진다")
    void overviewExcludesInactive() throws Exception {
        newSecuritiesAccount(me, "0240", ConnectedAccountStatus.ACTIVE, "1000", "2000");
        Long disconnected = newSecuritiesAccount(me, "0243", ConnectedAccountStatus.DISCONNECTED, "999999", "999999");
        transactionTemplate.executeWithoutResult(s -> assetRepository.findById(disconnected).orElseThrow().deactivate());
        long deleted = createdId(me, cash("삭제할 현금", "777777"));
        remove(me, deleted).andExpect(status().isOk());

        authed(get("/api/assets"), me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitiesAccounts.length()").value(1))
                .andExpect(jsonPath("$.data.manualAssets.length()").value(0))
                .andExpect(jsonPath("$.data.totals.total").value(3000));
    }

    @Test
    @DisplayName("해지 상태 연동의 자산은 is_active가 남아 있어도 보이지 않는다")
    void overviewExcludesDisconnectedEvenIfActiveRow() throws Exception {
        newSecuritiesAccount(me, "0243", ConnectedAccountStatus.DISCONNECTED, "5000", "5000");

        authed(get("/api/assets"), me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitiesAccounts.length()").value(0))
                .andExpect(jsonPath("$.data.totals.total").value(0));
    }

    @Test
    @DisplayName("계좌번호 원문은 응답에 없고 마스킹 값만 있다")
    void overviewMasksAccountNumber() throws Exception {
        newSecuritiesAccount(me, "0240", ConnectedAccountStatus.ACTIVE, "1000", "2000");

        String body = authed(get("/api/assets"), me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitiesAccounts[0].accountNumberMasked").value("123*******8901"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ACCOUNT_NUMBER).doesNotContain("connectedId").doesNotContain("cid-");
    }

    @Test
    @DisplayName("다른 사용자의 자산은 보이지 않는다")
    void overviewOwnerOnly() throws Exception {
        newSecuritiesAccount(other, "0240", ConnectedAccountStatus.ACTIVE, "1000", "2000");
        create(other, cash("남의 현금", "100")).andExpect(status().isCreated());
        create(me, cash("내 현금", "300")).andExpect(status().isCreated());

        authed(get("/api/assets"), me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitiesAccounts.length()").value(0))
                .andExpect(jsonPath("$.data.manualAssets.length()").value(1))
                .andExpect(jsonPath("$.data.manualAssets[0].name").value("내 현금"))
                .andExpect(jsonPath("$.data.totals.total").value(300));
        authed(get("/api/assets/manual"), me).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("첫 수집 전(예수금 null)이면 0으로 채우지 않고 incomplete로 표시")
    void overviewIncompleteBeforeFirstSync() throws Exception {
        newSecuritiesAccount(me, "0240", ConnectedAccountStatus.ACTIVE, null);

        authed(get("/api/assets"), me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.securitiesAccounts[0].depositReceived").doesNotExist())
                .andExpect(jsonPath("$.data.totals.total").value(0))
                .andExpect(jsonPath("$.data.totals.incomplete").value(true));
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/assets")).andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private record Login(User user, String accessToken) {}

    private Login newLogin() throws Exception {
        String email = signupNew();
        User user = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow();
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Login(user, json(body).get("data").get("accessToken").asText());
    }

    // 증권 계좌 행 + 보유 종목(평가금액들). balance가 null이면 첫 수집 전
    private Long newSecuritiesAccount(Login owner, String orgCode, ConnectedAccountStatus status, String balance,
                                      String... evalAmounts) {
        return transactionTemplate.execute(s -> {
            var institution = institutionRepository.findByCodefOrgCode(orgCode).orElseThrow();
            ConnectedAccount account = connectedAccountRepository.save(
                    ConnectedAccount.create(owner.user(), institution, "cid-" + UUID.randomUUID(), BusinessType.ST));
            account.changeStatus(status);
            Asset asset = assetRepository.save(Asset.securitiesAccount(owner.user(), account, institution, ACCOUNT_NUMBER));
            if (balance != null) asset.updateBalance(new BigDecimal(balance));
            for (int i = 0; i < evalAmounts.length; i++) {
                Holding holding = Holding.create(asset, "00000" + i);
                holding.update("종목" + i, null, BigDecimal.TEN, null, null, new BigDecimal(evalAmounts[i]), null, null);
                holdingRepository.save(holding);
            }
            return asset.getId();
        });
    }

    private Map<String, Object> deposit(String type, String name, String balance) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("interestRate", "3.5");
        detail.put("startDate", "2026-01-01");
        detail.put("maturityDate", "2027-01-01");
        detail.put("principal", balance);
        detail.put("saveTerm", 12);
        detail.put("autoRenew", false);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("assetType", type);
        request.put("name", name);
        request.put("balance", balance);
        request.put("depositDetail", detail);
        return request;
    }

    private Map<String, Object> cash(String name, String balance) {
        return cash(name, balance, "CASH");
    }

    private Map<String, Object> cash(String name, String balance, String type) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("assetType", type);
        request.put("name", name);
        request.put("balance", balance);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(Map<String, Object> request) {
        return (Map<String, Object>) request.get("depositDetail");
    }

    private ResultActions create(Login login, Map<String, Object> body) throws Exception {
        return authed(post("/api/assets/manual").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)), login);
    }

    private long createdId(Login login, Map<String, Object> body) throws Exception {
        String response = create(login, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(response).get("data").get("id").asLong();
    }

    private ResultActions update(Login login, long id, Map<String, Object> body) throws Exception {
        return authed(put("/api/assets/manual/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)), login);
    }

    private ResultActions remove(Login login, long id) throws Exception {
        return authed(delete("/api/assets/manual/" + id), login);
    }

    private ResultActions authed(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                 Login login) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()));
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}
