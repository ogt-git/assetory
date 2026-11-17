package com.project.assetory.ai.guard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM 원인 요약 출력 검사기 (AI-3, Q-7) — 스프링 없는 순수 클래스. 걸리면 호출하는 쪽(AI-5)이 요약을 버리고 분석을 FAILED로 둔다.
 * 문장을 고쳐 쓰지 않는다(LLM 문장을 코드가 바꾸지 않음). 요약 원문은 결과 · 예외에 담지 않는다(AGENTS 9장).
 *
 * 순서: ① 입력 문자열(공시 제목 · 종목명) 가리기(D1) — 완전 일치 · 앞 [정정] 접두어 생략 · 공백 차이까지, 일부 인용은 가리지 않음
 * ② 금지 패턴(CAUSAL · SOLICIT · FORECAST) — 문맥이 아니라 형태로 잡는다(부정 · 면책 문장도 잡힘, 안전한 쪽)
 * ③ 숫자 대조 — 날짜 → 시각 → 연도 → 건수 · 서수 → 일반 숫자 순으로 찾아 확인한 부분은 지운다.
 *    일반 숫자는 절댓값 · 입력 값의 자릿수별 HALF_UP 반올림까지 허용(D4, 입력끼리 계산한 값은 불허), 건수 · 서수는 입력 정수와 정확히 같아야 한다.
 *    가린 입력 문자열 안의 숫자도 입력 숫자다.
 * 한계: 부호를 보지 않아 "하락"을 "상승"으로 써도 잡지 못한다. 숫자 종류를 구분하지 않아 다른 뜻으로 쓴 같은 숫자는 통과한다
 */
public class LlmOutputGuard {

    private static final String MASK = "□";
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^\\s*\\[[^\\]]*\\]\\s*");

    private static final List<Pattern> FULL_DATES = List.of(
            Pattern.compile("(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})"),
            Pattern.compile("(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"));
    private static final List<Pattern> MONTH_DAYS = List.of(
            Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"),
            Pattern.compile("(?<![\\d.])(\\d{1,2})/(\\d{1,2})(?![\\d/])"));
    private static final Pattern TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(\\d{4})\\s*년");
    private static final Pattern COUNT = Pattern.compile("(?<![\\d.])(\\d+)\\s*(건|개|곳|회차|차|회|호|번째)");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])[+\\-−]?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?");

    private final Map<OutputRule, List<Pattern>> patterns = new EnumMap<>(OutputRule.class);

    public LlmOutputGuard(OutputGuardProperties properties) {
        // 한 번만 컴파일 — 잘못된 정규식이면 PatternSyntaxException으로 빈 생성(기동)이 실패한다
        patterns.put(OutputRule.CAUSAL, compile(properties.causal()));
        patterns.put(OutputRule.SOLICIT, compile(properties.solicit()));
        patterns.put(OutputRule.FORECAST, compile(properties.forecast()));
    }

    public OutputGuardResult check(String summary, OutputGuardContext context) {
        if (summary == null || summary.isBlank()) {
            return new OutputGuardResult(EnumSet.of(OutputRule.EMPTY));
        }
        Set<OutputRule> violations = EnumSet.noneOf(OutputRule.class);
        String masked = mask(summary, context.quotedTexts());
        patterns.forEach((rule, list) -> {
            if (list.stream().anyMatch(p -> p.matcher(masked).find())) {
                violations.add(rule);
            }
        });
        if (!numbersGrounded(masked, context)) {
            violations.add(OutputRule.UNGROUNDED_NUMBER);
        }
        return new OutputGuardResult(violations);
    }

    private static List<Pattern> compile(List<String> regexes) {
        return regexes.stream().map(Pattern::compile).toList();
    }

    // 긴 문자열부터 가린다 — 짧은 입력(종목명)이 긴 제목의 일부를 먼저 가려 제목 인용이 깨지지 않게
    private static String mask(String text, List<String> quotedTexts) {
        String result = text;
        List<String> sorted = quotedTexts.stream().filter(q -> q != null && !q.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String quoted : sorted) {
            for (String variant : List.of(quoted, BRACKET_PREFIX.matcher(quoted).replaceFirst(""))) {
                Pattern pattern = spaceInsensitive(variant);
                if (pattern != null) {
                    result = pattern.matcher(result).replaceAll(MASK);
                }
            }
        }
        return result;
    }

    // 공백을 뺀 글자 사이에 \s*를 둔다 — "추천위원회 설치" · "추천위원회설치" · "추천 위원회 설치"가 모두 같은 인용
    private static Pattern spaceInsensitive(String text) {
        String body = text.codePoints().filter(cp -> !Character.isWhitespace(cp))
                .mapToObj(cp -> Pattern.quote(new String(Character.toChars(cp))))
                .collect(Collectors.joining("\\s*"));
        return body.isEmpty() ? null : Pattern.compile(body);
    }

    private static boolean numbersGrounded(String masked, OutputGuardContext context) {
        Set<LocalDate> dates = Set.copyOf(context.dates());
        Set<MonthDay> monthDays = context.dates().stream().map(MonthDay::from).collect(Collectors.toSet());
        Set<Integer> years = context.dates().stream().map(LocalDate::getYear).collect(Collectors.toSet());
        Set<LocalTime> times = Set.copyOf(context.times());

        List<BigDecimal> inputs = new ArrayList<>(context.numbers());
        context.quotedTexts().forEach(q -> inputs.addAll(numbersIn(q)));
        NavigableSet<BigDecimal> rounded = roundedSet(inputs);
        NavigableSet<BigDecimal> exactIntegers = exactIntegerSet(inputs);

        boolean[] grounded = {true};
        String text = masked;
        for (Pattern p : FULL_DATES) {
            text = consume(text, p, m -> {
                try {
                    return dates.contains(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3))));
                } catch (DateTimeException e) {
                    return false;
                }
            }, grounded);
        }
        for (Pattern p : MONTH_DAYS) {
            text = consume(text, p, m -> {
                try {
                    return monthDays.contains(MonthDay.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                } catch (DateTimeException e) {
                    return false;
                }
            }, grounded);
        }
        text = consume(text, TIME, m -> times.contains(LocalTime.of(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)))), grounded);
        text = consume(text, YEAR, m -> years.contains(Integer.parseInt(m.group(1))), grounded);
        text = consume(text, COUNT, m -> exactIntegers.contains(new BigDecimal(m.group(1))), grounded);
        consume(text, NUMBER, m -> rounded.contains(toNumber(m)), grounded);
        return grounded[0];
    }

    // 찾은 부분을 확인하고 공백으로 지운다 — 다음 단계가 같은 숫자를 다시 보지 않게
    private static String consume(String text, Pattern pattern, Predicate<Matcher> allowed, boolean[] grounded) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (!allowed.test(m)) {
                grounded[0] = false;
            }
            m.appendReplacement(out, " ");
        }
        m.appendTail(out);
        return out.toString();
    }

    private static List<BigDecimal> numbersIn(String text) {
        if (text == null) {
            return List.of();
        }
        List<BigDecimal> result = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            result.add(toNumber(m));
        }
        return result;
    }

    private static BigDecimal toNumber(Matcher m) {
        String integer = m.group(1).replace(",", "");
        return new BigDecimal(m.group(2) == null ? integer : integer + "." + m.group(2));
    }

    // 절댓값 + 소수 자릿수마다 HALF_UP 반올림 — 입력 7.2300 → 7.23 · 7.2 · 7 (compareTo 비교라 7.20 = 7.2)
    private static NavigableSet<BigDecimal> roundedSet(List<BigDecimal> inputs) {
        NavigableSet<BigDecimal> set = new TreeSet<>();
        for (BigDecimal input : inputs) {
            BigDecimal abs = input.abs().stripTrailingZeros();
            set.add(abs);
            for (int scale = 0; scale < abs.scale(); scale++) {
                set.add(abs.setScale(scale, RoundingMode.HALF_UP));
            }
        }
        return set;
    }

    private static NavigableSet<BigDecimal> exactIntegerSet(List<BigDecimal> inputs) {
        NavigableSet<BigDecimal> set = new TreeSet<>();
        for (BigDecimal input : inputs) {
            BigDecimal abs = input.abs();
            if (abs.signum() == 0 || abs.stripTrailingZeros().scale() <= 0) {
                set.add(abs);
            }
        }
        return set;
    }
}
