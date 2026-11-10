package com.project.assetory.analysis.naver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 비교 전용 텍스트 정리 — 태그 제거 + HTML 엔티티 디코드. **저장값을 바꾸는 데 쓰지 않는다**(원문 그대로 저장, B3 확정 사항 3 · 4).
 * 이름 엔티티는 검색 응답에 나오는 5개(&amp; &lt; &gt; &quot; &apos;)와 &nbsp;, 숫자 엔티티(&#39; &#x27;)를 푼다. 새 라이브러리 없음
 */
final class NaverHtml {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC = Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);");

    private NaverHtml() {
    }

    static String plainText(String html) {
        if (html == null) return "";
        String text = TAG.matcher(html).replaceAll("");
        Matcher m = NUMERIC.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (m.find()) {
            String code = m.group(1);
            int codePoint;
            try {
                codePoint = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            } catch (NumberFormatException e) {
                m.appendReplacement(decoded, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(decoded, Matcher.quoteReplacement(
                    Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : m.group()));
        }
        m.appendTail(decoded);
        // &amp;는 마지막에 풀어야 "&amp;lt;" 같은 이중 인코딩을 한 번만 푼다
        return decoded.toString()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
                .strip();
    }
}
