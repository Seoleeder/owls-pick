package io.github.seoleeder.owls_pick.entity.genai.enums;

public enum GenaiFailReason {
    INSUFFICIENT_DATA,        // 유효 데이터 부족
    SAFETY_FILTER_REJECTED,   // 모델 안전 필터 거부
    NETWORK_ERROR,            // API 통신 장애
    INVALID_RESPONSE,         // 비정상 응답 포맷
    UNKNOWN_ERROR             // 기타 예외
}