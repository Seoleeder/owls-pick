package io.github.seoleeder.owls_pick.controller.auth;

import io.github.seoleeder.owls_pick.client.oauth.factory.SocialAuthFactory;
import io.github.seoleeder.owls_pick.client.oauth.provider.SocialAuthProvider;
import io.github.seoleeder.owls_pick.dto.auth.SocialTokenDto;
import io.github.seoleeder.owls_pick.dto.auth.SocialUserResponse;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.security.jwt.JwtTokenProvider;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuthIntegrationTest extends AbstractContainerBaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;


    @MockitoBean
    private SocialAuthFactory socialAuthFactory;

    // 여러 Provider 구현체 중 충돌을 막기 위해 덮어쓸 빈 이름 명시
    @MockitoBean(name = "kakaoAuthProvider")
    private SocialAuthProvider socialAuthProvider;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void tearDown() {
        // FK 제약 조건 위반을 방지하기 위해 자식 테이블부터 삭제
        socialAccountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("[통합] 소셜 로그인 정상 호출 시 JWT 발급 및 Redis 적재 확인")
    void social_login_integration_success() throws Exception {
        // [Given] 카카오 소셜 API 응답 데이터 스텁 세팅 (state 값 null 허용)
        String provider = "kakao";
        String code = "valid-auth-code";
        SocialTokenDto tokenDto = new SocialTokenDto("valid-access-token", "valid-id-token");
        SocialUserResponse userResponse = new SocialUserResponse("12345", "test@kakao.com", "카카오테스터");

        given(socialAuthFactory.getProvider(provider)).willReturn(socialAuthProvider);
        given(socialAuthProvider.fetchAccessToken(anyString(), any())).willReturn(tokenDto);
        given(socialAuthProvider.getUserInfo(any(SocialTokenDto.class))).willReturn(userResponse);

        // [When & Then] 로그인 API 호출 및 JSON 응답 확인
        mockMvc.perform(post("/api/auth/login/{provider}", provider)
                        .param("code", code)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andExpect(jsonPath("$.data.nickname").value("카카오테스터"));

        // [Then] DB에 유저가 저장되고, Redis에 리프레시 토큰이 적재되었는지 확인
        User savedUser = userRepository.findByEmail("test@kakao.com").orElseThrow();
        Boolean hasToken = redisTemplate.hasKey("RT:" + savedUser.getId());
        assertThat(hasToken).isTrue();
    }

    @Test
    @DisplayName("[통합] 인가(Authorization) 헤더 누락 시 Security 필터 차단 및 전역 에러 포맷 반환 확인")
    void logout_without_token_fails() throws Exception {
        // [When & Then] 인증 헤더 없이 요청 시 SecurityFilterChain에서 401 차단 확인
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    @DisplayName("[통합] 조작되거나 만료된 리프레시 토큰으로 재발급 요청 시 커스텀 예외 반환 확인")
    void reissue_with_invalid_refresh_token() throws Exception {
        // [When & Then] 유효하지 않은 토큰 요청 시 전역 예외 처리 응답 확인
        mockMvc.perform(post("/api/auth/reissue")
                        .header("Refresh-Token", "invalid.token.string")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.message").exists());
    }
}