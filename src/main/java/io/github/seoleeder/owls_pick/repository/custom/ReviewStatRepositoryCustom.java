package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.entity.game.ReviewStat;

import java.time.LocalDateTime;
import java.util.List;

public interface ReviewStatRepositoryCustom {
    //특정 게임의 주간 리뷰 수 업데이트
    void updateWeeklyReviewCount(Long gameId, LocalDateTime startTime);

    /**
     * AI 리뷰 요약 대상이 되는 리뷰 통계 리스트 조회
     * @param minThreshold 요구되는 최소 리뷰 수 (예: 50)
     * @param batchSize 한 번에 처리할 최대 데이터 개수
     */
    List<ReviewStat> findTargetsWithoutSummary(int minThreshold, int batchSize);
}
