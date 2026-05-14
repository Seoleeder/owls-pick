package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.enums.status.SyncStatus;
import io.github.seoleeder.owls_pick.repository.custom.PlaytimeRepositoryCustom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static io.github.seoleeder.owls_pick.entity.game.QGame.game;
import static io.github.seoleeder.owls_pick.entity.game.QPlaytime.playtime;

@Repository
@RequiredArgsConstructor
public class PlaytimeRepositoryImpl implements PlaytimeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /**
     * playtime 데이터가 존재하지 않고
     * */
    @Override
    public List<Game> findGamesWithUnsyncedPlaytime(int limit) {
        return queryFactory.selectFrom(game)
                .leftJoin(playtime).on(playtime.game.eq(game))
                .where(
                        // Playtime 미존재
                        playtime.isNull()
                                // 상태가 UNSYNCED 또는 FAILED
                                .or(playtime.syncStatus.in(SyncStatus.UNSYNCED, SyncStatus.FAILED))
                )
                .limit(limit)
                .fetch();
    }
}
