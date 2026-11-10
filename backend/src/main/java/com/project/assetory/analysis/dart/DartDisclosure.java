package com.project.assetory.analysis.dart;

import java.time.LocalDate;

/**
 * 공시검색(list.json) list[] 1행 — 값은 DART가 준 그대로. 시각 필드는 없다 (01 §14 #8).
 *
 * @param reportName  report_nm 보고서명(공백 포함 원문 — 근거 변환 때 앞뒤 공백만 제거)
 * @param receiptNo   rcept_no 접수번호 14자리 — 앞 8자리가 접수일
 * @param receiptDate rcept_dt 접수일
 * @param remark      rm 비고(유 · 코 · 정 등, 없으면 빈 문자열)
 */
public record DartDisclosure(String corpCode, String corpName, String stockCode, String reportName,
                             String receiptNo, String filerName, LocalDate receiptDate, String remark) {
}
