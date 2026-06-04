package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * MainPick 서비스 Redis 캐싱 최적화 통합 테스트
 */
@Tag("integration")
@SpringBootTest
@EnableCaching
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MainPickCacheIntegrationTest extends AbstractContainerBaseTest {

    @Autowired
    private MainPickService mainPickService;

    @MockitoBean
    private UserRepository userRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // JPA 표준 팩토리 객체 (Hibernate의 물리 세션 팩토리 추출)
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // Hibernate 자체 성능 통계 집계 객체 (DB로 전송된 SQL 쿼리 발생 횟수 추적)
    private Statistics hibernateStatistics;

    // 공통 사용 페이징 및 더미 식별자 상수
    private static final Pageable DEFAULT_PAGEABLE = PageRequest.of(0, 10);
    private static final Long TEST_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        // Redis 캐시 데이터 초기화
        redisTemplate.delete(redisTemplate.keys("main_pick:*"));

        // 쿼리 횟수 측정을 위한 Hibernate 통계 기능 활성화
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        hibernateStatistics = sessionFactory.getStatistics();
        hibernateStatistics.setStatisticsEnabled(true);
        hibernateStatistics.clear(); // 카운트를 0으로 초기화

        // 내부 랜덤 로직 통제를 위한 단일 선호 태그 유저 정보 주입
        User dummyUser = User.builder()
                .id(TEST_USER_ID)
                .preferredTags(List.of("ADVENTURE"))
                .birthDate(LocalDate.of(1994, 10, 31))
                .build();
        given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(dummyUser));
    }

    @AfterEach
    void tearDown() {
        // 잔여 캐시 데이터 클린업 및 하이버네이트 통계 기능 종료
        redisTemplate.delete(redisTemplate.keys("main_pick:*"));
        hibernateStatistics.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("캐시 히트 검증 : 정적 파라미터 조회 시, DB 쿼리는 1회만 발생하고 Redis에 캐시가 적재됨")
    void hiddenMasterpiecesCacheHitTest() {
        // [Given] 연속 요청 횟수 설정
        int requestCount = 10;
        int pageNumber = DEFAULT_PAGEABLE.getPageNumber();

        // [When] 동일한 페이지 조건으로 숨겨진 명작(Hidden Masterpieces) 반복 조회
        for (int i = 0; i < requestCount; i++) {
            mainPickService.getHiddenMasterpieces(DEFAULT_PAGEABLE);
        }

        // [Then - DB] 첫 번째 호출(Cache Miss) 시점에만 DB 쿼리가 발생했는지 검증
        long queryCount = hibernateStatistics.getPrepareStatementCount();
        assertThat(queryCount).isEqualTo(1L);

        // [Then - Redis] Redis 캐시 키 생성 및 실제 데이터 적재 여부 검증
        String expectedRedisKey = "main_pick:hidden_masterpiece::" + pageNumber;
        Boolean hasKey = redisTemplate.hasKey(expectedRedisKey);

        assertThat(hasKey).isTrue();
        assertThat(redisTemplate.opsForValue().get(expectedRedisKey)).isNotNull();
    }

    @Test
    @DisplayName("캐시 히트 검증 : 동적 태그 파라미터 조회 시, DB 쿼리는 1회만 발생하고 Redis에 동적 키가 적재됨")
    void trendingPicksCacheHitTest() {

        // [Given] 연속 요청 횟수 설정
        int requestCount = 10;
        int pageNumber = DEFAULT_PAGEABLE.getPageNumber();

        // [When] 유저 선호 태그 기반의 트렌딩 픽(Trending Picks) 반복 조회
        for (int i = 0; i < requestCount; i++) {
            mainPickService.getTrendingPicks(TEST_USER_ID, DEFAULT_PAGEABLE);
        }

        // [Then - DB] 동적 키 할당 구조에서도 첫 호출 이후 물리 쿼리가 방어되는지 검증
        long queryCount = hibernateStatistics.getPrepareStatementCount();
        assertThat(queryCount).isEqualTo(1L);

        // [Then - Redis] 추출된 선호 태그명이 포함된 동적 Redis 키가 잘 적재되었는지 검증
        String keyPattern = "main_pick:trending::*:" + pageNumber;
        Set<String> redisKeys = redisTemplate.keys(keyPattern);

        assertThat(redisKeys).isNotEmpty();
    }
}