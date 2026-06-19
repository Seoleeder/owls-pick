package io.github.seoleeder.owls_pick.global.security.config;

import io.github.seoleeder.owls_pick.global.security.config.properties.CorsProperties;
import io.github.seoleeder.owls_pick.global.security.jwt.JwtAuthenticationEntryPoint;
import io.github.seoleeder.owls_pick.global.security.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF 비활성화 (REST API이므로 불필요)
                .csrf(AbstractHttpConfigurer::disable)

                // Form Login & Basic Auth 비활성화 (JWT 쓸 거니까)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // 세션 설정: Stateless (세션과 JWT 충돌 방지)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CORS 설정 적용
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // api 요청별 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 로그인, 회원가입, swagger, error 페이지 등 통과
                        .requestMatchers(
                                "/",
                                "/admin/**",
                                "/error",
                                // 소셜 관련
                                "/api/auth/login/**",
                                "/api/auth/authorize/**",
                                "/api/auth/reissue",
                                "/api/webhook/**",

                                "/api/dashboard/**",    // 대시보드 차트
                                "/api/explore/**",      // 탐색 태그
                                "/api/games/**",        // 게임 상세
                                "/api/main-picks/**",   // 맞춤 픽

                                "/api/search/**",       // 통합 검색

                                // 개발용 백도어
                                "/api/dev/auth/**",

                                // swagger 관련
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()

                        // Actuator 엔드포인트 전용 접근 허용
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()

                        // 그 외 모든 요청은 토큰 인증 필요
                        .anyRequest().authenticated()
                )

                // 예외 처리 설정 (인증 실패시 커스텀 응답을 내려줌)
                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))

                // 필터 순서 설정
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * [CORS 설정]
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Allowed Origins
        configuration.setAllowedOriginPatterns(corsProperties.allowedOrigins());

        // Allowed Methods (GET, POST 등)
        configuration.setAllowedMethods(corsProperties.allowedMethods());

        // Allowed Headers (요청 시 허용할 헤더)
        configuration.setAllowedHeaders(corsProperties.allowedHeaders());

        // Exposed Headers (클라이언트가 읽을 수 있는 응답 헤더)
        configuration.setExposedHeaders(corsProperties.exposedHeaders());

        // Allow Credentials (쿠키/인증정보 포함 허용 여부)
        configuration.setAllowCredentials(corsProperties.allowCredentials());

        // Max Age (Preflight 요청 캐싱 시간)
        configuration.setMaxAge(corsProperties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
