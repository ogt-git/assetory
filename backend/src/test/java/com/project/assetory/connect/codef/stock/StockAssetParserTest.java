package com.project.assetory.connect.codef.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StockAssetParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static JsonNode fixtureData(String name) throws Exception {
        try (InputStream in = StockAssetParserTest.class.getResourceAsStream("/codef/" + name)) {
            return MAPPER.readTree(in).path("data");
        }
    }

    @Test
    @DisplayName("국내 종목만 파싱 — 해외(외화증권·USD) 제외, 전량 매도 제외, 예수금 콤마 처리")
    void parsesDomesticOnly() throws Exception {
        ParsedStockAccount parsed = StockAssetParser.parse(fixtureData("stock-financial-assets-success.json"), "0240");

        assertThat(parsed.complete()).isTrue();
        assertThat(parsed.depositReceived()).isEqualByComparingTo("1500000");
        assertThat(parsed.holdings()).extracting(ParsedHolding::ticker).containsExactly("005930", "000660");
        assertThat(parsed.foreignCount()).isEqualTo(1);
        assertThat(parsed.soldOutCount()).isEqualTo(1);
        assertThat(parsed.invalidCount()).isZero();
    }

    @Test
    @DisplayName("금액·수량은 BigDecimal, 콤마 포함 값 처리, 현재가·평균단가는 응답값 우선")
    void parsesNumbers() throws Exception {
        ParsedHolding samsung = StockAssetParser.parse(fixtureData("stock-financial-assets-success.json"), "0240")
                .holdings().get(0);

        assertThat(samsung.stockName()).isEqualTo("삼성전자");
        assertThat(samsung.quantity()).isEqualByComparingTo("20");
        assertThat(samsung.currentPrice()).isEqualByComparingTo("55000");
        assertThat(samsung.avgBuyPrice()).isEqualByComparingTo("50000");
        assertThat(samsung.evalAmount()).isEqualByComparingTo("1100000");
        assertThat(samsung.profitLoss()).isEqualByComparingTo("100000");
        assertThat(samsung.profitLossRate()).isEqualByComparingTo("10.0000");
    }

    @Test
    @DisplayName("현재가·평균단가가 비어 있으면 평가금액·매입금액 ÷ 수량으로 계산")
    void derivesPricesWhenEmpty() throws Exception {
        ParsedHolding hynix = StockAssetParser.parse(fixtureData("stock-financial-assets-success.json"), "0240")
                .holdings().get(1);

        assertThat(hynix.currentPrice()).isEqualByComparingTo("220000");
        assertThat(hynix.avgBuyPrice()).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("종목 조회 실패 건(resResultCode=0)이 있으면 응답 불완전")
    void incompleteResponse() throws Exception {
        ParsedStockAccount parsed = StockAssetParser.parse(fixtureData("stock-financial-assets-incomplete.json"), "0240");

        assertThat(parsed.complete()).isFalse();
        assertThat(parsed.invalidCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "예수금 \"{0}\" → 응답 불완전")
    @CsvSource(value = {"''", "'-'", "'abc'"})
    @DisplayName("예수금이 비었거나 해석할 수 없으면 응답 불완전 (0으로 채우지 않음)")
    void missingDepositIsIncomplete(String deposit) throws Exception {
        ParsedStockAccount parsed = StockAssetParser.parse(
                MAPPER.readTree("{\"resDepositReceived\":\"" + deposit + "\",\"resItemList\":[]}"), "0240");

        assertThat(parsed.complete()).isFalse();
        assertThat(parsed.depositReceived()).isNull();
    }

    @Test
    @DisplayName("예수금 필드가 아예 없어도 응답 불완전, 정상 숫자 0원은 유효")
    void depositFieldAbsentOrZero() throws Exception {
        assertThat(StockAssetParser.parse(MAPPER.readTree("{\"resItemList\":[]}"), "0240").complete()).isFalse();

        ParsedStockAccount zero = StockAssetParser.parse(
                MAPPER.readTree("{\"resDepositReceived\":\"0\",\"resItemList\":[]}"), "0240");
        assertThat(zero.complete()).isTrue();
        assertThat(zero.depositReceived()).isEqualByComparingTo("0");
    }

    @ParameterizedTest(name = "{0} 누락 → 응답 불완전")
    @CsvSource(value = {"resQuantity", "resValuationAmt"})
    @DisplayName("종목의 수량·평가금액이 없으면 0으로 채우지 않고 응답 불완전")
    void missingRequiredItemField(String field) throws Exception {
        String item = "{\"resItemCode\":\"005930\",\"resItemName\":\"삼성전자\",\"resQuantity\":\"10\","
                + "\"resValuationAmt\":\"700000\",\"resPurchaseAmount\":\"600000\"}";
        JsonNode data = MAPPER.readTree("{\"resDepositReceived\":\"100\",\"resItemList\":[" + item + "]}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("resItemList").get(0)).remove(field);

        ParsedStockAccount parsed = StockAssetParser.parse(data, "0240");

        assertThat(parsed.complete()).isFalse();
        assertThat(parsed.invalidCount()).isEqualTo(1);
        assertThat(parsed.holdings()).isEmpty();
    }

    @Test
    @DisplayName("선택 값(매입·손익·수익률)이 없고 역산도 불가하면 0이 아니라 null로 둔다")
    void optionalFieldsStayNull() throws Exception {
        JsonNode data = MAPPER.readTree("{\"resDepositReceived\":\"100\",\"resItemList\":[{\"resItemCode\":\"005930\","
                + "\"resItemName\":\"삼성전자\",\"resQuantity\":\"10\",\"resValuationAmt\":\"700000\"}]}");

        ParsedStockAccount parsed = StockAssetParser.parse(data, "0240");

        assertThat(parsed.complete()).isTrue();
        ParsedHolding holding = parsed.holdings().get(0);
        assertThat(holding.purchaseAmount()).isNull();
        assertThat(holding.profitLoss()).isNull();
        assertThat(holding.profitLossRate()).isNull();
        assertThat(holding.avgBuyPrice()).isNull();
        assertThat(holding.evalAmount()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("종목 목록이 배열이 아니면 응답 불완전")
    void missingItemList() throws Exception {
        ParsedStockAccount parsed = StockAssetParser.parse(MAPPER.readTree("{\"resDepositReceived\":\"100\"}"), "0240");

        assertThat(parsed.complete()).isFalse();
        assertThat(parsed.holdings()).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(value = {
            "1,234,567 | 1234567",
            "' 500 ' | 500",
            "10.5% | 10.5",
            "-3.25 | -3.25",
            "+1,000 | 1000",
            "1000원 | 1000"
    }, delimiter = '|')
    @DisplayName("숫자 정규화 — 콤마·공백·%·+·원 제거")
    void numberNormalization(String raw, String expected) throws Exception {
        JsonNode node = MAPPER.createObjectNode().put("v", raw.replace("'", ""));

        assertThat(StockAssetParser.decimal(node, "v")).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("빈 문자열·null·누락·숫자 아님 → null")
    void blankNumbers() throws Exception {
        JsonNode node = MAPPER.readTree("{\"empty\":\"\",\"nul\":null,\"dash\":\"-\",\"text\":\"N/A\"}");

        assertThat(StockAssetParser.decimal(node, "empty")).isNull();
        assertThat(StockAssetParser.decimal(node, "nul")).isNull();
        assertThat(StockAssetParser.decimal(node, "missing")).isNull();
        assertThat(StockAssetParser.decimal(node, "dash")).isNull();
        assertThat(StockAssetParser.decimal(node, "text")).isNull();
    }

    @ParameterizedTest(name = "{0} / 통화 {1} / 기관 {2} → 해외 {3}")
    @CsvSource({
            "주식,     KRW, 0240, false",
            "주식,     '',  0240, false",
            "외화증권, USD, 0240, true",
            "주식,     USD, 0240, true",
            "해외주식, '',  0240, true",
            // 통화 값을 믿을 수 없는 기관(대신·크레온·메리츠)은 상품 구분으로만 판단
            "주식,     USD, 0267, false",
            "외화증권, USD, 0267, true"
    })
    @DisplayName("해외 종목 판별 — 상품 구분(외화·해외) 또는 원화가 아닌 통화")
    void foreignDetection(String productType, String currency, String org, boolean expected) {
        JsonNode item = MAPPER.createObjectNode()
                .put("resProductType", productType).put("resAccountCurrency", currency);

        assertThat(StockAssetParser.isForeign(item, org)).isEqualTo(expected);
    }

    @Test
    @DisplayName("매입금액·손익 누락 보정 — 수익률로 역산, 수익률 0%면 매입 = 평가")
    void correctsMissingAmounts() {
        BigDecimal[] byRate = StockAssetParser.correctMissingAmounts(
                new BigDecimal("110000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10"));
        assertThat(byRate[0]).isEqualByComparingTo("100000");
        assertThat(byRate[1]).isEqualByComparingTo("10000");

        BigDecimal[] zeroRate = StockAssetParser.correctMissingAmounts(
                new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(zeroRate[0]).isEqualByComparingTo("100000");
        assertThat(zeroRate[1]).isEqualByComparingTo("0");
    }
}
