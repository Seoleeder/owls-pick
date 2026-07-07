package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.config.TestQueryDSLConfig;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.repository.support.EmbeddingExpressions;
import io.github.seoleeder.owls_pick.repository.support.GameExpressions;
import io.github.seoleeder.owls_pick.repository.support.LocalizationExpressions;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TestQueryDSLConfig.class,
        GameExpressions.class,
        LocalizationExpressions.class,
        EmbeddingExpressions.class
})
public class VectorEmbeddingRepositoryTest extends AbstractContainerBaseTest {

    @Autowired
    private VectorEmbeddingRepository vectorEmbeddingRepository;

    @Autowired
    private GameRepository gameRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("pgvector 코사인 거리 연산 기반 유사 임베딩 검색 및 정렬 검증")
    void findMostSimilarGames_Success() {
        // [Given] 유사도 비교를 위한 게임 데이터 세팅
        Game targetGame = gameRepository.save(Game.builder().title("Target Action RPG").build());
        Game similarGame = gameRepository.save(Game.builder().title("Similar Action RPG").build());
        Game completelyDifferentGame = gameRepository.save(Game.builder().title("Cozy Puzzle Game").build());

        // [Given] 상태 필터링 검증을 위한 실패 데이터 세팅
        Game failedGame = gameRepository.save(Game.builder().title("Failed Game").build());

        // [Given] 코사인 거리 방향성(각도) 검증을 위한 768차원 벡터 데이터 삽입
        insertVectorEmbedding(targetGame.getId(), generateVectorString(1.0f, 0.0f), "SUCCESS");
        insertVectorEmbedding(similarGame.getId(), generateVectorString(0.9f, 0.1f), "SUCCESS");
        insertVectorEmbedding(completelyDifferentGame.getId(), generateVectorString(0.0f, 1.0f), "SUCCESS");

        // [Given] FAILED 상태의 Null 벡터 데이터 삽입
        jdbcTemplate.update(
                "INSERT INTO vector_embedding (game_id, embedding, source_text, embedding_status) " +
                        "VALUES (?, NULL, ?, ?)",
                failedGame.getId(), "failed_source_text", "FAILED"
        );

        entityManager.clear(); // 1차 캐시 비우기

        // [When] 타겟 벡터와 동일한 배열 매개변수로 유사도 검색 쿼리 실행
        float[] queryVector = generateVectorArray(1.0f, 0.0f);
        List<VectorEmbedding> results = vectorEmbeddingRepository.findTopSimilarGames(queryVector, 3);

        // [Then] 코사인 거리(각도)가 가까운 순서에 따른 오름차순 정렬 결과 확인
        assertThat(results).hasSize(3)
                .extracting(result -> result.getGame().getTitle())
                .containsExactly(
                        "Target Action RPG",
                        "Similar Action RPG",
                        "Cozy Puzzle Game"
                );
    }

    /**
     * DB 삽입용 768차원 벡터 텍스트 배열 생성
     */
    private String generateVectorString(float v1, float v2) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(v1).append(", ").append(v2);
        for (int i = 2; i < 768; i++) {
            sb.append(", 0.0");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * QueryDSL 파라미터 바인딩용 768차원 float[] 배열 생성
     */
    private float[] generateVectorArray(float v1, float v2) {
        float[] vector = new float[768];
        vector[0] = v1;
        vector[1] = v2;
        // 나머지는 0.0f로 자동 초기화됨
        return vector;
    }

    /**
     * VectorEmbedding 데이터 삽입 헬퍼 메서드
     */
    private void insertVectorEmbedding(Long gameId, String vectorString, String status) {
        jdbcTemplate.update(
                "INSERT INTO vector_embedding (game_id, embedding, source_text, embedding_status) " +
                        "VALUES (?, CAST(? AS vector), ?, ?)",
                gameId,
                vectorString,
                "test_source_text",
                status
        );
    }
}
