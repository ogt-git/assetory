package com.project.assetory.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class SignupRequest {

    @NotBlank(message = "이메일을 입력해주세요")
    @Email(message = "올바른 이메일 형식으로 입력해주세요 (예: example@email.com)")
    @Size(max = 50, message = "이메일은 50자 이하로 입력해주세요")
    private String email;

    @NotBlank(message = "비밀번호를 입력해주세요")
    @Size(min = 8, max = 20, message = "비밀번호는 8자 이상 20자 이하로 입력해주세요")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).+$",
        message = "비밀번호는 영문자, 숫자, 특수문자를 포함하여야 합니다."
    )
    private String password;

    @NotBlank(message = "닉네임을 입력해주세요")
    @Size(max = 30, message = "닉네임은 30자 이하로 입력해주세요")
    private String nickname;

    @NotBlank(message = "약관 버전을 입력해주세요")
    private String termsVersion;

    @NotBlank(message = "이메일 인증을 완료해주세요.")
    private String verificationToken;
}
