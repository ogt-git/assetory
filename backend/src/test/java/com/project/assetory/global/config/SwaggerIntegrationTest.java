package com.project.assetory.global.config;

import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SWAGGER_ENABLED=true(local)일 때 API 문서와 Swagger UI가 인증 없이 열리는지 고정한다
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"})
class SwaggerIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("켜져 있으면 API 문서에 전체 경로와 JWT 인증 방식이 들어 있다")
    void apiDocs_enabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/codef/connect']").exists())
                .andExpect(jsonPath("$.paths['/api/assets']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    @DisplayName("multipart API의 문자열 필드는 query가 아닌 폼 필드로 문서화된다 — 비밀번호가 URL에 실리지 않게")
    void apiDocs_multipartFieldsInFormBody() throws Exception {
        String connect = "$.paths['/api/codef/connect'].post";
        String form = connect + ".requestBody.content['multipart/form-data'].schema";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(connect + ".parameters[?(@.in == 'query')]").isEmpty())
                .andExpect(jsonPath(form + ".properties.password").exists())
                .andExpect(jsonPath(form + ".properties.accountPassword").exists())
                .andExpect(jsonPath(form + ".properties.derFile").exists())
                .andExpect(jsonPath(form + ".required", hasItems("organization", "businessType", "loginType", "password")))
                .andExpect(jsonPath(form + ".required", not(hasItem("accountPassword"))));
    }

    @Test
    @DisplayName("켜져 있으면 Swagger UI 화면이 인증 없이 열린다")
    void swaggerUi_enabled() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
