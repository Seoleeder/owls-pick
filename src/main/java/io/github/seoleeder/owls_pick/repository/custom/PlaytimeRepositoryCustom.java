package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.entity.game.Game;

import java.util.List;

public interface PlaytimeRepositoryCustom {

    // HLTB 플레이 타임 스크래핑 대상 게임 목록 조회
    // (미수집 또는 수집 실패 상태의 게임 한정)
    public List<Game> findGamesWithUnsyncedPlaytime(int limit);

}
