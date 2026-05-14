package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.repository.custom.WishlistRepositoryCustom;
import io.github.seoleeder.owls_pick.repository.dto.WishlistQueryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

import static io.github.seoleeder.owls_pick.entity.game.QGame.game;
import static io.github.seoleeder.owls_pick.entity.game.QReviewStat.reviewStat;
import static io.github.seoleeder.owls_pick.entity.user.QUser.user;
import static io.github.seoleeder.owls_pick.entity.user.QWishlist.wishlist;

@RequiredArgsConstructor
public class WishlistRepositoryImpl implements WishlistRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<WishlistQueryDto> findWishlistPageByUserId(Long userId, Pageable pageable) {
        // 데이터 조회 쿼리
        List<WishlistQueryDto> content = queryFactory
                .select(Projections.constructor(WishlistQueryDto.class,
                        wishlist.createdAt, // 찜한 시각
                        game,               // 게임 엔티티
                        reviewStat))        // 리뷰 통계 엔티티
                .from(wishlist)
                .join(wishlist.game, game)  // 위시리스트와 게임 join
                .leftJoin(reviewStat).on(game.id.eq(reviewStat.game.id)) // 리뷰 통계는 on으로 join
                .where(wishlist.user.id.eq(userId))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(wishlist.createdAt.desc()) // 최근 찜한 순
                .fetch();

        // 카운트 쿼리
        JPAQuery<Long> countQuery = queryFactory
                .select(wishlist.count())
                .from(wishlist)
                .where(wishlist.user.id.eq(userId));

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    @Override
    public List<Long> findTargetUserIdsForDiscountPush(Long gameId) {
        return queryFactory
                .select(wishlist.id.userId)
                .from(wishlist)
                .join(wishlist.user, user)
                .where(
                        wishlist.id.gameId.eq(gameId),
                        user.isDiscountNotificationEnabled.isTrue()
                )
                .fetch();
    }
}

