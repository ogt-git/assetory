package com.project.assetory.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai 패키지 의존 방향 (Q-10 D2) — ai는 LLM 공통 기반이라 다른 도메인을 참조하지 않는다. 허용: com.project.assetory.ai · global.
 * 기능(analysis · 이후 goal · monthly) → ai 방향만 허용한다. 새 라이브러리(ArchUnit 등) 없이 소스 텍스트로 확인한다(스프링 없음).
 * 검사 대상: import · import static · 와일드카드 import, 그리고 import 없이 본문에 쓴 전체 경로 참조(package 줄 제외)
 */
class AiPackageDependencyTest {

    // Gradle 테스트 작업 디렉터리는 backend/ (build.gradle에 workingDir 설정 없음 — 실호출 테스트의 Path.of("build", …)와 같은 기준)
    private static final Path AI_SOURCES = Path.of("src", "main", "java", "com", "project", "assetory", "ai");

    @Test
    @DisplayName("실제 ai 소스에 다른 도메인 참조가 0건 — 스캔한 파일이 0개면 경로 오류로 보고 실패")
    void aiSourcesHaveNoOtherDomainReference() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(AI_SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).as("ai 소스 파일을 찾지 못했다 — 작업 디렉터리 확인: %s", AI_SOURCES.toAbsolutePath()).isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            violations.addAll(violations(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8)));
        }

        assertThat(violations).as("ai 패키지는 ai · global만 참조할 수 있다").isEmpty();
    }

    @ParameterizedTest(name = "[{index}] 허용: {0}")
    @ValueSource(strings = {
            "import com.project.assetory.ai.LlmPurpose;",
            "import com.project.assetory.ai.openai.OpenAiApiClient;",
            "import com.project.assetory.global.response.ApiResponse;",
            "import static com.project.assetory.global.util.Something.helper;",
            "import com.project.assetory.ai.guard.*;",
            "import java.util.List;",
            "import org.springframework.stereotype.Component;",
            "import io.micrometer.core.instrument.MeterRegistry;",
            "package com.project.assetory.ai.quota;"})
    @DisplayName("판정 — ai · global · 외부 라이브러리 · package 줄은 허용")
    void allowed(String line) {
        assertThat(violations("Sample.java", source(line))).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] 위반: {0}")
    @ValueSource(strings = {
            "import com.project.assetory.analysis.domain.StockEventAnalysis;",
            "import com.project.assetory.detection.service.StockChangeDetectionService;",
            "import com.project.assetory.snapshot.domain.AssetSnapshot;",
            "import static com.project.assetory.analysis.domain.SourceType.NEWS;",
            "import com.project.assetory.analysis.domain.*;",
            "    private com.project.assetory.analysis.domain.EventSource source;",
            "        var x = com.project.assetory.market.domain.IndicatorCode.KOSPI;"})
    @DisplayName("판정 — 다른 도메인 import · 정적 import · 와일드카드 · 본문의 전체 경로 참조는 위반")
    void violation(String line) {
        List<String> found = violations("Sample.java", source(line));

        assertThat(found).hasSize(1);
        assertThat(found.get(0)).startsWith("Sample.java:").contains(line.strip());
    }

    @Test
    @DisplayName("판정 — 위반 메시지는 파일 이름 · 줄 번호 · 그 줄 내용, 여러 줄이면 모두")
    void violationMessage() {
        String src = String.join("\n",
                "package com.project.assetory.ai.sample;",
                "",
                "import com.project.assetory.ai.LlmPurpose;",
                "import com.project.assetory.analysis.domain.StockEventAnalysis;",
                "import com.project.assetory.detection.domain.Foo;",
                "",
                "class Sample {}");

        assertThat(violations("Sample.java", src)).containsExactly(
                "Sample.java:4: import com.project.assetory.analysis.domain.StockEventAnalysis;",
                "Sample.java:5: import com.project.assetory.detection.domain.Foo;");
    }

    // 테스트 전용 — 지어낸 소스 한 덩어리
    private static String source(String line) {
        return String.join("\n", "package com.project.assetory.ai.sample;", "", line, "", "class Sample {}");
    }

    /**
     * 허용되지 않은 com.project.assetory.* 참조가 있는 줄 — "파일:줄번호: 줄 내용". package 줄은 건너뛴다.
     * import · import static · 와일드카드 · 본문 전체 경로 모두 같은 규칙: assetory 다음 첫 패키지가 ai · global이 아니면 위반
     */
    private static List<String> violations(String fileName, String source) {
        List<String> found = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("package ")) {
                continue;
            }
            Matcher m = ASSETORY_REFERENCE.matcher(line);
            while (m.find()) {
                if (!ALLOWED_TOP_PACKAGES.contains(m.group(1))) {
                    found.add(fileName + ":" + (i + 1) + ": " + line);
                    break;
                }
            }
        }
        return found;
    }

    private static final List<String> ALLOWED_TOP_PACKAGES = List.of("ai", "global");

    // com.project.assetory.<최상위 패키지>. — 그 뒤가 클래스 이름이든 하위 패키지든 와일드카드든 최상위 패키지로 판정한다
    private static final Pattern ASSETORY_REFERENCE = Pattern.compile("\\bcom\\.project\\.assetory\\.([a-z][a-z0-9]*)\\.");
}
