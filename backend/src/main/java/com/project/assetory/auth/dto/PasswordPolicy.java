package com.project.assetory.auth.dto;

import java.nio.charset.StandardCharsets;

// FR-AU-01 비밀번호 정책 — 회원가입·비밀번호 재설정이 같은 규칙을 쓴다
// 8~64자 + UTF-8 72바이트 이하 (Bcrypt는 72바이트를 넘는 입력을 해싱하지 않는다). 입력을 자르거나 trim하지 않는다
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    public static final int MAX_BYTES = 72;
    public static final String LENGTH_MESSAGE = "비밀번호는 8자 이상 64자 이하로 입력해주세요";
    public static final String BYTES_MESSAGE =
            "비밀번호가 너무 깁니다. 한글·이모지는 글자당 여러 바이트를 차지해 64자보다 짧아도 사용할 수 없을 수 있습니다 (최대 72바이트)";
    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).+$";
    public static final String REGEX_MESSAGE = "비밀번호는 영문자, 숫자, 특수문자를 포함하여야 합니다.";

    // null은 @NotBlank가 처리한다
    public static boolean withinBcryptLimit(String password) {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
