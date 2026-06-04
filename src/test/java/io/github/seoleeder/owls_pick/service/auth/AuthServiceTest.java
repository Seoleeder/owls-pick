package io.github.seoleeder.owls_pick.service.auth;

import io.github.seoleeder.owls_pick.client.oauth.factory.SocialAuthFactory;
import io.github.seoleeder.owls_pick.client.oauth.provider.SocialAuthProvider;
import io.github.seoleeder.owls_pick.dto.auth.LoginResponse;
import io.github.seoleeder.owls_pick.dto.auth.SocialTokenDto;
import io.github.seoleeder.owls_pick.dto.auth.SocialUserResponse;
import io.github.seoleeder.owls_pick.entity.user.SocialAccount;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.global.security.config.properties.JwtProperties;
import io.github.seoleeder.owls_pick.global.security.jwt.JwtTokenProvider;
import io.github.seoleeder.owls_pick.repository.SocialAccountRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private SocialAuthFactory socialAuthFactory;
    @Mock private UserRepository userRepository;
    @Mock private SocialAccountRepository socialAccountRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations; // Redis 값 조작 Mock
    @Mock private JwtProperties jwtProperties;
    @Mock private SocialAuthProvider socialAuthProvider;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        // redisTemplate.opsForValue()가 호출될 때 mock 객체를 반환하도록 설정
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("[소셜 로그인] 기존 회원이 로그인하는 경우 토큰 발급 및 Redis 저장 검증")
    void login_existing_user_success() {
        // [Given] 소셜 로그인 팩토리 및 제공자(Provider) 응답 모킹
        SocialTokenDto tokenDto = new SocialTokenDto("access-token", "refresh-token");
        SocialUserResponse userInfo = new SocialUserResponse("12345", "test@kakao.com", "한지수");
        given(socialAuthFactory.getProvider(anyString())).willReturn(socialAuthProvider);
        given(socialAuthProvider.fetchAccessToken(anyString(), anyString())).willReturn(tokenDto);
        given(socialAuthProvider.getUserInfo(any())).willReturn(userInfo);

        // [Given] DB에 이미 연동된 계정(SocialAccount)이 존재하는 상태 세팅
        User user = User.builder().id(1L).email("test@kakao.com").name("한지수").build();
        SocialAccount socialAccount = SocialAccount.builder().user(user).build();
        given(socialAccountRepository.findByProviderAndProviderId(any(), anyString())).willReturn(Optional.of(socialAccount));

        // [Given] 신규 JWT 토큰 생성 결과 세팅
        given(jwtTokenProvider.createAccessToken(any(Authentication.class))).willReturn("new-access-token");
        given(jwtTokenProvider.createRefreshToken(anyString())).willReturn("new-refresh-token");
        given(jwtProperties.refreshTokenValidity()).willReturn(3600000L);

        // [When] 소셜 로그인 로직 실행
        LoginResponse response = authService.login("kakao", "code", "state");

        // [Then] 발급된 Access 토큰 반환 확인
        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.email()).isEqualTo("test@kakao.com");

        // [Then] Redis에 유저 PK를 Key("RT:1")로 Refresh Token이 정상 적재되었는지 확인
        verify(valueOperations, times(1)).set(
                eq("RT:1"), eq("new-refresh-token"), anyLong(), eq(TimeUnit.MILLISECONDS)
        );
    }

    @Test
    @DisplayName("[소셜 로그인] 신규 유저의 자동 회원가입 및 로그인 처리 검증")
    void login_new_user_success() {
        // [Given] 신규 유저 정보 API 응답 모킹
        SocialTokenDto tokenDto = new SocialTokenDto("ac", "rt");
        SocialUserResponse userInfo = new SocialUserResponse("999", "new@kakao.com", "신규유저");
        given(socialAuthFactory.getProvider(anyString())).willReturn(socialAuthProvider);
        given(socialAuthProvider.fetchAccessToken(anyString(), anyString())).willReturn(tokenDto);
        given(socialAuthProvider.getUserInfo(any())).willReturn(userInfo);

        // [Given] 연동된 소셜 계정도 없고 기존 유저 이메일도 없는 상태 설정
        given(socialAccountRepository.findByProviderAndProviderId(any(), anyString())).willReturn(Optional.empty());
        given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());

        // [Given] 신규 가입 저장 시 반환될 엔티티 식별자 세팅
        User newUser = User.builder().id(2L).email("new@kakao.com").name("신규유저").build();
        given(userRepository.save(any(User.class))).willReturn(newUser);

        SocialAccount newAccount = SocialAccount.builder().user(newUser).build();
        given(socialAccountRepository.save(any(SocialAccount.class))).willReturn(newAccount);

        // [When] 소셜 로그인 로직 실행
        authService.login("kakao", "code", "state");

        // [Then] 가입을 위한 User 및 SocialAccount 엔티티의 단건 저장 쿼리 호출 여부 확인
        verify(userRepository, times(1)).save(any(User.class));
        verify(socialAccountRepository, times(1)).save(any(SocialAccount.class));
    }

    @Test
    @DisplayName("[로그아웃] Redis 내 사용자 Refresh Token 삭제 확인")
    void logout_success() {
        // [Given] Redis에 해당 유저의 RT Key("RT:1")가 존재하는 상태 모킹
        given(redisTemplate.hasKey("RT:1")).willReturn(true);

        // [When] 로그아웃 실행
        authService.logout("1");

        // [Then] Redis 저장소에서 대상 키를 정상적으로 삭제하는지 검증
        verify(redisTemplate, times(1)).delete("RT:1");
    }

    @Test
    @DisplayName("[토큰 재발급] 유효한 Refresh Token 전달 시 Access Token 갱신 검증")
    void reissue_success() {
        // [Given] 클라이언트가 전달한 RT와 Redis 내 저장된 RT가 동일한 상황 구성
        String refreshToken = "valid-refresh-token";
        given(jwtTokenProvider.getUserIdFromToken(refreshToken)).willReturn("1");
        given(valueOperations.get("RT:1")).willReturn(refreshToken);

        // [Given] 유저 조회 및 신규 Access Token 발급 모킹
        User user = User.builder().id(1L).email("test@kakao.com").name("한지수").build();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(jwtTokenProvider.createAccessToken(any(Authentication.class))).willReturn("new-access-token");

        // [When] 재발급 로직 실행
        LoginResponse response = authService.reissue(refreshToken);

        // [Then] 검증 통과 후 신규 토큰 반환 여부 확인
        assertThat(response.accessToken()).isEqualTo("new-access-token");
        verify(jwtTokenProvider).validateToken(refreshToken);
    }

    @Test
    @DisplayName("[토큰 재발급] 탈취 의심 시나리오: 전달된 토큰과 Redis 토큰 불일치 예외 검증")
    void reissue_fail_token_mismatch() {
        // [Given] 클라이언트가 제출한 토큰과 Redis에 저장된 원본 토큰의 값이 다른 상태(탈취/만료 등) 모킹
        String reqToken = "stolen-refresh-token";
        given(jwtTokenProvider.getUserIdFromToken(reqToken)).willReturn("1");
        given(valueOperations.get("RT:1")).willReturn("original-refresh-token");

        // [When & Then] 불일치 감지 시 INVALID_TOKEN 커스텀 예외를 던지는지 검증
        assertThatThrownBy(() -> authService.reissue(reqToken))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("[회원 탈퇴] 영구 삭제에 따른 연관 엔티티 일괄 삭제 및 Redis 토큰 만료 처리 검증")
    void withdraw_success() {
        // [Given] Redis 내 사용자 토큰 존재 모킹
        given(redisTemplate.hasKey("RT:1")).willReturn(true);

        // [When] 탈퇴 로직 실행
        authService.withdraw("1");

        // [Then] Redis 토큰 삭제, 연동 계정(N) 삭제, 최종 유저(1) 영구 삭제 쿼리의 순차 호출 확인
        verify(redisTemplate).delete("RT:1");
        verify(socialAccountRepository).deleteByUserId(1L);
        verify(userRepository).deleteById(1L);
    }

    @Test
    @DisplayName("[소셜 웹훅] Provider 연동 해제 알림 시 기존 유저 탈퇴 위임 검증")
    void unlinkSocialAccount_success() {
        // [Given] 웹훅으로부터 받은 providerId로 기존 소셜 계정 조회 결과 구성
        User user = User.builder().id(5L).build();
        SocialAccount account = SocialAccount.builder().user(user).build();
        given(socialAccountRepository.findByProviderAndProviderId(SocialAccount.Provider.KAKAO, "12345"))
                .willReturn(Optional.of(account));

        // [Given] 내부 탈퇴에 포함된 Redis 호출 모킹
        given(redisTemplate.hasKey("RT:5")).willReturn(false);

        // [When] 연동 해제 처리 실행
        authService.unlinkSocialAccount("KAKAO", "12345");

        // [Then] 탈퇴 로직으로 위임되어 해당 유저(ID: 5)의 삭제 쿼리가 실행되는지 확인
        verify(userRepository).deleteById(5L);
    }

    @Test
    @DisplayName("[백도어] 소셜 제공자와의 통신 생략 후 이메일 기반 우회 가입 및 JWT 발급 검증")
    void bypassLogin_success() {
        // [Given] DB에 존재하지 않는 임의의 이메일 세팅 및 강제 저장 결과 모킹
        User user = User.builder().id(999L).email("backdoor@test.com").name("test_userbackdoor").build();
        given(userRepository.findByEmail("backdoor@test.com")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(user);

        given(jwtTokenProvider.createAccessToken(any())).willReturn("bt-at");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("bt-rt");

        // [When] 백도어 로그인 실행
        LoginResponse response = authService.bypassLogin("backdoor@test.com");

        // [Then] 정상 토큰 반환 및 백도어 전용 닉네임 형식 자동 생성 단언
        assertThat(response.accessToken()).isEqualTo("bt-at");
        assertThat(response.nickname()).isEqualTo("test_userbackdoor");
        verify(valueOperations).set(eq("RT:999"), eq("bt-rt"), anyLong(), any());
    }

    @Test
    @DisplayName("[백도어] 우회 로그인 유저의 이메일 기반 Redis 리프레시 토큰 강제 만료 처리 검증")
    void bypassLogout_success() {
        // [Given] 타겟 이메일에 해당하는 기존 유저 엔티티 세팅
        User user = User.builder().id(888L).email("out@test.com").build();
        given(userRepository.findByEmail("out@test.com")).willReturn(Optional.of(user));
        given(redisTemplate.hasKey("RT:888")).willReturn(true);

        // [When] 우회 로그아웃 실행
        authService.bypassLogout("out@test.com");

        // [Then] Redis 토큰 식별자를 유추하여 정상 삭제하는지 확인
        verify(redisTemplate).delete("RT:888");
    }
}
