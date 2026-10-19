package com.project.assetory.market.kis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KisProperties.class)
public class KisConfig {

    // Boot가 주는 RestClient.Builder를 쓴다 — http.client.requests 메트릭이 자동으로 붙는다. 타임아웃 10초 (NFR-PF-05)
    @Bean
    public RestClient kisRestClient(RestClient.Builder builder, KisProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
