package com.project.assetory.connect.codef.stock;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CODEF 증권 종합자산(/v1/kr/stock/a/account/financial-assets) 응답 data 파싱.
 * 원본 동기화 로직의 파싱 부분을 분리했다. 환율 환산은 하지 않고 해외 종목은 제외한다 (FR-CN-03, D-6).
 *
 * <pre>
 * data.resDepositReceived      예수금
 * data.resItemList[]           보유 종목
 *   resItemCode / resItemName  종목코드 / 종목명
 *   resProductType             상품 구분 원문 (예: "외화증권")
 *   resAccountCurrency         종목 통화 (KRW 외 = 해외)
 *   resQuantity                보유 수량
 *   resPurchaseAmount          매입금액
 *   resValuationAmt            평가금액
 *   resValuationPL             평가손익
 *   resEarningsRate            수익률(%)
 *   resPresentAmt              현재가
 *   resAvgPresentAmt           평균 매입단가
 *   resResultCode              종목별 조회 결과 ("0"이면 조회 실패 건)
 * </pre>
 */
public final class StockAssetParser {

    // resAccountCurrency를 신뢰할 수 없는 기관 — 해외 여부를 상품 구분으로만 판단 (원본: 전 필드를 원화로 간주하던 그룹)
    private static final Set<String> CURRENCY_UNRELIABLE_ORGS = Set.of("0267", "1267", "0287");

    private static final Set<String> DOMESTIC_CURRENCIES = Set.of("", "KRW", "원");

    private StockAssetParser() {}

    public static ParsedStockAccount parse(JsonNode data, String organizationCode) {
        JsonNode itemList = data.path("resItemList");
        boolean itemListValid = itemList.isArray();

        List<ParsedHolding> holdings = new ArrayList<>();
        int invalid = 0;
        int soldOut = 0;
        int foreign = 0;

        if (itemListValid) {
            for (JsonNode item : itemList) {
                // 종목 조회 실패 건 — 응답 신뢰 불가
                if ("0".equals(text(item, "resResultCode"))) {
                    invalid++;
                    continue;
                }
                // 종목코드가 없으면 UNIQUE(asset_id, ticker) 키가 없음 — 응답 신뢰 불가
                String ticker = text(item, "resItemCode");
                if (ticker.isEmpty()) {
                    invalid++;
                    continue;
                }
                if (isForeign(item, organizationCode)) {
                    foreign++;
                    continue;
                }

                // 필수 값(수량·평가금액)이 없거나 해석할 수 없으면 0으로 채우지 않고 응답 불완전으로 본다
                // (평가금액은 CODEF 평가액을 임시로 쓰는 동안 필수 — 한투 종가 적용 후 재검토)
                BigDecimal quantity  = decimal(item, "resQuantity");
                BigDecimal valuation = decimal(item, "resValuationAmt");
                if (quantity == null || valuation == null) {
                    invalid++;
                    continue;
                }
                // 선택 값 — 없으면 null로 둔다 (0으로 만들지 않음)
                BigDecimal purchase = decimal(item, "resPurchaseAmount");
                BigDecimal profit   = decimal(item, "resValuationPL");
                BigDecimal rate     = decimal(item, "resEarningsRate");

                // 전량 매도 종목: 응답에 명시된 수량·평가금액(·매입금액)이 모두 0 — 보유 목록에서 제외
                // 응답이 완전한 보유 목록일 때만 저장에 반영된다 (불완전 응답은 아무것도 바꾸지 않음)
                if (quantity.signum() <= 0 && valuation.signum() == 0 && (purchase == null || purchase.signum() == 0)) {
                    soldOut++;
                    continue;
                }

                // 매입금액·손익이 비면 수익률로 역산 — 수익률도 없으면 null 그대로
                if (rate != null && valuation.signum() > 0) {
                    BigDecimal[] corrected = correctMissingAmounts(valuation, orZero(purchase), orZero(profit), rate);
                    purchase = corrected[0];
                    profit = corrected[1];
                }

                holdings.add(new ParsedHolding(
                        ticker,
                        text(item, "resItemName"),
                        text(item, "resProductType"),
                        quantity,
                        avgBuyPrice(decimal(item, "resAvgPresentAmt"), purchase, quantity),
                        currentPrice(decimal(item, "resPresentAmt"), valuation, quantity),
                        valuation,
                        purchase,
                        profit,
                        rate == null ? null : rate.setScale(4, RoundingMode.HALF_UP)));
            }
        }

        // 예수금은 필수 — 없거나 해석할 수 없으면 응답 불완전 (정상 숫자 0원은 유효)
        BigDecimal depositReceived = decimal(data, "resDepositReceived");
        return new ParsedStockAccount(
                depositReceived,
                holdings,
                itemListValid && invalid == 0 && depositReceived != null,
                invalid, soldOut, foreign);
    }

    // 해외 종목 판별: 상품 구분에 "외화"/"해외"가 있거나, 통화가 원화가 아닌 경우 (통화를 믿을 수 없는 기관은 상품 구분만 사용)
    static boolean isForeign(JsonNode item, String organizationCode) {
        String productType = text(item, "resProductType");
        if (productType.contains("외화") || productType.contains("해외")) {
            return true;
        }
        if (CURRENCY_UNRELIABLE_ORGS.contains(organizationCode)) {
            return false;
        }
        return !DOMESTIC_CURRENCIES.contains(text(item, "resAccountCurrency").toUpperCase());
    }

    // 매입금액·평가손익 누락 보정 (원본 correctMissingStockAmounts) — 평가금액과 수익률로 역산
    static BigDecimal[] correctMissingAmounts(BigDecimal valuation, BigDecimal purchase,
                                              BigDecimal profit, BigDecimal rate) {
        if (valuation.signum() <= 0 || (purchase.signum() != 0 && profit.signum() != 0)) {
            return new BigDecimal[]{purchase, profit};
        }
        // 수익률 0% → 매입금액 = 평가금액, 평가손익 = 0
        if (rate.signum() == 0) {
            return new BigDecimal[]{purchase.signum() == 0 ? valuation : purchase, BigDecimal.ZERO};
        }
        BigDecimal denominator = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        if (denominator.signum() == 0) {
            return new BigDecimal[]{purchase, profit};
        }
        BigDecimal correctedPurchase = purchase.signum() == 0
                ? valuation.divide(denominator, 0, RoundingMode.HALF_UP)
                : purchase;
        BigDecimal correctedProfit = profit.signum() == 0 ? valuation.subtract(correctedPurchase) : profit;
        return new BigDecimal[]{correctedPurchase, correctedProfit};
    }

    private static BigDecimal avgBuyPrice(BigDecimal given, BigDecimal purchase, BigDecimal quantity) {
        if (given != null) return given.setScale(2, RoundingMode.HALF_UP);
        if (purchase != null && quantity.signum() > 0 && purchase.signum() > 0) {
            return purchase.divide(quantity, 2, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static BigDecimal currentPrice(BigDecimal given, BigDecimal valuation, BigDecimal quantity) {
        if (given != null) return given.setScale(2, RoundingMode.HALF_UP);
        if (quantity.signum() > 0 && valuation.signum() > 0) {
            return valuation.divide(quantity, 2, RoundingMode.HALF_UP);
        }
        return null;
    }

    // 숫자 파싱 — null·빈 문자열은 null, 콤마·공백·통화·% 기호 제거. 숫자가 아니면 null
    static BigDecimal decimal(JsonNode node, String field) {
        String raw = text(node, field).replaceAll("[,\\s원%+]", "");
        if (raw.isEmpty() || raw.equals("-")) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }
}
