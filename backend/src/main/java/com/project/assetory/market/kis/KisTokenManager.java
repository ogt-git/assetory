package com.project.assetory.market.kis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.market.exception.KisApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 한투 접근 토큰 — Redis `kis:access-token`(05 §9)에 두고 만료 전에는 다시 발급하지 않는다.
 * 발급은 1분 1회로 제한되고 발급마다 알림톡이 가므로 동시 발급을 막는다. 운영은 단일 인스턴스라 프로세스 내 락으로 충분하다.
 * 토큰 · 앱키 · 시크릿은 로그 · 예외 메시지에 남기지 않는다 (NFR-SE-07).
 */
@Slf4j
@Component
public class KisTokenManager {

    static final String TOKEN_KEY = "kis:access-token";
    private static final String TOKEN_PATH = "/oauth2/tokenP";

    private final RestClient restClient;
    private final StringRedisTemplate redisTemplate;
    private final KisProperties properties;
    private final KisMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock issueLock = new ReentrantLock();

    public KisTokenManager(@Qualifier("kisRestClient") RestClient restClient, StringRedisTemplate redisTemplate,
                           KisProperties properties, KisMetrics metrics) {
        this.restClient = restClient;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    // Redis에 있으면 그대로, 없으면(만료 여유 이내 포함 — TTL로 미리 사라짐) 1회 발급
    public String getToken() {
        String token = redisTemplate.opsForValue().get(TOKEN_KEY);
        if (token != null) return token;

        issueLock.lock();
        try {
            token = redisTemplate.opsForValue().get(TOKEN_KEY);
            return token != null ? token : issue();
        } finally {
            issueLock.unlock();
        }
    }

    // 토큰 오류 응답을 받았을 때 — 다른 스레드가 이미 새 토큰을 받았으면 그것을 쓰고, 아니면 버리고 새로 발급
    public String reissue(String rejectedToken) {
        issueLock.lock();
        try {
            String current = redisTemplate.opsForValue().get(TOKEN_KEY);
            if (current != null && !current.equals(rejectedToken)) return current;
            redisTemplate.delete(TOKEN_KEY);
            return issue();
        } finally {
            issueLock.unlock();
        }
    }

    private String issue() {
        if (!properties.hasCredentials()) {
            throw new KisApiException(KisErrorType.CONFIG_MISSING, null);
        }
        long start = System.nanoTime();
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("grant_type", "client_credentials",
                            "appkey", properties.appKey(),
                            "appsecret", properties.appSecret()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            metrics.record(KisMetrics.API_TOKEN, false, start);
            log.warn("[KIS] 토큰 발급 호출 실패 error={}", e.getClass().getSimpleName());
            throw new KisApiException(KisErrorType.NETWORK_ERROR, null, e);
        }

        JsonNode body = readTree(response.getBody());
        String token = body.path("access_token").asText("");
        long expiresIn = body.path("expires_in").asLong(0);
        if (!response.getStatusCode().is2xxSuccessful() || token.isBlank() || expiresIn <= 0) {
            metrics.record(KisMetrics.API_TOKEN, false, start);
            String code = body.path("error_code").asText(null);
            log.warn("[KIS] 토큰 발급 실패 status={} code={}", response.getStatusCode().value(), code);
            throw new KisApiException(KisErrorType.TOKEN_ISSUE_FAILED, code);
        }
        metrics.record(KisMetrics.API_TOKEN, true, start);

        Duration ttl = Duration.ofSeconds(expiresIn).minus(properties.tokenRefreshMargin());
        if (!ttl.isNegative() && !ttl.isZero()) {
            redisTemplate.opsForValue().set(TOKEN_KEY, token, ttl);
        }
        log.info("[KIS] 접근 토큰 발급 expiresInSec={}", expiresIn);
        return token;
    }

    private JsonNode readTree(String body) {
        try {
            return body == null ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
