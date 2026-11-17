package com.project.assetory.ai.openai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiConfig {

    // Boot가 주는 RestClient.Builder를 쓴다(네이버 · DART와 같은 방식). 연결 · 읽기 타임아웃 10초 (AGENTS 9장, NFR-PF-05)
    @Bean
    public RestClient openAiRestClient(RestClient.Builder builder, OpenAiProperties openAi) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(openAi.connectTimeout());
        requestFactory.setReadTimeout(openAi.readTimeout());
        return builder.clone().baseUrl(openAi.baseUrl()).requestFactory(requestFactory).build();
    }
}
