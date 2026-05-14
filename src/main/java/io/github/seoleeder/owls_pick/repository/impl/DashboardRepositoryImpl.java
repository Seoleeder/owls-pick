package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.JPAExpressions;
import java.time.Duration;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.Dashboard;
import io.github.seoleeder.owls_pick.entity.game.Dashboard.CurationType;
import io.github.seoleeder.owls_pick.entity.game.QDashboard;
import io.github.seoleeder.owls_pick.entity.game.QGame;
import io.github.seoleeder.owls_pick.repository.custom.DashboardRepositoryCustom;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

import static io.github.seoleeder.owls_pick.entity.game.QDashboard.dashboard;
import static io.github.seoleeder.owls_pick.entity.game.QGame.game;

@RequiredArgsConstructor
public class DashboardRepositoryImpl implements DashboardRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Dashboard> findLatestTop100(CurationType type) {

        // 서브쿼리용 별칭 생성
        QDashboard subDashboard = new QDashboard("subDashboard");

        return queryFactory.selectFrom(dashboard)
                .join(dashboard.game, game).fetchJoin()
                .where(
                        dashboard.curationType.eq(type),
                        dashboard.referenceAt.eq(
                                JPAExpressions
                                        .select(subDashboard.referenceAt.max())
                                        .from(subDashboard)
                                        .where(subDashboard.curationType.eq(type))
                        )
                )
                .orderBy(dashboard.rank.asc())
                .distinct()
                .fetch();
    }

    // 특정 큐레이션 타입의 가장 최근(Max) referenceAt 조회
    public LocalDateTime findLatestReferenceAt(CurationType type) {
        QDashboard dashboard = QDashboard.dashboard;
        return queryFactory.select(dashboard.referenceAt.max())
                .from(dashboard)
                .where(dashboard.curationType.eq(type))
                .fetchOne();
    }

    // 특정 큐레이션 타입 + 특정 기준 시간의 랭킹 조회
    @Override
    public List<Dashboard> findGamesByCurationAndDate(CurationType type, LocalDateTime targetDate, int limit) {
        QDashboard dashboard = QDashboard.dashboard;
        QGame game = QGame.game;

        return queryFactory.selectFrom(dashboard)
                .join(dashboard.game, game).fetchJoin() // N+1 방지
                .where(
                        dashboard.curationType.eq(type),
                        dashboard.referenceAt.eq(targetDate) // 기준 시간 필터링 추가
                )
                .orderBy(dashboard.rank.asc())
                .limit(limit)
                .distinct()
                .fetch();
    }

    // 특정 큐레이션 타입의 특정 시각 기준으로 이전/다음 기준 시각 조회
    public LocalDateTime findAdjacentDate(CurationType type, LocalDateTime currentDate, boolean isPrevious) {
        QDashboard dashboard = QDashboard.dashboard;

        var query = queryFactory.select(dashboard.referenceAt)
                .from(dashboard)
                .where(dashboard.curationType.eq(type));

        if (isPrevious) {
            query.where(dashboard.referenceAt.lt(currentDate)).orderBy(dashboard.referenceAt.desc());
        } else {
            query.where(dashboard.referenceAt.gt(currentDate)).orderBy(dashboard.referenceAt.asc());
        }

        return query.limit(1).fetchOne();
    }

    /**
     * 특정 시각과 가장 인접한 DB의 수집 기준 시각 조회
     * (과거와 미래의 인접 시각을 각각 조회해서 오차가 가장 적은 기준 시각 조회)
     * */
    @Override
    public LocalDateTime findClosestReferenceAt(CurationType type, LocalDateTime targetDate) {
        QDashboard dashboard = QDashboard.dashboard;

        // 타겟 시간보다 작거나 같은 가장 가까운 과거 날짜 조회
        LocalDateTime pastDate = queryFactory.select(dashboard.referenceAt.max())
                .from(dashboard)
                .where(
                        dashboard.curationType.eq(type),
                        dashboard.referenceAt.loe(targetDate)
                )
                .fetchOne();

        // 타겟 시간보다 큰 가장 가까운 미래 날짜 조회
        LocalDateTime futureDate = queryFactory.select(dashboard.referenceAt.min())
                .from(dashboard)
                .where(
                        dashboard.curationType.eq(type),
                        dashboard.referenceAt.gt(targetDate)
                )
                .fetchOne();

        // 둘 다 없으면 null
        if (pastDate == null && futureDate == null) {
            return null;
        }
        // 한쪽만 있으면 있는 쪽 반환
        if (pastDate == null) return futureDate;
        if (futureDate == null) return pastDate;

        // 둘 다 있다면, targetDate와의 절대적인 시간 격차를 계산하여 더 가까운 쪽 반환
        long pastDiff = Duration.between(pastDate, targetDate).abs().toMillis();
        long futureDiff = Duration.between(targetDate, futureDate).abs().toMillis();

        // 인접 기준 시각 반환 (오차가 같다면 과거 날짜 우선)
        return (pastDiff <= futureDiff) ? pastDate : futureDate;
    }
}
