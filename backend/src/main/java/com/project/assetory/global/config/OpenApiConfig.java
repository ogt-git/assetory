package com.project.assetory.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// Swagger UI 문서 정보 + JWT(AT) 입력용 Authorize 버튼. 노출 여부는 springdoc.*.enabled(SWAGGER_ENABLED)로 정한다
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";
    private static final String MULTIPART_FORM_DATA = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

    @Bean
    public OpenAPI assetoryOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Assetory API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    // multipart API의 @RequestParam은 springdoc이 query로 그려서 Swagger UI가 비밀번호를 URL에 싣는다.
    // 문서에서만 multipart 폼 필드로 옮긴다 — 서버의 @RequestParam은 폼 필드도 읽으므로 컨트롤러는 그대로 둔다
    @Bean
    public OperationCustomizer multipartQueryToFormFields() {
        return (operation, handlerMethod) -> {
            RequestBody body = operation.getRequestBody();
            if (body == null || body.getContent() == null || operation.getParameters() == null) {
                return operation;
            }
            MediaType multipart = body.getContent().get(MULTIPART_FORM_DATA);
            if (multipart == null) {
                return operation;
            }
            List<Parameter> queryParams = operation.getParameters().stream()
                    .filter(p -> "query".equals(p.getIn()))
                    .toList();
            if (queryParams.isEmpty()) {
                return operation;
            }

            Schema<?> form = multipart.getSchema() != null ? multipart.getSchema() : new ObjectSchema();
            for (Parameter param : queryParams) {
                form.addProperty(param.getName(), param.getSchema());
                if (Boolean.TRUE.equals(param.getRequired())) {
                    form.addRequiredItem(param.getName());
                }
            }
            multipart.setSchema(form);
            operation.getParameters().removeAll(queryParams);
            return operation;
        };
    }
}
