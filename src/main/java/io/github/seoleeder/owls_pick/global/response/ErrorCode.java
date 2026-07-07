package io.github.seoleeder.owls_pick.global.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    // Test Error
    TEST_ERROR(10000, HttpStatus.BAD_REQUEST, "테스트 에러입니다."),

    //400 Bad Request
    INVALID_REQUEST(40000, HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    INVALID_PARAMETER(40001, HttpStatus.BAD_REQUEST, "잘못된 요청 파라미터입니다."),
    UNSUPPORTED_PROVIDER(40002, HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 제공자입니다."),
    INVALID_AUTHORIZATION_CODE(40003, HttpStatus.BAD_REQUEST, "인가 코드가 만료되었거나 유효하지 않습니다. "),

    // 검색 API용
    INVALID_PRICE_RANGE(40004, HttpStatus.BAD_REQUEST, "최소 가격은 최대 가격보다 클 수 없습니다."),
    INVALID_PLAYTIME_RANGE(40005, HttpStatus.BAD_REQUEST, "최소 플레이타임은 최대 플레이타임보다 클 수 없습니다."),
    SEARCH_KEYWORD_TOO_LONG(40006, HttpStatus.BAD_REQUEST, "검색어는 최대 50자까지만 입력 가능합니다."),

    INVALID_INPUT_VALUE(40007, HttpStatus.BAD_REQUEST, "잘못된 입력값입니다."),

    // 401 Unauthorized
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED, "인증이 유효하지 않습니다"),
    ADMIN_KEY_ERROR(40101, HttpStatus.UNAUTHORIZED, "관리자 인증이 유효하지 않습니다"),
    EXPIRED_TOKEN(40102, HttpStatus.UNAUTHORIZED,"JWT 토큰이 만료되었습니다"),
    INVALID_TOKEN(40103, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    MALFORMED_TOKEN(40104, HttpStatus.UNAUTHORIZED, "잘못된 형식의 토큰입니다."),
    REVOKED_REFRESH_TOKEN(40105,HttpStatus.UNAUTHORIZED, "폐기되었거나 만료된 리프레시 토큰입니다."),
    INVALID_SIGNATURE(40106,HttpStatus.UNAUTHORIZED, "서명이 유효하지 않습니다."),

    // 403 Forbidden
    FORBIDDEN(40300,HttpStatus.FORBIDDEN, "금지된 요청입니다."),
    UNCONSENTED_NOTIFICATION(40301,HttpStatus.FORBIDDEN, "알림 수신에 동의하지 않은 사용자입니다."),
    NOT_SESSION_OWNER(40302, HttpStatus.FORBIDDEN, "해당 채팅 세션에 대한 권한이 없습니다."),

    // 404 Not Found
    NOT_FOUND_END_POINT(40400, HttpStatus.NOT_FOUND, "존재하지 않는 API입니다."),
    NOT_FOUND_USER(40401, HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
    NOT_FOUND_GAME(40402, HttpStatus.NOT_FOUND, "존재하지 않는 게임입니다."),
    NOT_FOUND_NOTIFICATION(40403, HttpStatus.NOT_FOUND, "존재하지 않는 알림입니다."),
    NOT_FOUND_SESSION(40404, HttpStatus.NOT_FOUND, "존재하지 않는 세션입니다."),
    NOT_FOUND_REVIEW_STAT(40405, HttpStatus.NOT_FOUND, "존재하지 않는 리뷰 통계 데이터입니다."),

    // 409 Conflict
    CONFLICT(40900,HttpStatus.CONFLICT,""),
    ALREADY_ONBOARDED(40901, HttpStatus.CONFLICT, "이미 온보딩이 완료되었습니다."),
    DUPLICATE_NICKNAME(40902, HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),

    // 429 Too Many Requests
    TOO_MANY_REQUESTS(42900, HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    CHATBOT_PROCESSING(42901, HttpStatus.TOO_MANY_REQUESTS, "답변을 생성 중입니다. 잠시만 기다려주세요."),

    // 500 Internal Server Error
    INTERNAL_SERVER_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류입니다."),
    OAUTH_SERVER_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "소셜 로그인 서버와 통신 중 오류가 발생했습니다."),
    FASTAPI_COMMUNICATION_FAILED(50002,HttpStatus.INTERNAL_SERVER_ERROR, "FastAPI와의 통신에 실패했습니다."),
    INVALID_LOCALIZATION_RESPONSE(50003,HttpStatus.INTERNAL_SERVER_ERROR, "한글화 엔진으로부터 유효하지 않은 응답을 받았습니다.");

    private final Integer code;
    private final HttpStatus httpStatus;
    private final String message;
}
