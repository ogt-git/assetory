package com.project.assetory.connect.codef;

import io.codef.api.EasyCodef;
import io.codef.api.EasyCodefServiceType;
import io.codef.api.EasyCodefUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodefApiClient {

    private final EasyCodef easyCodef;

    @Value("${codef.public-key}")
    private String publicKey;

    @Value("${codef.service-type}")
    private int serviceType;

    public String encryptRSA(String plainText) throws Exception {
        return EasyCodefUtil.encryptRSA(plainText, publicKey);
    }

    private EasyCodefServiceType resolveServiceType() {
        return (serviceType == 0) ? EasyCodefServiceType.API : EasyCodefServiceType.DEMO;
    }

    // 계정 연결 (connectedId 발급)
    public String createAccount(HashMap<String, Object> accountMap) throws Exception {
        log.info("[CODEF] createAccount org={}", resolveOrganization(accountMap));
        String response = easyCodef.createAccount(resolveServiceType(), accountMap);
        log.debug("[CODEF] createAccount completed");
        return response;
    }

    // 계정 연결 해지
    public String deleteAccount(HashMap<String, Object> accountMap) throws Exception {
        log.info("[CODEF] deleteAccount org={}", resolveOrganization(accountMap));
        String response = easyCodef.deleteAccount(resolveServiceType(), accountMap);
        log.debug("[CODEF] deleteAccount completed");
        return response;
    }

    // 계정 정보 업데이트 (인증 재시도용)
    public String updateAccount(HashMap<String, Object> accountMap) throws Exception {
        log.info("[CODEF] updateAccount org={}", resolveOrganization(accountMap));
        String response = easyCodef.updateAccount(resolveServiceType(), accountMap);
        log.debug("[CODEF] updateAccount completed");
        return response;
    }

    // 데이터 조회
    public String requestProduct(String productUrl, HashMap<String, Object> params) throws Exception {
        log.info("[CODEF] requestProduct url={}", productUrl);
        String response = easyCodef.requestProduct(productUrl, resolveServiceType(), params);
        log.debug("[CODEF] requestProduct completed url={}", productUrl);
        return response;
    }

    private Object resolveOrganization(HashMap<String, Object> parameters) {
        Object organization = parameters.get("organization");
        if (organization != null) {
            return organization;
        }

        Object accountList = parameters.get("accountList");
        if (accountList instanceof Iterable<?> accounts) {
            for (Object account : accounts) {
                if (account instanceof Map<?, ?> accountMap) {
                    return accountMap.get("organization");
                }
            }
        }
        return null;
    }
}
