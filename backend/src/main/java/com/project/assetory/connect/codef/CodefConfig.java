package com.project.assetory.connect.codef;

import io.codef.api.EasyCodef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CodefConfig {

    @Value("${codef.client-id}")
    private String clientId;

    @Value("${codef.client-secret}")
    private String clientSecret;

    @Value("${codef.public-key}")
    private String publicKey;

    @Value("${codef.service-type:1}")
    private int serviceType;

    @Bean
    public EasyCodef easyCodef() {
        EasyCodef codef = new EasyCodef();
        if (serviceType == 0) {
            codef.setClientInfo(clientId, clientSecret);
        } else {
            codef.setClientInfoForDemo(clientId, clientSecret);
        }
        codef.setPublicKey(publicKey);
        return codef;
    }
}
