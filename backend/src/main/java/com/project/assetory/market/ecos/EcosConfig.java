package com.project.assetory.market.ecos;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(EcosProperties.class)
public class EcosConfig {

    // Boot가 주는 RestClient.Builder를 쓴다(KisConfig · DartConfig와 같은 방식) — http.client.requests uri 태그는 템플릿이라 키 값이 남지 않는다
    @Bean
    public RestClient ecosRestClient(RestClient.Builder builder, EcosProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }
}
