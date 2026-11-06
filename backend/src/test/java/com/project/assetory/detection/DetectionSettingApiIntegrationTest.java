package com.project.assetory.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.assetory.global.crypto.HashUtil;
import com.project.assetory.global.exception.ErrorCode;
import com.project.assetory.support.IntegrationTestSupport;
import com.project.assetory.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET · PUT · DELETE /api/detection-settings (FR-DT-02 · UC-07) — 응답 구조 · 인증 · 오류 응답 · 토큰 주인 데이터만
class DetectionSettingApiIntegrationTest extends IntegrationTestSupport {

    private static final String BASE = "/api/detection-settings";
    private static final List<String> FIELDS = List.of("ruleCode", "name", "triggerType", "unit", "defaultThreshold",
            "minValue", "maxValue", "userConfigurable", "thresholdValue", "appliedThreshold", "enabled",
            "excludedTickers", "customized");

    @Autowired UserRepository userRepository;

    private record Login(Long userId, String accessToken) {}

    private Login login() throws Exception {
        String email = signupNew();
        Long userId = userRepository.findByEmailHash(HashUtil.sha256Base64(email)).orElseThrow().getId();
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Login(userId, objectMapper.readTree(body).get("data").get("accessToken").asText());
    }

    @Test
    @DisplayName("GET 200 — 활성 규칙 6개, 필드 전부, 기본값")
    void get_structure() throws Exception {
        JsonNode data = okData(authed(get(BASE), login()));

        assertThat(data.size()).isEqualTo(6);
        JsonNode stock = data.get(0);
        assertThat(stock.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(FIELDS);
        assertThat(stock.get("ruleCode").asText()).isEqualTo("STOCK_PRICE_CHANGE");
        assertThat(stock.get("triggerType").asText()).isEqualTo("STOCK_CHANGE");
        assertThat(stock.get("unit").asText()).isEqualTo("%");
        assertThat(stock.get("appliedThreshold").decimalValue()).isEqualByComparingTo("5");
        assertThat(stock.get("thresholdValue").isNull()).isTrue();
        assertThat(stock.get("enabled").asBoolean()).isTrue();
        assertThat(stock.get("excludedTickers").isArray()).isTrue();
        assertThat(stock.get("excludedTickers").size()).isZero();
        assertThat(stock.get("customized").asBoolean()).isFalse();
        assertThat(data.get(5).get("appliedThreshold").isNull()).isTrue();
    }

    @Test
    @DisplayName("PUT 200 — 저장한 값이 응답과 이후 GET에 반영")
    void put_saves() throws Exception {
        Login login = login();

        JsonNode saved = okData(putJson(login, "STOCK_PRICE_CHANGE",
                body(true, "8", List.of("005930", "005930"))));

        assertThat(saved.get("appliedThreshold").decimalValue()).isEqualByComparingTo("8");
        assertThat(saved.get("customized").asBoolean()).isTrue();
        assertThat(saved.get("excludedTickers").toString()).isEqualTo("[\"005930\"]");
        assertThat(okData(authed(get(BASE), login)).get(0).get("appliedThreshold").decimalValue())
                .isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("DELETE 200 — 기본값 응답, 없는 설정을 지워도 200")
    void delete_resets() throws Exception {
        Login login = login();
        okData(putJson(login, "STOCK_PRICE_CHANGE", body(false, "8", null)));

        JsonNode reset = okData(authed(delete(BASE + "/STOCK_PRICE_CHANGE"), login));

        assertThat(reset.get("customized").asBoolean()).isFalse();
        assertThat(reset.get("enabled").asBoolean()).isTrue();
        assertThat(reset.get("appliedThreshold").decimalValue()).isEqualByComparingTo("5");
        okData(authed(delete(BASE + "/STOCK_PRICE_CHANGE"), login));
    }

    @Test
    @DisplayName("토큰이 없으면 GET · PUT · DELETE 모두 401")
    void noToken_unauthorized() throws Exception {
        assertError(mockMvc.perform(get(BASE)), 401, ErrorCode.AUTH_FAILED.name());
        assertError(mockMvc.perform(put(BASE + "/STOCK_PRICE_CHANGE").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(true, "8", null)))), 401, ErrorCode.AUTH_FAILED.name());
        assertError(mockMvc.perform(delete(BASE + "/STOCK_PRICE_CHANGE")), 401, ErrorCode.AUTH_FAILED.name());
    }

    @Test
    @DisplayName("모르는 ruleCode는 PUT · DELETE 모두 404 DETECTION_RULE_NOT_FOUND")
    void unknownRule_notFound() throws Exception {
        Login login = login();

        JsonNode put = assertError(putJson(login, "NO_SUCH_RULE", body(true, null, null)),
                404, ErrorCode.DETECTION_RULE_NOT_FOUND.name());
        assertThat(put.get("message").asText()).isEqualTo(ErrorCode.DETECTION_RULE_NOT_FOUND.getMessage());
        assertError(authed(delete(BASE + "/NO_SUCH_RULE"), login), 404, ErrorCode.DETECTION_RULE_NOT_FOUND.name());
    }

    @Test
    @DisplayName("범위 밖 값은 400 — data에 허용 범위, 문구에 입력값 없음")
    void outOfRange_badRequestWithRange() throws Exception {
        JsonNode root = assertError(putJson(login(), "STOCK_PRICE_CHANGE", body(true, "20.0001", null)),
                400, ErrorCode.THRESHOLD_OUT_OF_RANGE.name());

        assertThat(root.get("message").asText()).isEqualTo(ErrorCode.THRESHOLD_OUT_OF_RANGE.getMessage())
                .doesNotContain("20.0001");
        JsonNode data = root.get("data");
        assertThat(data.get("minValue").decimalValue()).isEqualByComparingTo("2");
        assertThat(data.get("maxValue").decimalValue()).isEqualByComparingTo("20");
        assertThat(data.get("unit").asText()).isEqualTo("%");
    }

    @Test
    @DisplayName("조정 불가 규칙에 임계치 · 비대상 규칙에 제외 종목은 400 (data 없음)")
    void ruleMismatch_badRequest() throws Exception {
        Login login = login();

        JsonNode notConfigurable = assertError(putJson(login, "MONTHLY_REPORT", body(true, "5", null)),
                400, ErrorCode.THRESHOLD_NOT_CONFIGURABLE.name());
        assertThat(notConfigurable.get("data").isNull()).isTrue();
        assertError(putJson(login, "TOTAL_ASSET_CHANGE", body(true, null, List.of("005930"))),
                400, ErrorCode.TARGET_SCOPE_NOT_SUPPORTED.name());
    }

    @Test
    @DisplayName("요청 형식 오류는 400 VALIDATION_ERROR — 종목코드 형식 · 개수 상한(설정값) · 수신 여부 누락 · 소수 5자리")
    void requestValidation() throws Exception {
        Login login = login();
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) tooMany.add(String.format("%06d", i));

        assertError(putJson(login, "STOCK_PRICE_CHANGE", body(true, null, List.of("abcdef"))), 400, "VALIDATION_ERROR");
        assertError(putJson(login, "STOCK_PRICE_CHANGE", body(true, null, tooMany)), 400, "VALIDATION_ERROR");
        assertError(putJson(login, "STOCK_PRICE_CHANGE", body(null, null, null)), 400, "VALIDATION_ERROR");
        assertError(putJson(login, "STOCK_PRICE_CHANGE", body(true, "8.12345", null)), 400, "VALIDATION_ERROR");
        // 거부된 요청은 행을 만들지 않는다
        assertThat(okData(authed(get(BASE), login)).get(0).get("customized").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("토큰 주인의 설정만 조회 · 변경된다")
    void ownerOnly() throws Exception {
        Login a = login();
        Login b = login();
        okData(putJson(a, "STOCK_PRICE_CHANGE", body(true, "12", List.of("000660"))));

        JsonNode bStock = okData(authed(get(BASE), b)).get(0);
        assertThat(bStock.get("customized").asBoolean()).isFalse();
        okData(authed(delete(BASE + "/STOCK_PRICE_CHANGE"), b));

        JsonNode aStock = okData(authed(get(BASE), a)).get(0);
        assertThat(aStock.get("appliedThreshold").decimalValue()).isEqualByComparingTo("12");
        assertThat(aStock.get("excludedTickers").toString()).isEqualTo("[\"000660\"]");
    }

    private Map<String, Object> body(Boolean enabled, String threshold, List<String> tickers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("thresholdValue", threshold == null ? null : new java.math.BigDecimal(threshold));
        body.put("excludedTickers", tickers);
        return body;
    }

    private ResultActions putJson(Login login, String ruleCode, Map<String, Object> body) throws Exception {
        return authed(put(BASE + "/" + ruleCode).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)), login);
    }

    private ResultActions authed(MockHttpServletRequestBuilder request, Login login) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()));
    }

    private JsonNode okData(ResultActions result) throws Exception {
        JsonNode root = objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private JsonNode assertError(ResultActions result, int status, String errorCode) throws Exception {
        JsonNode root = objectMapper.readTree(result.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
        assertThat(root.get("success").asBoolean()).isFalse();
        assertThat(root.get("errorCode").asText()).isEqualTo(errorCode);
        return root;
    }
}
