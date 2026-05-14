package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.entity.game.Review;

import java.util.List;
import java.util.Set;

public interface ReviewRepositoryCustom {

    // 특정 게임에 수집된 스팀 리뷰 텍스트 목록 조회
    List<String> findReviewTextsByGameId(Long gameId);

    // 특정 게임의 기존 리뷰 ID 목록 전체 조회
    Set<Long> findRecommendationIdsByGameId(Long gameId);

    // JDBC 기반 대량 리뷰 일괄 삽입
    void bulkInsertReviews(List<Review> reviews);
}
