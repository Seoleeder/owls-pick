package io.github.seoleeder.owls_pick.global.config.restclient;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;


@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final RestClientLoggingInterceptor loggingInterceptor;

    @Bean
    public RestClient restClient(RestClient.Builder builder) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)) // 연결 시도 3초 제한
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(10));   // 응답 대기 10초 제한

        return builder
                .requestFactory(factory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }

    /**
     * OpenAI 엔진(한글화, 리뷰 요약, 벡터 임베딩) 전용 RestClient 빈
     */
    @Bean("genaiRestClient")
    public RestClient genaiRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);

        // 네트워크 지연 및 AI 생성 시간 변동폭을 고려하여 응답 대기 30초 제한
        factory.setReadTimeout(Duration.ofSeconds(30));

        return builder
                .requestFactory(factory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }

    /**
     * 외부 게임 데이터 수집(Steam Review, ITAD) 전용 RestClient 빈
     */
    @Bean("externalApiRestClient")
    public RestClient externalApiRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)) // 연결 시도 3초 제한
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5)); // 응답 대기 5초 제한

        return builder
                .requestFactory(factory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }

    /**
     * HLTB 플레이 타임 수집 전용 RestClient 빈
     */
    @Bean("hltbRestClient")
    public RestClient hltbRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)) // 연결 시도 5초 제한
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);

        // 네트워크 지연 및 파싱 시간을 고려하여 응답 대기 60초 제한
        factory.setReadTimeout(Duration.ofSeconds(60));

        return builder
                .requestFactory(factory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }

    /**
     * owls 챗봇 전용 RestClient 빈
     */
    @Bean("chatRestClient")
    public RestClient chatRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // 챗봇 환경 특성을 고려하여 타임아웃 30초 제한
        factory.setReadTimeout(Duration.ofSeconds(30));

        return builder
                .requestFactory(factory)
                .requestInterceptor(loggingInterceptor)
                .build();
    }
}
