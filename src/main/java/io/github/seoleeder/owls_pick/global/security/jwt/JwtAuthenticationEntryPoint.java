package io.github.seoleeder.owls_pick.global.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.seoleeder.owls_pick.global.response.CommonResponse;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * [인증 실패 처리기]
 * 사용자가 인증 없이(또는 유효하지 않은 토큰으로) 보호된 리소스에 접근하려 할 때 동작합니다.
 * - 401 Unauthorized 에러를 리턴합니다.
 * - JSON 형태로 에러 응답을 내려줍니다.
 */
@Slf4j
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {

        //Resuest Attribute에서 예외 원인 조회
        Object exceptionAttribute = request.getAttribute("exception");

        //에러 코드 기본값 설정 (인증이 유효하지 X)
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;

        //속성이 존재하면 해당 ErrorCode로 덮어쓰기
        if (exceptionAttribute instanceof ErrorCode) {
            errorCode = (ErrorCode) exceptionAttribute;
        }

        // 에러 로그 기록
        log.warn("인증 실패 (401 Unauthorized) - URI: {}, Error: {}", request.getRequestURI(), authException.getMessage());

        // HTTP 응답 헤더 설정
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE); // "application/json"
        response.setCharacterEncoding("UTF-8");

        // CommonResponse 객체 생성
        CommonResponse<Object> errorResponse = CommonResponse.fail(ErrorCode.UNAUTHORIZED);

        // JSON 변환 (Serialize)
        // 필터 단에서는 @RestControllerAdvice가 동작하지 않으므로 직접 변환해야 함
        String jsonResponse = objectMapper.writeValueAsString(errorResponse);

        // 응답 본문에 쓰기
        response.getWriter().write(jsonResponse);
    }
}
