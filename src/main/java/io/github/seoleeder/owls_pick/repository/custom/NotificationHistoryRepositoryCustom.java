package io.github.seoleeder.owls_pick.repository.custom;

import java.time.LocalDateTime;

public interface NotificationHistoryRepositoryCustom {
    // 만료된 알림 일괄 삭제
    long deleteExpiredNotifications(LocalDateTime threshold);
}
