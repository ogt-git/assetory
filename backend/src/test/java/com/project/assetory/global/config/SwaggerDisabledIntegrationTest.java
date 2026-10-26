package com.project.assetory.global.config;

import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SWAGGER_ENABLED가 없으면(기본값) API 문서 · Swagger UI가 외부에 열리지 않는지 고정한다
class SwaggerDisabledIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("기본값(꺼짐)이면 API 문서 경로는 인증 없이 401")
    void apiDocs_disabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("기본값(꺼짐)이면 Swagger UI 경로는 인증 없이 401")
    void swaggerUi_disabled() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());
    }
}
