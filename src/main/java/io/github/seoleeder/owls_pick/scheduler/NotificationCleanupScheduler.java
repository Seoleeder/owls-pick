package io.github.seoleeder.owls_pick.scheduler;

import io.github.seoleeder.owls_pick.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupScheduler {

    private final NotificationService notificationService;

    /**
     * 매일 새벽 4시에 실행되어,
     * 할인 만료일로부터 3일이 지난 알림을 DB에서 일괄 삭제합니다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupOldNotifications() {
        log.info("Starting scheduled task: Cleanup expired notifications.");

        // 복잡한 로직은 서비스에게 위임
        notificationService.cleanupExpiredNotifications();

        log.info("Finished scheduled task: Cleanup expired notifications.");
    }
}