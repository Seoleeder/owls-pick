package io.github.seoleeder.owls_pick.service.genai.chat;

import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owls 챗봇 트래픽 제어 인프라 통합 테스트
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatTrafficIntegrationTest extends AbstractContainerBaseTest {

    @Autowired
    private ChatTrafficService chatTrafficService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private GenaiProperties genaiProperties;

    private static final Long TEST_USER_ID = 999L;
    private String lockKeyPrefix;
    private String rateKeyPrefix;
    private int maxRequestsPerMinute;

    @BeforeEach
    void setUp() {
        // 프로퍼티 설정값 로드 및 검증 변수 할당
        GenaiProperties.Chat.Traffic trafficConfig = genaiProperties.chat().traffic();
        lockKeyPrefix = trafficConfig.prefix().lock();
        rateKeyPrefix = trafficConfig.prefix().rateLimit();
        maxRequestsPerMinute = trafficConfig.limit().maxRequestsPerMinute();

        // Owls 챗봇 관련 Redis 데이터 전면 초기화
        redisTemplate.delete(redisTemplate.keys("chat:*"));
    }

    @AfterEach
    void tearDown() {
        // 테스트 종료 후 잔여 데이터 소거
        redisTemplate.delete(redisTemplate.keys("chat:*"));
    }

    @Test
    @DisplayName("분산 락 검증 : 동일 유저의 동시 다발적 중복 요청 시 1건만 성공하고 나머지는 차단됨")
    void concurrentAccessDistributedLockTest() throws InterruptedException {
        // [Given] 멀티스레드 동시 인입 환경 설정 (10개 스레드 할당)
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 비동기 스레드 동기화를 위한 CountDownLatch 선언
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 스레드 안전성 확보를 위한 원자성 정수 변수(성공/실패 카운터) 선언
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // [When] 동일 유저 ID 기반 트래픽 검증 및 분산 락 획득 로직 동시 호출
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    chatTrafficService.checkTrafficAndAcquireLock(TEST_USER_ID);
                    successCount.incrementAndGet(); // 최초 락 점유 성공 시 카운트 증가
                } catch (CustomException e) {
                    // 중복 요청 차단 예외(CHATBOT_PROCESSING) 수신 시 실패 카운트 증가
                    if (e.getErrorCode() == ErrorCode.CHATBOT_PROCESSING) {
                        failCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();  // 작업 완료 후 래치 카운트 차감
                }
            });
        }
        latch.await(); // 전체 비동기 스레드 작업 종료 시까지 메인 스레드 대기
        executorService.shutdown(); // 스레드 자원 반환

        // [Then] 락 점유 성공 1건 및 중복 요청 차단 9건 수치 확인
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        // [Then] Redis 내 분산 락 활성화 Key 적재 유무 확인
        String expectedLockKey = lockKeyPrefix + TEST_USER_ID;
        assertThat(redisTemplate.hasKey(expectedLockKey)).isTrue();
    }

    @Test
    @DisplayName("트래픽 제한 검증 : 1분당 최대 허용 호출 횟수 초과 시 TOO_MANY_REQUESTS 예외 발생")
    void rateLimitExceedTest() {
        // [Given] 분산 락 중복 제어 간섭 제거를 위해 요청 처리 직후 즉시 락 해제 수행 (임계치 누적)
        for (int i = 0; i < maxRequestsPerMinute; i++) {
            chatTrafficService.checkTrafficAndAcquireLock(TEST_USER_ID);
            chatTrafficService.releaseLock(TEST_USER_ID); // 다음 호출을 위해 분산 락 반환
        }

        // [When & Then] 임계치를 초과하는 최종 +1회차 요청 시 트래픽 제한 예외 발생 확인
        assertThatThrownBy(() -> chatTrafficService.checkTrafficAndAcquireLock(TEST_USER_ID))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        // [Then - Redis] Redis Rate Limit Key 생성 상태 및 최종 누적 카운트 수치(max + 1) 일치 여부 확인
        String expectedRateKey = rateKeyPrefix + TEST_USER_ID;
        Object currentCount = redisTemplate.opsForValue().get(expectedRateKey);

        assertThat(redisTemplate.hasKey(expectedRateKey)).isTrue();
        assertThat(currentCount).isNotNull();
        assertThat(Long.valueOf(currentCount.toString())).isEqualTo((long) maxRequestsPerMinute + 1);

        // [Then - Redis] 차단 로직 수행 직후 내부 자동 락 해제 메커니즘 정상 작동 유무 확인
        String expectedLockKey = lockKeyPrefix + TEST_USER_ID;
        assertThat(redisTemplate.hasKey(expectedLockKey)).isFalse();
    }
}
