package io.github.seoleeder.owls_pick.config;

import io.github.seoleeder.owls_pick.global.config.AdminAuthorizationInterceptor;
import io.github.seoleeder.owls_pick.global.config.properties.AdminProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 어드민 전용 API 접근 비밀키 헤더 검증 인터셉터 순수 로직 테스트
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthorizationInterceptorTest {

    @InjectMocks
    private AdminAuthorizationInterceptor interceptor;

    @Mock
    private AdminProperties adminProperties;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private Object handler;

    @BeforeEach
    void setUp() {
        // [Given] 테스트용 HTTP 요청/응답 객체 초기화
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        handler = new Object();
    }

    @Test
    @DisplayName("올바른 어드민 키 헤더 통과 검증")
    void preHandle_Success() throws Exception {
        // [Given] 유효한 어드민 키 모킹 및 요청 헤더 설정
        given(adminProperties.adminKey()).willReturn("valid-key");
        request.addHeader("X-ADMIN-KEY", "valid-key");

        // [When] 인터셉터 로직 직접 호출
        boolean result = interceptor.preHandle(request, response, handler);

        // [Then] 요청이 차단되지 않고 통과(true 반환)됨을 확인
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("잘못된 어드민 키 헤더 차단 검증")
    void preHandle_Fail_WrongKey() throws Exception {
        // [Given] 유효한 어드민 키 모킹 및 잘못된 요청 헤더 설정
        given(adminProperties.adminKey()).willReturn("valid-key");
        request.addHeader("X-ADMIN-KEY", "wrong-key");

        // [When & Then] 인터셉터 호출 시 ADMIN_KEY_ERROR 예외 발생 확인
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_KEY_ERROR);
    }

    @Test
    @DisplayName("인증 헤더 누락 차단 검증")
    void preHandle_Fail_NoHeader() throws Exception {

        // [Given] 유효한 어드민 키 모킹 및 헤더 없는 요청 객체 준비
        given(adminProperties.adminKey()).willReturn("valid-key");

        // [When & Then] 헤더 누락 시 ADMIN_KEY_ERROR 예외 발생 확인
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_KEY_ERROR);
    }
}