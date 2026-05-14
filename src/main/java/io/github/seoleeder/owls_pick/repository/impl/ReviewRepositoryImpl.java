package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.Review;
import io.github.seoleeder.owls_pick.repository.custom.ReviewRepositoryCustom;
import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.seoleeder.owls_pick.entity.game.QReview.review;

@Repository
@RequiredArgsConstructor
public class ReviewRepositoryImpl implements ReviewRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    @Override
    public List<String> findReviewTextsByGameId(Long gameId) {

        return queryFactory.select(review.reviewText)
                .from(review)
                .where(review.game.id.eq(gameId))
                .fetch();
    }

    /**
     * N+1 SELECT 방지를 위한 게임별 기존 리뷰 ID 전체 로드
     */
    @Override
    public Set<Long> findRecommendationIdsByGameId(Long gameId) {
        List<Long> existingIds = queryFactory.select(review.recommendationId)
                .from(review)
                .where(review.game.id.eq(gameId))
                .fetch();

        // Set 구조로 반환하여 탐색 속도 극대화
        return new HashSet<>(existingIds);
    }

    /**
     * 리뷰 데이터 일괄 삽입 (Bulk Insert)
     */
    @Override
    public void bulkInsertReviews(List<Review> reviews) {
        if (reviews.isEmpty()) return;

        String sql = "INSERT INTO review (game_id, recommendation_id, review_text, weighted_vote_score, playtime_at_review, voted_up, votes_up, written_at, created_at, updated_at) " +
                "VALUES (:gameId, :recommendationId, :reviewText, :weightedVoteScore, :playtimeAtReview, :votedUp, :votesUp, :writtenAt, :createdAt, :updatedAt)";

        // 일괄 적용할 생성 및 수정 시각
        LocalDateTime now = LocalDateTime.now();

        // 파라미터 바인딩 및 배치 처리용 배열로 변환
        SqlParameterSource[] batch = reviews.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("gameId", r.getGame().getId())
                        .addValue("recommendationId", r.getRecommendationId())
                        .addValue("reviewText", r.getReviewText())
                        .addValue("weightedVoteScore", r.getWeightedVoteScore())
                        .addValue("playtimeAtReview", r.getPlaytimeAtReview())
                        .addValue("votedUp", r.getVotedUp())
                        .addValue("votesUp", r.getVotesUp())
                        .addValue("writtenAt", Timestamp.valueOf(r.getWrittenAt()))
                        .addValue("createdAt", Timestamp.valueOf(now))
                        .addValue("updatedAt", Timestamp.valueOf(now)))
                .toArray(SqlParameterSource[]::new);

        namedJdbcTemplate.batchUpdate(sql, batch);
    }
}
