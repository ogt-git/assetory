package com.project.assetory.global.config;

import com.project.assetory.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 운영 지표는 관리 포트에서만 health · prometheus 두 개만 열린다 (FR-OP-01, NFR-SE-08)
// 실제 포트가 필요해서 MockMvc 대신 RANDOM_PORT + 관리 포트 0(임의)으로 띄운다. 테스트는 기본으로 메트릭 export가 꺼져 있어 켠다
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class ActuatorIntegrationTest extends IntegrationTestSupport {

    @LocalServerPort int serverPort;
    @LocalManagementPort int managementPort;
    @Autowired TestRestTemplate rest;

    private ResponseEntity<String> getApi(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) headers.setBearerAuth(accessToken);
        return rest.exchange("http://localhost:" + serverPort + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getManagement(String path) {
        return rest.getForEntity("http://localhost:" + managementPort + path, String.class);
    }

    @Test
    @DisplayName("API 포트에서는 actuator가 없다 — 비인증은 기존 규칙대로 401, 인증해도 404")
    void apiPort_noActuator() throws Exception {
        assertThat(getApi("/actuator/prometheus", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String token = signupAndGetAccessToken();
        assertThat(getApi("/actuator/prometheus", token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getApi("/actuator/health", token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("관리 포트 prometheus는 인증 없이 200, HTTP · repository 지표와 공통 태그가 있다")
    void managementPort_prometheus() throws Exception {
        String token = signupAndGetAccessToken();   // repository 호출 발생
        getApi("/api/health", null);
        getApi("/api/assets", token);

        ResponseEntity<String> res = getManagement("/actuator/prometheus");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("http_server_requests")
                .contains("application=\"assetory\"")
                .contains("spring_data_repository_invocations")
                .contains("hikaricp_connections_active")
                // NFR-PF-01 판정용 SLO 버킷(500ms)
                .contains("le=\"0.5\"")
                // 토큰이 태그로 새지 않는다 (NFR-SE-07)
                .doesNotContain(token);
    }

    @Test
    @DisplayName("uri 태그는 실제 값이 아니라 경로 템플릿으로 남는다 — 카디널리티 · 식별자 노출 방지")
    void managementPort_uriTemplate() throws Exception {
        String token = signupAndGetAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        rest.exchange("http://localhost:" + serverPort + "/api/assets/manual/987654321", HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);

        // 서버 쪽 지표만 본다 (테스트의 TestRestTemplate이 남기는 http_client_requests는 제외)
        List<String> serverLines = getManagement("/actuator/prometheus").getBody().lines()
                .filter(line -> line.startsWith("http_server_requests"))
                .toList();

        assertThat(serverLines)
                .anyMatch(line -> line.contains("uri=\"/api/assets/manual/{assetId}\""))
                .noneMatch(line -> line.contains("987654321"));
    }

    @Test
    @DisplayName("관리 포트 health는 200이고 구성요소 상세를 보여주지 않는다")
    void managementPort_healthWithoutDetails() {
        ResponseEntity<String> res = getManagement("/actuator/health");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    @Test
    @DisplayName("health · prometheus 외 엔드포인트는 관리 포트에서도 열리지 않는다")
    void managementPort_otherEndpointsHidden() {
        assertThat(getManagement("/actuator/env").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getManagement("/actuator/metrics").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getManagement("/actuator/heapdump").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getManagement("/actuator").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
