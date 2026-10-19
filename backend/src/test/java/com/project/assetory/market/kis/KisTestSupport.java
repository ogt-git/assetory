package com.project.assetory.market.kis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 한투 클라이언트 단위 테스트 공용 — 스프링 없이 MockRestServiceServer + 메모리 Redis 대역으로 돌린다
final class KisTestSupport {

    static final String BASE_URL = "http://kis.test";
    // 테스트 전용 더미 키 — 실제 키가 아니다
    static final String APP_KEY = "test-only-app-key";
    static final String APP_SECRET = "test-only-app-secret";

    private KisTestSupport() {}

    static KisProperties properties(int rateLimitMaxRetries) {
        return new KisProperties(BASE_URL, APP_KEY, APP_SECRET, 0,
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofMinutes(10),
                new KisProperties.RateLimit(rateLimitMaxRetries, 0));
    }

    static String fixture(String name) {
        try {
            return new ClassPathResource("kis/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // StringRedisTemplate 대역 — get · set(TTL) · delete만 메모리 맵으로 흉내 낸다. 마지막 TTL은 ttls에 남긴다
    static final class FakeRedis {
        final Map<String, String> values = new ConcurrentHashMap<>();
        final Map<String, Duration> ttls = new ConcurrentHashMap<>();
        final StringRedisTemplate template = mock(StringRedisTemplate.class);

        @SuppressWarnings("unchecked")
        FakeRedis() {
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
            doAnswer(inv -> {
                values.put(inv.getArgument(0), inv.getArgument(1));
                ttls.put(inv.getArgument(0), inv.getArgument(2));
                return null;
            }).when(ops).set(anyString(), anyString(), any(Duration.class));
            when(template.delete(anyString())).thenAnswer(inv -> values.remove(inv.<String>getArgument(0)) != null);
        }
    }
}
