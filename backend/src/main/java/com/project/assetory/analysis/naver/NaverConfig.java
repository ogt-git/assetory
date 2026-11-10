package com.project.assetory.analysis.naver;

import com.project.assetory.analysis.config.EvidenceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(NaverProperties.class)
public class NaverConfig {

    // Boot가 주는 RestClient.Builder를 쓴다(KisConfig · DartConfig와 같은 방식). 연결 · 읽기 타임아웃 10초 (NFR-PF-05)
    @Bean
    public RestClient naverRestClient(RestClient.Builder builder, NaverProperties naver, EvidenceProperties evidence) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(evidence.callTimeout());
        requestFactory.setReadTimeout(evidence.callTimeout());
        return builder.clone().baseUrl(naver.baseUrl()).requestFactory(requestFactory).build();
    }
}
