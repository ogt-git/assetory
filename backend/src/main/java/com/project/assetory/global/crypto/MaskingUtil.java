package com.project.assetory.global.crypto;

public class MaskingUtil {

    private MaskingUtil() {}

    // 계좌번호 : 앞 3자리 + ******* + 뒤 4자리 (예: 12345678901 → "123*******8901")
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 7) return accountNumber;
        String prefix = accountNumber.substring(0, 3);
        String suffix = accountNumber.substring(accountNumber.length() - 4);
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
