package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.repository.custom.NotificationHistoryRepositoryCustom;
import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;

import static io.github.seoleeder.owls_pick.entity.notification.QNotificationHistory.notificationHistory;


@RequiredArgsConstructor
public class NotificationHistoryRepositoryImpl implements NotificationHistoryRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    /**
     * 특정 시각 기준으로 만료된 알림 일괄 삭제
     * */
    @Override
    public long deleteExpiredNotifications(LocalDateTime threshold) {
        return queryFactory
                .delete(notificationHistory)
                .where(notificationHistory.expiryDate.lt(threshold))
                .execute();
    }
}
