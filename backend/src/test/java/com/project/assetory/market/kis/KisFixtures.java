package com.project.assetory.market.kis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 픽스처 JSON을 실제 KisApiClient 파서로 읽어 응답 객체로 만든다 — 서비스 테스트에서 KisApiClient 목의 반환값으로 쓴다
public final class KisFixtures {

    private KisFixtures() {}

    public static List<KisDailyPrice> dailyPrices(String fixtureName) {
        return parser(fixtureName).getDailyPrices("000000");
    }

    public static KisStockInfo stockInfo(String fixtureName) {
        return parser(fixtureName).getStockInfo("000000");
    }

    private static KisApiClient parser(String fixtureName) {
        RestClient.Builder builder = RestClient.builder().baseUrl(KisTestSupport.BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(KisTestSupport.fixture(fixtureName), MediaType.APPLICATION_JSON));
        KisTestSupport.FakeRedis redis = new KisTestSupport.FakeRedis();
        redis.values.put(KisTokenManager.TOKEN_KEY, "fixture-token");
        RestClient restClient = builder.build();
        KisProperties properties = KisTestSupport.properties(0);
        KisMetrics metrics = new KisMetrics(new SimpleMeterRegistry());
        return new KisApiClient(restClient, new KisTokenManager(restClient, redis.template, properties, metrics), properties, metrics);
    }
}
