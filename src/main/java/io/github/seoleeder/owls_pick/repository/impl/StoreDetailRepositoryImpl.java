package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.custom.StoreDetailRepositoryCustom;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.seoleeder.owls_pick.entity.game.QGame.game;
import static io.github.seoleeder.owls_pick.entity.game.QReviewStat.reviewStat;
import static io.github.seoleeder.owls_pick.entity.game.QStoreDetail.storeDetail;

@RequiredArgsConstructor
public class StoreDetailRepositoryImpl implements StoreDetailRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    //특정 스토어의 모든 App Id 조회
    @Override
    public Set<String> findAllAppIdsByStore(StoreDetail.StoreName storeName) {

        List<String> result = queryFactory
                .select(storeDetail.storeAppId)
                .from(storeDetail)
                .where(storeDetail.storeName.eq(storeName))
                .fetch();

        return new HashSet<>(result);
    }


    //ReviewStat이 없는 게임 조회
    @Override
    public List<StoreDetail> findGamesWithNoReviews(StoreDetail.StoreName storeName, int limit) {
        return queryFactory
                .selectFrom(storeDetail)
                .leftJoin(reviewStat).on(reviewStat.id.eq(storeDetail.game.id))
                .join(storeDetail.game).fetchJoin() // 저장 시에 Game 엔티티가 필요함
                .where(
                        storeDetail.storeName.eq(storeName),
                        storeDetail.storeAppId.isNotNull(),
                        reviewStat.id.isNull() // 리뷰 통계 데이터가 존재하지 않음 -> 리뷰 데이터 수집 안된 게임
                )
                .limit(limit) // 메모리 부하 방지
                .fetch();
    }

    //ReviewStat의 갱신 시각이 오래된 순으로 조회 (리뷰 수집이 안된 게임도 포함)
    @Override
    public List<StoreDetail> findGamesNeedingReviewUpdate(StoreDetail.StoreName storeName, int limit) {
        return queryFactory
                .selectFrom(storeDetail)
                .leftJoin(reviewStat).on(reviewStat.id.eq(storeDetail.game.id))
                .join(storeDetail.game).fetchJoin()
                .where(
                        storeDetail.storeName.eq(storeName),
                        storeDetail.storeAppId.isNotNull()
                ).orderBy(reviewStat.updatedAt.asc().nullsFirst()) // 갱신일이 오래된 순
                .limit(limit)
                .fetch();

    }

    @Override
    public List<StoreDetail> findByStoreNameAndStoreAppIdIn(StoreDetail.StoreName storeName, List<String> appIds) {
        return queryFactory
                .selectFrom(storeDetail)
                .join(storeDetail.game).fetchJoin()
                .where(
                        storeDetail.storeName.eq(storeName),
                        storeDetail.storeAppId.in(appIds)
                )
                .fetch();
    }

    @Override
    public List<StoreDetail> findValidGamesMissingItadId(StoreDetail.StoreName storeName, Long lastId, int limit) {
        return queryFactory
                .selectFrom(storeDetail)
                .join(storeDetail.game, game).fetchJoin() // N+1 방지
                .where(
                        storeDetail.storeName.eq(storeName),
                        storeDetail.storeAppId.isNotNull(),
                        game.itadId.isNull(),
                        game.firstRelease.isNotNull(), // IGDB 출시일 정보가 있는 유효한 게임만 필터링
                        lastId > 0 ? storeDetail.id.gt(lastId) : null // 커서 조건 (0이면 처음부터 조회)
                )
                .orderBy(storeDetail.id.asc()) // ASC 정렬
                .limit(limit)
                .fetch();
    }

    /**
     * 배치 내 대상 게임들과 스토어 목록에 해당하는 StoreDetail 일괄 조회
     */
    @Override
    public List<StoreDetail> findAllByGamesAndStoreNames(List<Game> games, List<StoreDetail.StoreName> storeNames) {
        if (games == null || games.isEmpty()) {
            return List.of();
        }

        return queryFactory
                .selectFrom(storeDetail)
                .join(storeDetail.game, game).fetchJoin()
                .where(
                        storeDetail.game.in(games),
                        storeDetail.storeName.in(storeNames)
                )
                .fetch();
    }
}
