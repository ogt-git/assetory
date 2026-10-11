package com.project.assetory.global.crypto;

public class MaskingUtil {

    private MaskingUtil() {}

    // 계좌번호 : 하이픈·공백 제거 후 앞 3자리 + ******* + 뒤 4자리 (예: 123-4567-8901 → "123*******8901")
    // 7자리 미만은 앞뒤를 남기면 원문이 대부분 드러나므로 전체를 가린다
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null) return null;
        String digits = accountNumber.replaceAll("[-\\s]", "");
        if (digits.length() < 7) return "*".repeat(digits.length());
        String prefix = digits.substring(0, 3);
        String suffix = digits.substring(digits.length() - 4);
        return prefix + "*******" + suffix;
    }

    // Email : 로컬파트 앞 2자리 + *** + @도메인 (예: us***@example.com)
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 2) return local + "***" + domain;
        return local.substring(0, 2) + "***" + domain;
    }

    // connectedId : 앞 4자리 + **** + 뒤 4자리 (예: 12ab****ef78)
    public static String maskConnectedId(String connectedId) {
        if (connectedId == null || connectedId.length() <= 8) return "****";
        return connectedId.substring(0, 4) + "****" + connectedId.substring(connectedId.length() - 4);
    }
}
