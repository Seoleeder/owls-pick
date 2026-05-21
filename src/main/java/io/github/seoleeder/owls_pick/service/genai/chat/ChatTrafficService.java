package io.github.seoleeder.owls_pick.service.genai.chat;

import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTrafficService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final GenaiProperties genaiProperties;

    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration RATE_LIMIT_TTL = Duration.ofMinutes(1);

    /**
     * FastAPI 통신 전 트래픽 제어 수행
     * 중복 클릭 및 과도한 API 호출 사전 차단 목적.
     */
    public void checkTrafficAndAcquireLock(Long userId) {

        // 제어 키 생성을 위한 Prefix 설정값 로드
        GenaiProperties.Chat.Traffic trafficConfig = genaiProperties.chat().traffic();
        String lockKey = trafficConfig.prefix().lock() + userId;
        String rateKey = trafficConfig.prefix().rateLimit() + userId;

        // 분산 락 검증 (중복 요청 방어)
        Boolean isLocked = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", LOCK_TTL);

        // 락 획득 실패 시, 처리 중인 요청으로 간주하여 즉각 차단.
        if (Boolean.FALSE.equals(isLocked)) {
            log.warn("[ChatbotTraffic] Lock acquisition failed. userId: {}", userId);
            throw new CustomException(ErrorCode.CHATBOT_PROCESSING);
        }

        // 트래픽 누적 카운트 갱신. 락 획득에 성공한 현재 요청을 포함하여 총 호출 횟수 집계.
        Long requestCount = redisTemplate.opsForValue().increment(rateKey);

        // 최초 카운트 발생 시점에 만료 시간(1분) 할당
        if (requestCount != null && requestCount == 1L) {
            redisTemplate.expire(rateKey, RATE_LIMIT_TTL);
        }

        // 설정된 임계치 초과 여부 검증
        if (requestCount != null && requestCount > trafficConfig.limit().maxRequestsPerMinute()) {
            log.warn("[ChatbotTraffic] Rate limit exceeded. userId: {}, count: {}", userId, requestCount);

            // 로직 차단에 따른 예외 발생 전, 점유 중인 락 해제
            releaseLock(userId);
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /**
     * 로직 종료(정상/예외) 시점에 호출되어 할당된 분산 락을 반환.
     * 다음 요청이 정상적으로 유입될 수 있도록 상태 초기화.
     */
    public void releaseLock(Long userId) {
        String lockKey = genaiProperties.chat().traffic().prefix().lock() + userId;
        redisTemplate.delete(lockKey);
    }
}
