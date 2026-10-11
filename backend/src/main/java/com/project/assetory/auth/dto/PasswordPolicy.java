package com.project.assetory.auth.dto;

// FR-AU-01 비밀번호 정책 — 회원가입·비밀번호 재설정이 같은 규칙을 쓴다
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 20;
    public static final String LENGTH_MESSAGE = "비밀번호는 8자 이상 20자 이하로 입력해주세요";
    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).+$";
    public static final String REGEX_MESSAGE = "비밀번호는 영문자, 숫자, 특수문자를 포함하여야 합니다.";
}
