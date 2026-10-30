package com.project.assetory.fake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.connect.codef.CodefApiClient;
import io.codef.api.EasyCodef;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 가짜 CODEF 클라이언트 — fake 프로필 전용. 외부 호출 없이 고정 응답을 준다. 응답 필드명은 E2E 픽스처(e2e/codef)와 같고 값은 모두 더미다.
 *
 * <ul>
 *   <li>연동(createAccount): 항상 성공, 새 더미 connectedId 발급</li>
 *   <li>보유 조회(requestProduct): connectedId별 등록 응답 → 없으면 기본 포트폴리오</li>
 *   <li>인증 실패로 등록한 connectedId: 금융사 비밀번호 오류(복구 불가 인증 오류) 응답</li>
 * </ul>
 */
public class FakeCodefApiClient extends CodefApiClient {

    // 금융사 비밀번호 오류 — CodefErrorClassifier에서 복구 불가 인증 오류로 분류된다
    static final String AUTH_FAILURE_CODE = "CF-12800";
    // 테스트 전용 더미 계좌번호
    static final String DUMMY_ACCOUNT_NUMBER = "00000000000";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 보유 종목 1개 — 평가금액 등 CODEF 값은 임시값이고 스냅샷은 한투 종가로 다시 계산한다 */
    public record Item(String ticker, String name, BigDecimal quantity, BigDecimal presentPrice, BigDecimal averagePrice) {}

    // 연동 직후 처음 보이는 포트폴리오 — 화면에서 새로 연동한 계좌용
    private static final String DEFAULT_PORTFOLIO = portfolio(new BigDecimal("1000000"), List.of(
            new Item("005930", "삼성전자", new BigDecimal("10"), new BigDecimal("70000"), new BigDecimal("65000")),
            new Item("069500", "KODEX 200", new BigDecimal("5"), new BigDecimal("35000"), new BigDecimal("32000"))));

    private final Map<String, String> responses = new ConcurrentHashMap<>();

    public FakeCodefApiClient(EasyCodef easyCodef) {
        super(easyCodef);
    }

    /** connectedId의 보유 조회 응답을 등록한다 */
    public void respond(String connectedId, String json) {
        responses.put(connectedId, json);
    }

    /** connectedId의 보유 조회를 인증 실패로 만든다 */
    public void authFailure(String connectedId) {
        responses.put(connectedId, result(AUTH_FAILURE_CODE, "테스트 전용 인증 실패", Map.of()));
    }

    @Override
    public String encryptRSA(String plainText) {
        return "fake-rsa";
    }

    @Override
    public String createAccount(HashMap<String, Object> accountMap) {
        return result("CF-00000", "성공", Map.of("connectedId", "fake-cid-" + UUID.randomUUID()));
    }

    @Override
    public String deleteAccount(HashMap<String, Object> accountMap) {
        return result("CF-00000", "성공", Map.of("connectedId", String.valueOf(accountMap.get("connectedId"))));
    }

    @Override
    public String updateAccount(HashMap<String, Object> accountMap) {
        return result("CF-00000", "성공", Map.of("connectedId", String.valueOf(accountMap.get("connectedId"))));
    }

    @Override
    public String requestProduct(String productUrl, HashMap<String, Object> params) {
        return responses.getOrDefault(String.valueOf(params.get("connectedId")), DEFAULT_PORTFOLIO);
    }

    /** 증권 보유 조회 성공 응답 — 예수금 + 종목 목록 */
    public static String portfolio(BigDecimal deposit, List<Item> items) {
        List<Map<String, Object>> list = items.stream().map(FakeCodefApiClient::item).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resAccount", DUMMY_ACCOUNT_NUMBER);
        data.put("resDepositReceived", amount(deposit));
        data.put("resItemList", list);
        return result("CF-00000", "성공", data);
    }

    private static Map<String, Object> item(Item item) {
        BigDecimal valuation = item.presentPrice().multiply(item.quantity());
        BigDecimal purchase = item.averagePrice().multiply(item.quantity());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resProductType", "주식");
        map.put("resItemName", item.name());
        map.put("resItemCode", item.ticker());
        map.put("resQuantity", item.quantity().toPlainString());
        map.put("resPresentAmt", amount(item.presentPrice()));
        map.put("resPurchaseAmount", amount(purchase));
        map.put("resValuationAmt", amount(valuation));
        map.put("resValuationPL", amount(valuation.subtract(purchase)));
        map.put("resAccountCurrency", "KRW");
        return map;
    }

    private static String amount(BigDecimal value) {
        return new DecimalFormat("#,##0").format(value);
    }

    private static String result(String code, String message, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", Map.of("code", code, "message", message, "extraMessage", ""));
        body.put("data", data);
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
