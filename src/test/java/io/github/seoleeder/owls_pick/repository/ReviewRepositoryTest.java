package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.config.TestQueryDSLConfig;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.Review;
import io.github.seoleeder.owls_pick.repository.support.EmbeddingExpressions;
import io.github.seoleeder.owls_pick.repository.support.GameExpressions;
import io.github.seoleeder.owls_pick.repository.support.LocalizationExpressions;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TestQueryDSLConfig.class,
        GameExpressions.class,
        LocalizationExpressions.class,
        EmbeddingExpressions.class
})
public class ReviewRepositoryTest extends AbstractContainerBaseTest {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate; // 벌크 인서트 결과 검증용

    @Test
    @DisplayName("NamedParameterJdbcTemplate 기반 리뷰 데이터 Bulk Insert 검증")
    void bulkInsertReviews_Success() {
        // [Given] 삽입 대상 타겟 게임 엔티티 저장
        Game targetGame = gameRepository.save(Game.builder().title("Bulk Test Game").build());

        // [Given] 다량의 가상 리뷰 데이터 리스트 생성 (비영속 상태)
        List<Review> mockReviews = IntStream.range(0, 100).mapToObj(i ->
                Review.builder()
                        .game(targetGame)
                        .recommendationId((long) (1000 + i))
                        .reviewText("Review " + i)
                        .weightedVoteScore(BigDecimal.valueOf(0.85))
                        .playtimeAtReview(120)
                        .votedUp(true)
                        .votesUp(10)
                        .writtenAt(LocalDateTime.now().minusDays(1))
                        .build()
        ).toList();

        // [When] Raw SQL 기반 Bulk Insert 실행
        reviewRepository.bulkInsertReviews(mockReviews);

        // [Then] JdbcTemplate을 활용한 실제 DB 적재 카운트 정합성 검증
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review WHERE game_id = ?",
                Long.class,
                targetGame.getId()
        );

        assertThat(count).isEqualTo(100L);
    }
}
