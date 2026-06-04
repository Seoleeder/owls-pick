package io.github.seoleeder.owls_pick.controller.auth;

import io.github.seoleeder.owls_pick.client.oauth.provider.NaverWebhookProcessor;
import io.github.seoleeder.owls_pick.entity.user.SocialAccount;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.config.properties.SocialProperties;
import io.github.seoleeder.owls_pick.repository.SocialAccountRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// TODO: 향후 회원 탈퇴 로직 비동기화 시 @Transactional 제거 및 TestConfig 방식으로 전환 필요
@Transactional
class WebhookIntegrationTest extends AbstractContainerBaseTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SocialAccountRepository socialAccountRepository;

    // 프로퍼티 설정 주입 및 암호화 모듈 모킹
    @MockitoBean private SocialProperties socialProperties;
    @MockitoBean private NaverWebhookProcessor naverWebhookProcessor;

    private User targetUser;

    @BeforeEach
    void setUp() {
        // 웹훅 연동 해제 테스트용 유저 데이터 저장
        targetUser = userRepository.save(User.builder().email("kakao@test.com").name("카카오웹훅타겟").build());
        socialAccountRepository.save(SocialAccount.builder()
                .user(targetUser).provider(SocialAccount.Provider.KAKAO).providerId("12345").build());

        // 카카오 웹훅 Admin Key 검증용 프로퍼티 모킹
        SocialProperties.Registration kakaoReg = new SocialProperties.Registration(
                "real-client-id", "secret", "uri", Set.of(), "code", null, null
        );
        given(socialProperties.registration()).willReturn(Map.of("kakao", kakaoReg));
    }

    @Test
    @DisplayName("[통합] 카카오 연동 해제 웹훅 호출 시 Admin Key 인가 확인 및 계정 삭제 완료 검증")
    void kakao_webhook_unlink_success() throws Exception {
        // [When] 인가된 Admin Key와 올바른 providerId로 웹훅 요청
        mockMvc.perform(post("/api/webhook/kakao/unlink")
                        .header("Authorization", "KakaoAK real-client-id")
                        .param("user_id", "12345")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk()); // Controller 설계대로 200 OK 반환

        // DB 유저 데이터 삭제 여부 확인
        Optional<User> deletedUser = userRepository.findById(targetUser.getId());
        assertThat(deletedUser).isEmpty();
    }

    @Test
    @DisplayName("[통합] 인가되지 않은 헤더 유입 시 예외 발생 및 CommonResponse 반환 확인")
    void kakao_webhook_unlink_fail_unauthorized() throws Exception {
        // 잘못된 인가 헤더 유입 시 전역 에러 핸들러 동작 확인
        mockMvc.perform(post("/api/webhook/kakao/unlink")
                        .header("Authorization", "KakaoAK fake-client-id")
                        .param("user_id", "12345")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").exists()); // @RestControllerAdvice의 CommonResponse 처리 확인
    }

    @Test
    @DisplayName("[통합] 네이버 서명 검증 모듈 실패 시 전역 에러 핸들러 연동 확인")
    void naver_webhook_unlink_fail_signature() throws Exception {
        // 네이버 웹훅 서명 검증 실패 상태 모킹
        given(naverWebhookProcessor.process(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willThrow(new RuntimeException("Signature verification failed"));

        // 예외 발생 시 500 에러 및 공통 포맷 응답 확인
        mockMvc.perform(post("/api/webhook/naver/unlink")
                        .param("clientId", "testClient")
                        .param("encryptUniqueId", "encrypt")
                        .param("timestamp", "12345678")
                        .param("signature", "invalid-sig")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }
}