package com.project.assetory.analysis.dart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// DART 테스트 공용 — 테스트 전용 더미 키 · 설정 · corpCode ZIP 생성
final class DartTestSupport {

    static final String BASE_URL = "https://opendart.test/api";
    /** 테스트 전용 가짜 키 — 실제 키가 아니다. 로그 · 예외 · 메트릭에 이 문자열이 나오면 유출이다 */
    static final String TEST_KEY = "TEST-ONLY-DART-KEY-7f3a9c";

    private DartTestSupport() {
    }

    static DartProperties properties(String apiKey, int maxPages) {
        return new DartProperties(BASE_URL, apiKey, List.of("B", "I", "D"), maxPages, Duration.ofSeconds(30));
    }

    /** CORPCODE.xml 한 파일이 든 ZIP — 실제 파일 구조(OpenDART 개발가이드 고유번호) 기준 더미 값 */
    static byte[] corpCodeZip(String xml) {
        return zip("CORPCODE.xml", xml);
    }

    static byte[] zip(String entryName, String content) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 상장 2개(테스트전자 · 테스트바이오) + 비상장 1개(stock_code 공백 — 실제 파일은 비상장을 공백 1칸으로 준다) */
    static final String CORP_CODE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <result>
                <list>
                    <corp_code>00999901</corp_code>
                    <corp_name>테스트전자</corp_name>
                    <corp_eng_name>TEST ELECTRONICS</corp_eng_name>
                    <stock_code>999901</stock_code>
                    <modify_date>20261101</modify_date>
                </list>
                <list>
                    <corp_code>00999902</corp_code>
                    <corp_name>테스트비상장</corp_name>
                    <corp_eng_name>TEST PRIVATE</corp_eng_name>
                    <stock_code> </stock_code>
                    <modify_date>20261101</modify_date>
                </list>
                <list>
                    <corp_code>00999903</corp_code>
                    <corp_name>테스트바이오</corp_name>
                    <corp_eng_name>TEST BIO</corp_eng_name>
                    <stock_code>999903</stock_code>
                    <modify_date>20261101</modify_date>
                </list>
            </result>
            """;
}
