package io.github.seoleeder.owls_pick.support;

import io.github.seoleeder.owls_pick.global.config.FirebaseConfig;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트의 상위 클래스
 * 테스트 시작 전 자동으로 DB, Redis 컨테이너 로드
 * */
@ActiveProfiles("test")
public abstract class AbstractContainerBaseTest {

    @MockitoBean
    private FirebaseConfig firebaseConfig;

    // PostgreSQL 컨테이너 (15-alpine)
    static final PostgreSQLContainer<?> POSTGRES_CONTAINER = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("owlspick_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("init.sql");

    // Redis 컨테이너 (7-alpine)
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        // 컨테이너 실행 (병렬 시작)
        POSTGRES_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    // 컨테이너 접속 정보 주입
    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        // Postgres 연결 정보
        registry.add("spring.datasource.url", () -> POSTGRES_CONTAINER.getJdbcUrl() + "&reWriteBatchedInserts=true");
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);

        // Redis 연결 정보
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
    }
}
