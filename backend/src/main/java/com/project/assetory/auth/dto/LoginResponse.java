package com.project.assetory.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse {
    private Long userId;
    private String accessToken;
    private String nickname;
    private String email;
}
