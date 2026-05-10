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
import io.github.seoleeder.owls_pick.repository.UserRepository;
import io.github.seoleeder.owls_pick.repository.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.jsonwebtoken.ExpiredJwtException;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SocialAuthFactory socialAuthFactory;
    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final JwtTokenProvider jwtTokenProvider;

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtProperties jwtProperties;

    /**
     * [소셜 로그인 로직]
     * 인가 코드와 state 값을 받아서 로그인 처리 및 JWT 토큰 발급
     * */
    @Transactional
    public LoginResponse login(String providerName, String code, String state) {
        // 1. 소셜 유저 정보 가져오기
        SocialAuthProvider provider = socialAuthFactory.getProvider(providerName);
        SocialTokenDto tokenDto = provider.fetchAccessToken(code, state);
        SocialUserResponse userInfo = provider.getUserInfo(tokenDto);

        // 2. 가입 또는 로그인
        SocialAccount.Provider enumProvider = SocialAccount.Provider.valueOf(providerName.toUpperCase());
        SocialAccount socialAccount = socialAccountRepository
                .findByProviderAndProviderId(enumProvider, userInfo.providerId())
                .orElseGet(() -> join(userInfo, enumProvider));

        User user = socialAccount.getUser();

        // 3. JWT 발급
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getId().toString(),
                null,
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))
        );

        String accessToken = jwtTokenProvider.createAccessToken(authentication);
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId().toString());

        try {
            // 4. Refresh Token을 Redis에 저장 (사용자 식별자를 Key로 사용)
            // Key: "RT:1" (RT:유저PK)
            // Value: eyJhbGci...
            // TTL: JwtProperties에 설정된 refreshTokenValidity 시간과 동일하게 설정
            String redisKey = "RT:" + user.getId();
            redisTemplate.opsForValue().set(
                    redisKey,
                    refreshToken,
                    jwtProperties.refreshTokenValidity(),
                    TimeUnit.MILLISECONDS
            );

            log.info("[Auth] User logged in successfully - Provider: {}, UserId: {}", providerName, user.getId());
        } catch (Exception e) {
            log.error("[Auth] Unexpected error during social login Redis processing - UserId: {}", user.getId(), e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .nickname(user.getName())
                .email(user.getEmail())
                .build();

    }

    /**
     * [회원가입 로직]
     * 로그인 과정에서 사용자 정보가 존재하지 않으면 회원가입 처리
     * */
    private SocialAccount join(SocialUserResponse userInfo, SocialAccount.Provider provider) {
        //사용자의 이메일 정보가 존재하지 않으면 임의의 문자열로 대체
        String email = userInfo.email() != null ? userInfo.email() : "no-email-" + userInfo.providerId();

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .email(email)
                            .name(userInfo.name())
                            .build();
                    return userRepository.save(newUser);
                });

        SocialAccount socialAccount = SocialAccount.builder()
                .user(user)
                .provider(provider)
                .providerId(userInfo.providerId())
                .build();

        SocialAccount savedAccount = socialAccountRepository.save(socialAccount);
        log.info("[Auth] New user registered - Provider: {}, UserId: {}", provider, user.getId());

        return savedAccount;
    }

    /**
     * [Access 토큰 재발급]
     * Refresh Token을 받아 검증 -> 새로운 토큰 발급
     * */
    @Transactional
    public LoginResponse reissue(String refreshToken) {
        // 프론트에서 받은 Refresh Token이 위조되거나 만료되지 않았는지 검증
        try {
            jwtTokenProvider.validateToken(refreshToken);
        } catch (ExpiredJwtException e) {
            log.warn("[Auth] Refresh token has expired.");
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        } catch (Exception e) {
            log.warn("[Auth] Invalid refresh token access detected.");
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        // Refresh Token에서 유저 PK 추출
        String userId = jwtTokenProvider.getUserIdFromToken(refreshToken);

        // Redis에서 해당 유저의 Refresh Token 조회
        String redisKey = "RT:" + userId;
        String storedRefreshToken = redisTemplate.opsForValue().get(redisKey);

        // Redis에 값이 존재하고 값이 일치한지 검증

        if (storedRefreshToken == null) {
            //Redis에 값이 존재하지 X
            log.warn("[Auth] Refresh token not found in Redis (Logged out or Expired) - UserId: {}", userId);
            throw new CustomException(ErrorCode.REVOKED_REFRESH_TOKEN);
        }
        if (!storedRefreshToken.equals(refreshToken)) {
            //Redis의 값과 일치하지 않음
            log.warn("[Auth] Requested refresh token does not match Redis (Potential Hijacking) - UserId: {}", userId);
            // redisTemplate.delete(redisKey);
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        // 검증 완료 후 사용자 정보 조회
        User user = userRepository.findById(Long.valueOf(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_USER)); // 유저 찾을 수 없음 예외

        // 새로운 Access Token 발급
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userId, null, Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))
        );
        String newAccessToken = jwtTokenProvider.createAccessToken(authentication);

        log.info("[Auth] Access Token successfully reissued - UserId: {}", userId);

        return LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken)
                .nickname(user.getName())
                .email(user.getEmail())
                .build();
    }

    /**
     * [로그아웃] Redis에서 해당 유저의 Refresh Token을 삭제
     * (응답 받은 후, 프론트엔드에서 브라우저/로컬스토리지에 있는 AT, RT 알아서 삭제)
     */
    @Transactional
    public void logout(String userId) {
        String redisKey = "RT:" + userId;

        try {
            // Redis에 키가 존재하면 삭제
            if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                redisTemplate.delete(redisKey);
                log.info("[Auth] User logged out successfully. Refresh token deleted - UserId: {}", userId);
            }
        } catch (Exception e) {
            // 레디스 서버가 죽었거나 통신이 끊긴 경우
            log.error("[Auth] Redis communication failed during logout - UserId: {}", userId, e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * [회원 탈퇴] 사용자 계정 영구 삭제, 토큰 삭제
     */
    @Transactional
    public void withdraw(String userId) {
        //Redis에 저장된 Refresh Token 삭제 (토큰 재사용 방지)
        logout(userId);

        // 소셜 계정 연동 정보 삭제
        socialAccountRepository.deleteByUserId(Long.valueOf(userId));

        // 유저 영구 삭제
        userRepository.deleteById(Long.valueOf(userId));
        log.info("[Auth] User account successfully withdrawn - UserId: {}", userId);
    }

    /**
     *  [개발용 백도어 로그인] 소셜 서비스와의 통신 없이 이메일만으로 강제 가입/로그인 및 토큰 발급
     */
    @Transactional
    public LoginResponse bypassLogin(String email) {
        // 이메일로 사용자 조회 (없으면 테스트용 이름으로 강제 가입 처리)
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .email(email)
                            // 이메일 앞자리를 따서 임시 닉네임 생성 (예: test@kakao.com -> 테스트유저_test)
                            .name("test_user" + email.split("@")[0])
                            .build();
                    User savedUser = userRepository.save(newUser);
                    log.info("[Auth-Bypass] Test user auto-registered - UserId: {}, Email: {}", savedUser.getId(), email);
                    return savedUser;
                });

        // JWT 토큰 발급
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getId().toString(), null, Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))
        );
        String accessToken = jwtTokenProvider.createAccessToken(authentication);
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId().toString());

        // Redis에 Refresh Token 저장
        try {
            String redisKey = "RT:" + user.getId();
            redisTemplate.opsForValue().set(
                    redisKey, refreshToken, jwtProperties.refreshTokenValidity(), TimeUnit.MILLISECONDS
            );
            log.info("[Auth-Bypass] Test user login executed - UserId: {}", user.getId());
        } catch (Exception e) {
            log.error("[Auth-Bypass] Redis communication failed during backdoor login", e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .nickname(user.getName())
                .email(user.getEmail())
                .build();
    }


    /**
     * [개발용 백도어 로그아웃]
     * 이메일만으로 Redis에서 강제 로그아웃
     */
    @Transactional
    public void bypassLogout(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            logout(user.getId().toString());
            log.info("[Auth-Bypass] Logout completed - Email: {}", email);
        });
    }

    /**
     * [소셜 연동 해제 웹훅]
     * 외부에서 카카오/네이버 연결을 끊었을 때 처리
     */
    @Transactional
    public void unlinkSocialAccount(String providerName, String providerId) {
        log.info("[Webhook] Starting {} account unlink webhook processing. Provider ID: {}", providerName, providerId);

        try {
            SocialAccount.Provider provider = SocialAccount.Provider.valueOf(providerName.toUpperCase());

            socialAccountRepository.findByProviderAndProviderId(provider, providerId)
                    .ifPresentOrElse(
                            socialAccount -> {
                            User user = socialAccount.getUser();
                        // 회원 탈퇴(withdraw) 로직 활용 탈퇴 처리
                            withdraw(user.getId().toString());
                            log.info("[Webhook] Successfully deleted {} user data (Provider ID: {})", providerName, providerId);
                        },
                        () -> log.warn("[Webhook] Account to unlink not found. Provider: {}, Provider ID: {}", providerName, providerId)
                );
        } catch (Exception e) {
            log.error("[Social Unlink] 웹훅 처리 중 오류 발생: {}", e.getMessage());
            // 웹훅 응답은 무조건 200
        }
    }
}
