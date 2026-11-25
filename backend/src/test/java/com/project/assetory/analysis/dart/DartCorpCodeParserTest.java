package com.project.assetory.analysis.dart;

import com.project.assetory.analysis.exception.DartApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.project.assetory.analysis.dart.DartTestSupport.CORP_CODE_XML;
import static com.project.assetory.analysis.dart.DartTestSupport.corpCodeZip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// corpCode.xml ZIP → stock_code → corp_code (D4 메모리 캐시의 원본). ZIP은 테스트 안에서 만든다
class DartCorpCodeParserTest {

    @Test
    @DisplayName("상장사(stock_code 있음)만 담는다 — 비상장(공백)은 뺀다")
    void listedOnly() {
        Map<String, String> codes = DartCorpCodeParser.parse(corpCodeZip(CORP_CODE_XML));

        assertThat(codes).containsExactlyInAnyOrderEntriesOf(Map.of("999901", "00999901", "999903", "00999903"));
    }

    @Test
    @DisplayName("ZIP이 아닌 JSON 오류 응답 → status로 분류해 실패")
    void jsonError() {
        byte[] body = "{\"status\":\"010\",\"message\":\"테스트 전용\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DartCorpCodeParser.parse(body))
                .isInstanceOfSatisfying(DartApiException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(DartErrorType.KEY_OR_ACCESS);
                    assertThat(e.getDartCode()).isEqualTo("010");
                });
    }

    @Test
    @DisplayName("ZIP이 아닌 XML 오류 응답 → status로 분류해 실패")
    void xmlError() {
        byte[] body = "<?xml version=\"1.0\"?><result><status>020</status><message>테스트 전용</message></result>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DartCorpCodeParser.parse(body))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.RATE_LIMIT));
    }

    @Test
    @DisplayName("해석할 수 없는 본문 · 빈 ZIP · 상장사 0건 → INVALID_RESPONSE (0건으로 굳히지 않는다)")
    void invalid() {
        String noListed = """
                <result><list><corp_code>00999902</corp_code><stock_code> </stock_code></list></result>""";

        for (byte[] body : new byte[][]{
                "<html>점검 중</html>".getBytes(StandardCharsets.UTF_8),
                new byte[0],
                DartTestSupport.zip("other.txt", "x"),
                corpCodeZip("<result><list><corp_code>"),
                corpCodeZip(noListed)}) {
            assertThatThrownBy(() -> DartCorpCodeParser.parse(body))
                    .isInstanceOfSatisfying(DartApiException.class,
                            e -> assertThat(e.getErrorType()).isEqualTo(DartErrorType.INVALID_RESPONSE));
        }
    }

    @Test
    @DisplayName("외부 엔티티(XXE)를 읽지 않는다")
    void noExternalEntity() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <result><list><corp_code>00999901</corp_code><stock_code>&x;</stock_code></list></result>""";

        assertThatThrownBy(() -> DartCorpCodeParser.parse(corpCodeZip(xxe))).isInstanceOf(DartApiException.class);
    }
}
