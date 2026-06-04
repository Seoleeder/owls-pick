package io.github.seoleeder.owls_pick.service.notification.listener;

import io.github.seoleeder.owls_pick.repository.WishlistRepository;
import io.github.seoleeder.owls_pick.service.notification.NotificationService;
import io.github.seoleeder.owls_pick.service.client.itad.event.GameDiscountEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final WishlistRepository wishlistRepository;
    private final NotificationService notificationService;

    /**
     * 메인 트랜잭션 커밋 완료 후 비동기로 푸시 알림 발송
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleGameDiscountEvent(GameDiscountEvent event) {

        // 발송 대상 유저 식별자 추출
        List<Long> targetUserIds = wishlistRepository.findTargetUserIdsForDiscountPush(event.gameId());

        if (targetUserIds.isEmpty()) {
            log.debug("[Notification] No target users found for discount alert - GameId: {}", event.gameId());
            return;
        }

        log.info("[Notification] Starting push delivery for {} users - GameId: {}", targetUserIds.size(), event.gameId());

        // 대상 유저 순회하며 개별 푸시 발송 요청
        for (Long userId : targetUserIds) {
            try {
                notificationService.sendDiscountPush(
                        userId,
                        event.gameId(),
                        event.discountRate(),
                        event.expiryDate()
                );
            } catch (Exception e) {
                // 단일 발송 실패 시 예외 격리 (전체 루프 유지)
                log.error("[Notification] Failed to send push - UserId: {}, GameId: {} | Error: {}", userId, event.gameId(), e.getMessage());
            }
        }
    }
}