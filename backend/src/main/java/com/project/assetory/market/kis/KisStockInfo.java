package com.project.assetory.market.kis;

/**
 * 종목 기본정보 (search-stock-info output) — ETF · 거래정지 판별 후보 필드를 원문 코드 그대로 담는다.
 * 코드 값의 의미는 실호출 검증(V2 · V3) 뒤에 확정한다. 빈 값은 null.
 *
 * @param securityGroupCode scty_grp_id_cd 증권그룹ID
 * @param etfDivisionCode   etf_dvsn_cd ETF구분
 * @param etfTypeCode       etf_type_cd ETF유형
 * @param tradingHalted     tr_stop_yn 거래정지여부
 * @param administrativeIssue admn_item_yn 관리종목여부
 * @param delistingDate     lstg_abol_dt 상장폐지일자 (yyyyMMdd 원문)
 */
public record KisStockInfo(
        String ticker,
        String name,
        String securityGroupCode,
        String etfDivisionCode,
        String etfTypeCode,
        String tradingHalted,
        String administrativeIssue,
        String delistingDate
) {}
