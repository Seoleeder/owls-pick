package io.github.seoleeder.owls_pick.service.auth;

import io.github.seoleeder.owls_pick.client.oauth.provider.NaverWebhookProcessor;
import io.github.seoleeder.owls_pick.global.config.properties.SocialProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {
    private final SocialProperties socialProperties;
    private final AuthService authService;
    private final NaverWebhookProcessor naverWebhookProcessor;

    /**
     * 카카오 서비스 연동 해제 알림 처리 (연결 끊기 웹훅)
     * Client Id로 Authorization 헤더 검증 수행
     * @param authHeader 카카오가 전송한 'KakaoAK {Admin_Key}' 형식의 인증 헤더
     * @param providerId 연동 해제된 사용자의 provider Id
     * */
    @Transactional
    public void handleKakaoUnlink(String authHeader, String providerId) {
        String expected = "KakaoAK " + socialProperties.registration().get("kakao").clientId();
        if (authHeader == null || !authHeader.equals(expected)) {
            log.warn("[Webhook] KAKAO Unlink Request - Unauthorized attempt detected. Header mismatch for Provider ID: {}", providerId);
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        //사용자 회원 탈퇴 처리
        log.debug("[Webhook] KAKAO Unlink Request - Authentication passed. Delegating to AuthService.");
        authService.unlinkSocialAccount("KAKAO", providerId);
    }

    /**
     * 네이버 서비스 연동 해제 알림 처리 (연결 끊기 웹훅)
     * 서명 검증 및 식별자 복호화 과정 포함
     * @param clientId 애플리케이션 클라이언트 ID
     * @param encryptUniqueId 암호화된 provider Id
     * @param timestamp 요청 시점의 Epoch Time
     * @param signature HMAC-SHA256 서명값
     */
    @Transactional
    public void handleNaverUnlink(String clientId, String encryptUniqueId, String timestamp, String signature) {
        String secret = socialProperties.registration().get("naver").clientSecret();
        try {
            // 서명 검증과 provider Id 복호화
            String providerId = naverWebhookProcessor.process(clientId, encryptUniqueId, timestamp, signature, secret);

            log.debug("[Webhook] NAVER Unlink Request - Signature verified. Delegating to AuthService.");

            //사용자 회원 탈퇴 처리
            authService.unlinkSocialAccount("NAVER", providerId);
        } catch (Exception e) {
            log.warn("[Webhook] NAVER Unlink Request - Signature verification or decryption failed. Potential spoofing attempt.", e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
