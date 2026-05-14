package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.game.Dashboard;
import io.github.seoleeder.owls_pick.entity.game.Dashboard.CurationType;
import io.github.seoleeder.owls_pick.repository.custom.DashboardRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface DashboardRepository extends JpaRepository<Dashboard, Long>, DashboardRepositoryCustom {

    //같은 큐레이션 타입에 중복된 일자 데이터가 존재하는지 확인
    boolean existsByCurationTypeAndReferenceAt(CurationType curationType, LocalDateTime referenceAt);

    // 특정 큐레이션 타입과 특정 수집 시각의 데이터 삭제
    void deleteByCurationTypeAndReferenceAt(CurationType curationType, LocalDateTime referenceAt);

}
