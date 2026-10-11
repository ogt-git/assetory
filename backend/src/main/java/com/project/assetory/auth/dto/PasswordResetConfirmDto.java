package com.project.assetory.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class PasswordResetConfirmDto {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "인증코드는 6자리 숫자여야 합니다.")
    private String otp;

    // 새 비밀번호에도 회원가입과 같은 정책(FR-AU-01) 적용
    @NotBlank
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
    @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.REGEX_MESSAGE)
    private String newPassword;
}
