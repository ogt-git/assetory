package com.project.assetory.analysis.dart;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * DART 접수번호(rcept_no) — 14자리 숫자, 앞 8자리가 접수일(yyyyMMdd).
 * 공시 근거는 published_at이 NULL이라 날짜는 external_id(= 접수번호)에서 꺼낸다 (01 §14 #8, B1 확정 사항 1)
 */
public final class DartReceiptNo {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private DartReceiptNo() {
    }

    public static boolean isValid(String receiptNo) {
        return receiptNo != null && receiptNo.matches("\\d{14}") && parseDate(receiptNo.substring(0, 8)) != null;
    }

    /** 형식이 틀리면 IllegalArgumentException — 날짜를 추측하지 않는다 */
    public static LocalDate dateOf(String receiptNo) {
        if (!isValid(receiptNo)) {
            throw new IllegalArgumentException("접수번호 형식이 아니다");
        }
        return parseDate(receiptNo.substring(0, 8));
    }

    private static LocalDate parseDate(String yyyyMMdd) {
        try {
            return LocalDate.parse(yyyyMMdd, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
