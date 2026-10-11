package com.project.assetory.connect.exception;

// 금융기관 사이트 변경/점검으로 인해 조회 불가 (CF-12701, CF-12710)
// 연동 비활성화 없이 "조회 불가" 안내만 반환
public class CodefInstitutionUnavailableException extends RuntimeException {

    private final String codefCode;

    public CodefInstitutionUnavailableException(String codefCode) {
        super("현재 해당 금융기관 시스템 개편으로 조회가 불가능합니다. 금융사의 조치가 완료되면 순차적으로 반영될 예정입니다. [" + codefCode + "]");
        this.codefCode = codefCode;
    }

    public String getCodefCode() {
        return codefCode;
    }
}
