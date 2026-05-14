package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.notification.NotificationHistory;
import io.github.seoleeder.owls_pick.repository.custom.NotificationHistoryRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, Long>, NotificationHistoryRepositoryCustom {

    // 특정 사용자의 알림 목록을 최신순으로 정렬하여 반환
    List<NotificationHistory> findAllByUserIdOrderByCreatedAtDesc(Long userId);


}
