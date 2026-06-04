package io.github.seoleeder.owls_pick.service.auth;

import io.github.seoleeder.owls_pick.client.oauth.provider.NaverWebhookProcessor;
import io.github.seoleeder.owls_pick.global.config.properties.SocialProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @InjectMocks
    private WebhookService webhookService;

    @Mock private SocialProperties socialProperties;
    @Mock private AuthService authService;
    @Mock private NaverWebhookProcessor naverWebhookProcessor;

    @BeforeEach
    void setUp() {
        // [Setup] 카카오 및 네이버 웹훅 인증 처리에 필요한 SocialProperties 설정 데이터 구성
        SocialProperties.Registration kakaoReg = new SocialProperties.Registration(
                "kakao-client-id", "dummy-secret", "uri", Set.of("profile"), "code", null, null
        );
        SocialProperties.Registration naverReg = new SocialProperties.Registration(
                "dummy-id", "naver-secret", "uri", Set.of("profile"), "code", null, null
        );

        given(socialProperties.registration()).willReturn(Map.of(
                "kakao", kakaoReg,
                "naver", naverReg
        ));
    }

    @Test
    @DisplayName("[카카오 웹훅] Admin Key 헤더 검증 성공 시 회원 탈퇴 로직 호출 확인")
    void handleKakaoUnlink_Success() {
        // [Given] 카카오 규격에 맞는 Authorization 헤더 및 가입자 식별자 세팅
        String validHeader = "KakaoAK kakao-client-id";
        String providerId = "12345";

        // [When] 카카오 연동 해제 웹훅 메서드 호출
        webhookService.handleKakaoUnlink(validHeader, providerId);

        // [Then] 헤더 검증 통과 후 AuthService의 계정 연동 해제 로직이 정상 호출되었는지 검증
        verify(authService, times(1)).unlinkSocialAccount("KAKAO", providerId);
    }

    @Test
    @DisplayName("[카카오 웹훅] 인증 헤더 불일치 시 예외 발생 및 하위 로직 호출 차단 검증")
    void handleKakaoUnlink_Fail_Unauthorized() {
        // [Given] 인가되지 않은 잘못된 카카오 Admin Key 헤더 세팅
        String invalidHeader = "KakaoAK invalid-key";
        String providerId = "12345";

        // [When & Then] 헤더 검증 실패 시 UNAUTHORIZED 예외가 발생하고, 내부 로직이 호출되지 않는지 검증
        assertThatThrownBy(() -> webhookService.handleKakaoUnlink(invalidHeader, providerId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        verify(authService, never()).unlinkSocialAccount(anyString(), anyString());
    }

    @Test
    @DisplayName("[네이버 웹훅] 서명 검증 및 식별자 복호화 성공 시 AuthService 위임 검증")
    void handleNaverUnlink_Success() throws Exception {
        // [Given] 네이버 웹훅 페이로드 파라미터 세팅
        String clientId = "client";
        String encryptId = "encrypted";
        String timestamp = "1234567890";
        String signature = "valid-signature";

        // [Given] NaverWebhookProcessor를 통한 서명 검증 및 복호화 통과 상태(naver_123 반환) 모킹
        given(naverWebhookProcessor.process(clientId, encryptId, timestamp, signature, "naver-secret"))
                .willReturn("naver_123");

        // [When] 네이버 연동 해제 웹훅 메서드 호출
        webhookService.handleNaverUnlink(clientId, encryptId, timestamp, signature);

        // [Then] 복호화가 완료된 유저 식별자(naver_123)로 계정 삭제 로직이 정상 호출되었는지 검증
        verify(authService, times(1)).unlinkSocialAccount("NAVER", "naver_123");
    }

    @Test
    @DisplayName("[네이버 웹훅] 변조된 서명 감지 시 예외 처리 및 하위 로직 호출 차단 검증")
    void handleNaverUnlink_Fail_Signature_Mismatch() throws Exception {
        // [Given] 서명 검증 컴포넌트(Processor) 연산 중 INVALID_SIGNATURE 예외가 발생하는 상황 모킹
        given(naverWebhookProcessor.process(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willThrow(new CustomException(ErrorCode.INVALID_SIGNATURE));

        // [When & Then] 예외를 캐치하여 INTERNAL_SERVER_ERROR를 반환하고, 하위 서비스 호출을 차단하는지 확인
        assertThatThrownBy(() -> webhookService.handleNaverUnlink("c", "e", "t", "s"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_SERVER_ERROR);

        verify(authService, never()).unlinkSocialAccount(anyString(), anyString());
    }
}