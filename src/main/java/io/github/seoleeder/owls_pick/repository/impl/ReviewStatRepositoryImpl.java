package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.genai.QGenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.repository.custom.ReviewStatRepositoryCustom;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

import static io.github.seoleeder.owls_pick.entity.game.QReview.review;
import static io.github.seoleeder.owls_pick.entity.game.QReviewStat.reviewStat;

@RequiredArgsConstructor
public class ReviewStatRepositoryImpl implements ReviewStatRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /**
     * 특정 게임의 ID를 받아 주간 리뷰 수 업데이트
     * */
    @Override
    public void updateWeeklyReviewCount(Long gameId, LocalDateTime startTime) {

        // 해당 게임의 최근 N일간 작성된 리뷰 개수 집계
        Long count = queryFactory
                .select(review.count())
                .from(review)
                .where(
                        review.game.id.eq(gameId),
                        review.writtenAt.goe(startTime) // startTime 이후 작성된 리뷰
                )
                .fetchOne();

        int weeklyCount = count != null ? count.intValue() : 0;

        // ReviewStat의 weeklyReview 업데이트
        queryFactory.update(reviewStat)
                .set(reviewStat.weeklyReview, weeklyCount)
                .where(reviewStat.game.id.eq(gameId))
                .execute();
    }

    /**
     * 리뷰 수가 임계치 이상이면서 아직 요약되지 않은 데이터 조회 (미조치 실패 작업 제외)
     */
    @Override
    public List<ReviewStat> findTargetsWithoutSummary(int minThreshold, int batchSize) {

        QGenaiFailedTask failedTask = QGenaiFailedTask.genaiFailedTask;

        return queryFactory.selectFrom(reviewStat)
                .where(
                        // 총 리뷰 수가 임계치 이상
                        reviewStat.totalReview.goe(minThreshold),

                        // 리뷰 요약 텍스트가 없는 데이터 필터링
                        reviewStat.reviewSummary.isNull()
                                .or(reviewStat.reviewSummary.isEmpty()),

                        // 미조치 실패 작업 목록에 존재하지 않는 데이터 필터링 (NOT EXISTS)
                        JPAExpressions
                                .selectOne()
                                .from(failedTask)
                                .where(
                                        failedTask.targetId.eq(reviewStat.game.id),
                                        failedTask.pipelineType.eq(GenaiPipelineType.STEAM_REVIEW_SUMMARY),
                                        failedTask.isHandled.eq(false)
                                )
                                .notExists()
                )
                // 리뷰가 많은 게임부터 요약을 수행하도록 필터링
                .orderBy(reviewStat.totalReview.desc())
                .limit(batchSize)
                .fetch();
    }

}
