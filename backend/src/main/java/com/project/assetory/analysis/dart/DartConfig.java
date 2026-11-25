package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.config.EvidenceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(DartProperties.class)
public class DartConfig {

    // Boot가 주는 RestClient.Builder를 쓴다(KisConfig와 같은 방식) — http.client.requests uri 태그는 템플릿이라 키 값이 남지 않는다
    // 공시 목록: 외부 호출 타임아웃 10초 (NFR-PF-05)
    @Bean
    public RestClient dartListRestClient(RestClient.Builder builder, DartProperties dart, EvidenceProperties evidence) {
        return build(builder, dart.baseUrl(), evidence.callTimeout(), evidence.callTimeout());
    }

    // 고유번호 ZIP(수 MB): 연결은 10초, 읽기는 corp-code-timeout
    @Bean
    public RestClient dartCorpCodeRestClient(RestClient.Builder builder, DartProperties dart, EvidenceProperties evidence) {
        return build(builder, dart.baseUrl(), evidence.callTimeout(), dart.corpCodeTimeout());
    }

    private static RestClient build(RestClient.Builder builder, String baseUrl, Duration connect, Duration read) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connect);
        requestFactory.setReadTimeout(read);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
