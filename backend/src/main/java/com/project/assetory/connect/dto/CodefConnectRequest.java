package com.project.assetory.connect.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodefConnectRequest {

    private String organization;  // 금융기관 코드
    private String businessType;  // ST=증권

    // "0": 인증서 방식, "1": 아이디/패스워드 방식
    private String loginType;

    // 인증서 방식 전용 (loginType "0") — 서버에서 파일을 받아 Base64 인코딩한 값
    private String derFileBase64;
    private String keyFileBase64;

    // 아이디/패스워드 방식 전용 (loginType "1")
    private String id;

    // 공통 필드
    private String password;   // 인증서 방식: 인증서 비밀번호 / 아이디 방식: 계정 비밀번호
    private String birthDate;  // 선택 (아이디 방식 일부 기관)
}
