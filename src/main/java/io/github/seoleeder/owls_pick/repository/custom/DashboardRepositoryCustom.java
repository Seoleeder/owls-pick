package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.entity.game.Dashboard;
import io.github.seoleeder.owls_pick.entity.game.Dashboard.CurationType;

import java.time.LocalDateTime;
import java.util.List;

public interface DashboardRepositoryCustom {

    // 특정 큐레이션 타입의 가장 최신의 대시보드 데이터 조회
    List<Dashboard> findLatestTop100(CurationType type);

    // 특정 큐레이션 타입의 가장 최근 갱신 시각 조회
    LocalDateTime findLatestReferenceAt(CurationType type);

    //특정 큐레이션 타입 + 특정 기준 시각의 차트 조회
    List<Dashboard> findGamesByCurationAndDate(CurationType type, LocalDateTime targetDate, int limit);

    // 특정 큐레이션 타입의 특정 시각 기준으로 이전 or 다음 시각 조회
    LocalDateTime findAdjacentDate(CurationType type, LocalDateTime currentDate, boolean isPrevious);

    // 특정 시각과 가장 인접한 기준 시각 조회
    LocalDateTime findClosestReferenceAt(CurationType type, LocalDateTime targetDate);
}
