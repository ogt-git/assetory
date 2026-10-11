package com.project.assetory.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.assetory.connect.domain.ConnectedAccount;
import com.project.assetory.connect.domain.ConnectedAccountStatus;
import com.project.assetory.connect.repository.ConnectedAccountRepository;
import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConnectIntegrationTest extends IntegrationTestSupport {

    private static final String SAMSUNG = "0240";   // 삼성증권 (SECURITIES)
    private static final String KB_BANK = "0004";   // 국민은행 (BANK)

    @Autowired ConnectedAccountRepository connectedAccountRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private String lastIssuedConnectedId;

    @BeforeEach
    void stubCodef() throws Exception {
        when(codefApiClient.encryptRSA(anyString())).thenReturn("rsa-encrypted");
        when(codefApiClient.createAccount(any())).thenAnswer(inv -> {
            lastIssuedConnectedId = "cid-" + UUID.randomUUID();
            return codefResponse("CF-00000", "성공", lastIssuedConnectedId);
        });
        when(codefApiClient.deleteAccount(any())).thenReturn(codefResponse("CF-00000", "성공", null));
    }

    @Test
    @DisplayName("연결 성공 → ACTIVE 저장, connected_id_enc는 평문이 아니고 응답에도 connectedId 없음")
    void connect_storesEncryptedConnectedId() throws Exception {
        String at = signupAndGetAccessToken();

        String body = connect(at, SAMSUNG, "ST").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.institutionName").value("삼성증권"))
                .andExpect(jsonPath("$.data.businessType").value("ST"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(lastIssuedConnectedId).doesNotContain("connectedId");

        long id = json(body).get("data").get("id").asLong();
        String stored = jdbcTemplate.queryForObject(
                "SELECT connected_id_enc FROM connected_accounts WHERE id = ?", String.class, id);
        assertThat(stored).isNotBlank().isNotEqualTo(lastIssuedConnectedId).doesNotContain("cid-");
        assertThat(connectedAccountRepository.findById(id).orElseThrow().getConnectedId())
                .isEqualTo(lastIssuedConnectedId);
    }

    @Test
    @DisplayName("같은 기관 재연결(해지 후) → 새 행을 만들지 않고 기존 행 갱신")
    void reconnect_reusesSameRow() throws Exception {
        String at = signupAndGetAccessToken();
        long firstId = idOf(connect(at, SAMSUNG, "ST").andExpect(status().isOk()));
        disconnect(at, firstId).andExpect(status().isOk());

        long secondId = idOf(connect(at, SAMSUNG, "ST").andExpect(status().isOk()));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(rowCount(firstId)).isEqualTo(1);
        ConnectedAccount row = connectedAccountRepository.findById(firstId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ConnectedAccountStatus.ACTIVE);
        assertThat(row.getConnectedId()).isEqualTo(lastIssuedConnectedId);
    }

    @Test
    @DisplayName("이미 ACTIVE인 기관을 다시 연결 → 409, CODEF 호출 안 함")
    void alreadyActive_conflict() throws Exception {
        String at = signupAndGetAccessToken();
        connect(at, SAMSUNG, "ST").andExpect(status().isOk());

        connect(at, SAMSUNG, "ST")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CODEF_ALREADY_CONNECTED"));
        verify(codefApiClient, times(1)).createAccount(any());
    }

    @Test
    @DisplayName("재인증(AUTH_FAILED) → 같은 행을 ACTIVE로, 이전 connectedId는 CODEF에서 삭제")
    void reauth_reusesRowAndDeletesOldConnectedId() throws Exception {
        String at = signupAndGetAccessToken();
        long id = idOf(connect(at, SAMSUNG, "ST").andExpect(status().isOk()));
        String oldConnectedId = lastIssuedConnectedId;
        ConnectedAccount row = connectedAccountRepository.findById(id).orElseThrow();
        row.changeStatus(ConnectedAccountStatus.AUTH_FAILED);
        connectedAccountRepository.save(row);

        assertThat(idOf(connect(at, SAMSUNG, "ST").andExpect(status().isOk()))).isEqualTo(id);

        assertThat(deletedConnectedIds()).containsExactly(oldConnectedId);
        assertThat(connectedAccountRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(ConnectedAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("연동 해지 → CODEF 삭제 호출, DISCONNECTED, connected_id_enc NULL로 파기, 목록에서 제외")
    void disconnect_destroysConnectedId() throws Exception {
        String at = signupAndGetAccessToken();
        long id = idOf(connect(at, SAMSUNG, "ST").andExpect(status().isOk()));
        String connectedId = lastIssuedConnectedId;

        disconnect(at, id).andExpect(status().isOk());

        assertThat(deletedConnectedIds()).containsExactly(connectedId);
        Map<String, Object> raw = jdbcTemplate.queryForMap(
                "SELECT connected_id_enc, status FROM connected_accounts WHERE id = ?", id);
        assertThat(raw.get("connected_id_enc")).isNull();
        assertThat(raw.get("status")).isEqualTo("DISCONNECTED");
        mockMvc.perform(get("/api/codef/connections").header("Authorization", "Bearer " + at))
                .andExpect(jsonPath("$.data.length()").value(0));

        disconnect(at, id).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 사용자의 연동 계정은 해지·조회할 수 없다")
    void otherUser_blocked() throws Exception {
        String owner = signupAndGetAccessToken();
        String other = signupAndGetAccessToken();
        long id = idOf(connect(owner, SAMSUNG, "ST").andExpect(status().isOk()));

        disconnect(other, id).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/codef/connections").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.data.length()").value(0));

        assertThat(connectedAccountRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(ConnectedAccountStatus.ACTIVE);
        verify(codefApiClient, never()).deleteAccount(any());
    }

    @Test
    @DisplayName("은행(BK) 연결 가능 — clientType P로 요청. 업무 구분과 기관 유형이 다르면 400")
    void bankConnect() throws Exception {
        String at = signupAndGetAccessToken();

        connect(at, KB_BANK, "BK").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessType").value("BK"));
        ArgumentCaptor<HashMap<String, Object>> params = paramsCaptor();
        verify(codefApiClient).createAccount(params.capture());
        assertThat(firstAccount(params.getValue())).containsEntry("businessType", "BK").containsEntry("clientType", "P");

        connect(at, SAMSUNG, "BK").andExpect(status().isBadRequest());
        connect(at, KB_BANK, "ST").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CODEF 실패 응답 → 유형별 사용자 메시지·유형 이름, CODEF 원문은 노출 안 함, 행 생성 안 함")
    void codefFailure_userMessage() throws Exception {
        String at = signupAndGetAccessToken();
        when(codefApiClient.createAccount(any()))
                .thenReturn(codefResponse("CF-12800", "기관 원문 오류 메시지", null));

        String body = connect(at, SAMSUNG, "ST")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("AUTH_UNRECOVERABLE"))
                .andExpect(jsonPath("$.message").value(
                        "금융기관 아이디·비밀번호가 맞지 않거나 계정이 잠겼습니다. 금융기관에서 확인한 뒤 다시 연결해주세요."))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("기관 원문 오류 메시지").doesNotContain("CF-12800");

        mockMvc.perform(get("/api/codef/connections").header("Authorization", "Bearer " + at))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("CODEF 실패 원문(계좌번호 포함)은 로그에도 남지 않는다 — 코드만 기록 (NFR-SE-07)")
    void codefFailure_rawMessageNotLogged(CapturedOutput output) throws Exception {
        String at = signupAndGetAccessToken();
        when(codefApiClient.createAccount(any()))
                .thenReturn(codefResponse("CF-12800", "계좌 98765432101 비밀번호 오류", null));

        connect(at, SAMSUNG, "ST").andExpect(status().isBadGateway());

        assertThat(output.getAll()).contains("CF-12800")
                .doesNotContain("98765432101").doesNotContain("비밀번호 오류");
    }

    @Test
    @DisplayName("통신 실패·타임아웃(CF-09980) → NETWORK_ERROR 메시지")
    void networkFailure() throws Exception {
        String at = signupAndGetAccessToken();
        when(codefApiClient.createAccount(any())).thenReturn(codefResponse("CF-09980", "Read timed out", null));

        connect(at, SAMSUNG, "ST")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("NETWORK_ERROR"));
    }

    @Test
    @DisplayName("증권(ST) 연결은 clientType A(통합)로 요청 — CODEF 증권 API는 개인·법인 구분 없음")
    void securitiesConnect_usesClientTypeA() throws Exception {
        String at = signupAndGetAccessToken();

        connect(at, SAMSUNG, "ST").andExpect(status().isOk());

        ArgumentCaptor<HashMap<String, Object>> params = paramsCaptor();
        verify(codefApiClient).createAccount(params.capture());
        assertThat(firstAccount(params.getValue()))
                .containsEntry("businessType", "ST")
                .containsEntry("clientType", "A")
                .containsEntry("organization", SAMSUNG);
    }

    @ParameterizedTest(name = "{0} + ID/PW → 422")
    @ValueSource(strings = {"0225", "0243", "0262", "0264", "0270", "0279", "0287"})
    @DisplayName("ID/PW 방식 미지원 증권사(CODEF 목록 내 코드) → CODEF 호출 전 422")
    void idPwUnsupportedSecurities_blocked(String org) throws Exception {
        assertThat(InstitutionSeedTest.CODEF_SECURITIES).containsKey(org);
        String at = signupAndGetAccessToken();

        connect(at, org, "ST")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CODEF_UNSUPPORTED_OPERATION"));
        verify(codefApiClient, never()).createAccount(any());
    }

    @Test
    @DisplayName("인증서 방식 미지원 증권사(0227 다올투자증권) → CODEF 호출 전 422")
    void certUnsupportedSecurities_blocked() throws Exception {
        assertThat(InstitutionSeedTest.CODEF_SECURITIES).containsKey("0227");
        String at = signupAndGetAccessToken();

        mockMvc.perform(multipart("/api/codef/connect")
                        .file(new MockMultipartFile("derFile", "signCert.der", "application/octet-stream", new byte[]{1}))
                        .file(new MockMultipartFile("keyFile", "signPri.key", "application/octet-stream", new byte[]{2}))
                        .param("organization", "0227")
                        .param("businessType", "ST")
                        .param("loginType", "0")
                        .param("password", "cert-password")
                        .header("Authorization", "Bearer " + at))
                .andExpect(status().isUnprocessableEntity());
        verify(codefApiClient, never()).createAccount(any());
    }

    @Test
    @DisplayName("증권 연결 → assets 1행(SECURITIES·CASH, 계좌번호 암호화·마스킹, balance는 수집 전이라 NULL), 계좌 비밀번호는 암호화 저장")
    void securitiesConnect_createsAssetRow() throws Exception {
        String at = signupAndGetAccessToken();
        long id = idOf(connect(at, SAMSUNG, "ST").andExpect(status().isOk()));

        Map<String, Object> asset = jdbcTemplate.queryForMap(
                "SELECT asset_type, asset_class, name, account_number_enc, account_number_masked, balance, is_active " +
                "FROM assets WHERE connected_account_id = ?", id);
        assertThat(asset.get("asset_type")).isEqualTo("SECURITIES");
        assertThat(asset.get("asset_class")).isEqualTo("CASH");
        assertThat(asset.get("name")).isEqualTo("삼성증권");
        assertThat((String) asset.get("account_number_enc")).isNotBlank().doesNotContain("12345678901");
        assertThat(asset.get("account_number_masked")).isEqualTo("123*******8901");
        assertThat(asset.get("balance")).isNull();
        assertThat(asset.get("is_active")).isIn(true, 1, (byte) 1);

        String password = jdbcTemplate.queryForObject(
                "SELECT account_password_enc FROM connected_accounts WHERE id = ?", String.class, id);
        assertThat(password).isNotBlank().isNotEqualTo("4321");
    }

    @Test
    @DisplayName("증권 연결에 계좌번호가 없으면 400, CODEF 호출 안 함")
    void securitiesConnect_requiresAccountNumber() throws Exception {
        String at = signupAndGetAccessToken();

        mockMvc.perform(multipart("/api/codef/connect")
                        .param("organization", SAMSUNG).param("businessType", "ST").param("loginType", "1")
                        .param("id", "bank-login-id").param("password", "bank-login-password")
                        .header("Authorization", "Bearer " + at))
                .andExpect(status().isBadRequest());
        verify(codefApiClient, never()).createAccount(any());
    }

    @Test
    @DisplayName("해지 → 자산 행은 남기고 비활성화, 보유 종목 삭제 (D-21)")
    void disconnect_deactivatesAssetAndDeletesHoldings() throws Exception {
        String at = signupAndGetAccessToken();
        long id = idOf(connect(at, SAMSUNG, "ST").andExpect(status().isOk()));
        Long assetId = jdbcTemplate.queryForObject("SELECT id FROM assets WHERE connected_account_id = ?", Long.class, id);
        jdbcTemplate.update("INSERT INTO holdings (asset_id, ticker, quantity) VALUES (?, '005930', 1)", assetId);

        disconnect(at, id).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM assets WHERE id = ?", Boolean.class, assetId)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holdings WHERE asset_id = ?", Integer.class, assetId)).isZero();
    }

    // ---------- helpers ----------

    private ResultActions connect(String accessToken, String organization, String businessType) throws Exception {
        return mockMvc.perform(multipart("/api/codef/connect")
                .param("organization", organization)
                .param("businessType", businessType)
                .param("loginType", "1")
                .param("id", "bank-login-id")
                .param("password", "bank-login-password")
                .param("accountNumber", "ST".equals(businessType) ? "123-45-678901" : "")
                .param("accountPassword", "ST".equals(businessType) ? "4321" : "")
                .header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions disconnect(String accessToken, long id) throws Exception {
        return mockMvc.perform(delete("/api/codef/connect/" + id).header("Authorization", "Bearer " + accessToken));
    }

    private long idOf(ResultActions result) throws Exception {
        return json(result.andReturn().getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private int rowCount(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM connected_accounts c JOIN connected_accounts t " +
                "ON c.user_id = t.user_id AND c.institution_id = t.institution_id AND c.business_type = t.business_type " +
                "WHERE t.id = ?", Integer.class, id);
    }

    private List<Object> deletedConnectedIds() throws Exception {
        ArgumentCaptor<HashMap<String, Object>> params = paramsCaptor();
        verify(codefApiClient, org.mockito.Mockito.atLeastOnce()).deleteAccount(params.capture());
        return params.getAllValues().stream().map(p -> firstAccount(p).get("connectedId")).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<HashMap<String, Object>> paramsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(HashMap.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstAccount(HashMap<String, Object> params) {
        return ((List<Map<String, Object>>) params.get("accountList")).get(0);
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String codefResponse(String code, String message, String connectedId) throws Exception {
        Map<String, Object> data = connectedId == null ? Map.of() : Map.of("connectedId", connectedId);
        return objectMapper.writeValueAsString(Map.of(
                "result", Map.of("code", code, "message", message),
                "data", data));
    }
}
