package com.project.assetory.connect.codef;

/**
 * CODEF 호출 타임아웃 10초 (NFR-PF-05).
 * easycodef-java는 내부 HttpURLConnection에 타임아웃을 설정하지 않으므로(무제한) JVM 기본값을 지정한다.
 * 이 값은 HttpURLConnection을 처음 쓰기 전에 설정돼야 적용되므로 main()에서 가장 먼저 호출한다.
 * 타임아웃이 나면 easycodef는 예외 대신 CF-09980을 돌려주고, NETWORK_ERROR로 분류된다.
 */
public final class CodefTimeouts {

    public static final String TIMEOUT_MS = "10000";

    private CodefTimeouts() {}

    public static void apply() {
        setIfAbsent("sun.net.client.defaultConnectTimeout");
        setIfAbsent("sun.net.client.defaultReadTimeout");
    }

    private static void setIfAbsent(String key) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, TIMEOUT_MS);
        }
    }
}
