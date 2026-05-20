package io.github.seoleeder.owls_pick.global.config.redis;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@EnableCaching
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    // Spring Boot 기본 설정이 적용된 ObjectMapper (PageModule 등 포함)
    private final ObjectMapper defaultObjectMapper;

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        //설정 파일의 redis 설정과 연결
        template.setConnectionFactory(connectionFactory);

        // Key: 사람이 읽을 수 있는 문자열로 저장
        template.setKeySerializer(new StringRedisSerializer());

        //Value와 HashValue에 적용
        template.setValueSerializer(customJsonSerializer());
        template.setHashValueSerializer(customJsonSerializer());

        return template;
    }

    /**
     * @Cacheable 지원을 위한 RedisCacheManager 설정.
     * 직렬화 방식 및 도메인별 캐시 생명주기(TTL) 관리
     * */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 캐시 기본 정책 설정 (Key: String, Value: JSON, 기본 TTL: 1시간)
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(customJsonSerializer()))
                .entryTtl(Duration.ofHours(1));

        // 도메인별 커스텀 TTL 매핑
        Map<String, RedisCacheConfiguration> customConfigurations = new HashMap<>();

        // 숨겨진 명작 (Hidden Masterpieces)
        customConfigurations.put("main_pick::hidden_masterpiece", defaultConfig.entryTtl(Duration.ofHours(24)));

        // 트렌딩 픽 (Trending Picks)
        customConfigurations.put("main_pick::trending", defaultConfig.entryTtl(Duration.ofHours(12)));

        // 퀵 플레이 (Quick Plays)
        customConfigurations.put("main_pick::quick_play", defaultConfig.entryTtl(Duration.ofHours(12)));

        // 사용자 맞춤 픽 (Most Personalized Picks)
        customConfigurations.put("main_pick::personal", defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // RedisCacheManager 객체 빌드 및 Bean 등록
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(customConfigurations)
                .build();
    }

    /**
     * JSON 직렬화/역직렬화 공통 설정 도구.
     * 날짜 데이터 포맷 및 다형성 처리를 위한 타입 메타데이터(@class) 활성화 포함
     * */
    private GenericJackson2JsonRedisSerializer customJsonSerializer() {

        // 스프링 기본 ObjectMapper 복제
        ObjectMapper objectMapper = defaultObjectMapper.copy();

        // 클래스패스 내 Jackson 확장 모듈 자동 스캔 및 등록
        objectMapper.findAndRegisterModules();

        // 날짜 데이터 직렬화 포맷 설정 (타임스탬프 기반 배열 대신 ISO-8601 문자열 사용)
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 역직렬화 시 JSON에 존재하지만 객체에 없는 필드 무시 처리
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // 다형성 객체 역직렬화를 위한 타입 검증기 설정 (화이트리스트 기반)
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator
                .builder()
                .allowIfSubType(Object.class)
                .build();

        // 다형성 객체 바인딩을 위한 클래스 타입 메타데이터(@class) 자동 삽입 활성화
        objectMapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }
}

