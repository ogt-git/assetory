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
    @DisplayName("같은 단순 이름의 응답 record가 서로의 스키마를 덮어쓰지 않는다 — /api/assets 종목과 자산 기록 상세 종목")
    void apiDocs_holdingItemSchemasSeparate() throws Exception {
        String schemas = "$.components.schemas";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // /api/assets (AssetOverviewResponse.HoldingItem) — CODEF 평가 · 손익 필드
                .andExpect(jsonPath(schemas + ".HoldingItem.properties.evalAmount").exists())
                .andExpect(jsonPath(schemas + ".HoldingItem.properties.profitLossRate").exists())
                .andExpect(jsonPath(schemas + ".HoldingItem.properties.price").doesNotExist())
                // /api/snapshots/{date} (SnapshotDetailResponse.HoldingItem) — 그날 평가 단가 · 전일 대비
                .andExpect(jsonPath(schemas + ".SnapshotDetailHoldingItem.properties.price").exists())
                .andExpect(jsonPath(schemas + ".SnapshotDetailHoldingItem.properties.dayChangeAmount").exists())
                .andExpect(jsonPath(schemas + ".SecuritiesAccount.properties.holdings.items.$ref").value("#/components/schemas/HoldingItem"))
                .andExpect(jsonPath(schemas + ".SnapshotDetailAssetItem.properties.holdings.items.$ref")
                        .value("#/components/schemas/SnapshotDetailHoldingItem"));
    }

    @Test
    @DisplayName("자산 기록 응답의 내부 record 스키마 이름 = 바깥 응답 이름 + 내부 이름 — 일반 이름(Record · Point · Comparison 등)으로 겹치지 않는다")
    void apiDocs_snapshotNestedSchemaNames() throws Exception {
        String schemas = "$.components.schemas";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(schemas + ".AssetChangeComparison").exists())
                .andExpect(jsonPath(schemas + ".AssetChangeLatestRecord").exists())
                .andExpect(jsonPath(schemas + ".AssetChangeAssetClassChanges").exists())
                .andExpect(jsonPath(schemas + ".AssetTrendPoint").exists())
                .andExpect(jsonPath(schemas + ".SnapshotMonthRecord").exists())
                .andExpect(jsonPath(schemas + ".SnapshotDetailAssetClasses").exists())
                .andExpect(jsonPath(schemas + ".SnapshotDetailAssetClassItem").exists())
                .andExpect(jsonPath(schemas + ".SnapshotDetailAssetItem").exists())
                .andExpect(jsonPath(schemas + ".Comparison").doesNotExist())
                .andExpect(jsonPath(schemas + ".LatestRecord").doesNotExist())
                .andExpect(jsonPath(schemas + ".Point").doesNotExist())
                .andExpect(jsonPath(schemas + ".Record").doesNotExist())
                .andExpect(jsonPath(schemas + ".AssetItem").doesNotExist())
                .andExpect(jsonPath(schemas + ".AssetClasses").doesNotExist());
    }

    @Test
    @DisplayName("켜져 있으면 Swagger UI 화면이 인증 없이 열린다")
    void swaggerUi_enabled() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
