package com.project.assetory.detection.exception;

import com.project.assetory.global.exception.ErrorCode;
import lombok.Getter;

import java.util.Map;

/**
 * 감지 설정 저장 거부 (400) — 응답 문구는 ErrorCode 메시지만 쓴다(입력값 미포함).
 * data는 클라이언트 안내용 값(허용 범위 minValue · maxValue · unit)이며 없으면 null
 */
@Getter
public class DetectionSettingInvalidException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> data;

    public DetectionSettingInvalidException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public DetectionSettingInvalidException(ErrorCode errorCode, Map<String, Object> data) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.data = data;
    }
}
