package io.github.seoleeder.owls_pick.service.notification;

import com.google.firebase.messaging.*;
import io.github.seoleeder.owls_pick.dto.response.DiscountNotificationResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.notification.FcmToken;
import io.github.seoleeder.owls_pick.entity.notification.NotificationHistory;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.FcmTokenRepository;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.NotificationHistoryRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final FcmTokenRepository fcmTokenRepository;
    private final NotificationHistoryRepository notificationHistoryRepository;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;

    /**
     * FCM 토큰 등록 및 유저 매핑
     * 토큰 등록: 존재하면 갱신, 없으면 신규 저장
     */
    @Transactional
    public void registerToken(Long userId, String token) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_USER));

        if (!user.isDiscountNotificationEnabled()) {
            log.warn("[Notification] User {} has disabled notifications. Token registration rejected.", userId);
            throw new CustomException(ErrorCode.UNCONSENTED_NOTIFICATION);
        }

        fcmTokenRepository.findByToken(token)
                .ifPresentOrElse(
                        existingToken -> {
                            // 토큰은 있는데, 다른 사용자인 경우 (기기 소유권 이전)
                            if (!existingToken.getUser().getId().equals(userId)) {
                                log.info("[Notification] Token owner changed from User {} to User {}", existingToken.getUser().getId(), userId);
                                existingToken.updateOwner(user); // 연관 유저 변경 및 updatedAt 갱신
                            }
                            // 토큰도 있고, 같은 사용자인 경우 (단순 재등록/갱신)
                            else {
                                log.debug("[Notification] Token already registered for User {}. Refreshing timestamp.", userId);
                                existingToken.refresh(); // updatedAt만 임의로 갱신
                            }
                        },
                        () -> {
                            fcmTokenRepository.save(FcmToken.builder()
                                    .user(user)
                                    .token(token)
                                    .build());
                            log.info("[Notification] Registered new token for User: {}", userId);
                        }
                );
    }

    /**
     * 특정 사용자의 알림 목록 조회
     * 각 알림 데이터를 DiscountNotificationResponse로 변환
     */
    @Transactional(readOnly = true)
    public List<DiscountNotificationResponse> getNotificationList(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new CustomException(ErrorCode.NOT_FOUND_USER);
        }

        return notificationHistoryRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(n -> DiscountNotificationResponse.builder()
                        .id(n.getId())
                        .title(n.getTitle())
                        .gameTitle(n.getGameTitle())
                        .discountRate(n.getDiscountRate())
                        .isRead(n.isRead())
                        .expiryDate(n.getExpiryDate())
                        .createdAt(n.getCreatedAt().toString())
                        .build())
                .toList();
    }

    /**
     * 알림 발송 및 이력 저장 (내부 로직)
     */
    @Transactional
    public void sendDiscountPush(Long userId, Long gameId, int discountRate, LocalDateTime expiryDate) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_USER));

        // 알림 수신 비동의 상태이면, 그대로 리턴
        if (!user.isDiscountNotificationEnabled()) {
            log.debug("[Notification] User {} opted out of discount alerts. Push and history logging aborted.", userId);
            return;
        }

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_GAME));

        // 이력 저장
        notificationHistoryRepository.save(NotificationHistory.builder()
                .user(user)
                .game(game)
                .title("할인 알림")
                .gameTitle(game.getTitle())
                .discountRate(discountRate)
                .expiryDate(expiryDate)
                .build());

        // 푸시 전송 로직
        List<FcmToken> tokens = fcmTokenRepository.findAllByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("[Notification] No tokens for User: {}. Push aborted.", userId);
            return;
        }

        List<String> tokenList = tokens.stream().map(FcmToken::getToken).toList();
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokenList)
                .setNotification(Notification.builder()
                        .setTitle("Owl's Pick 할인 알림")
                        .setBody(game.getTitle() + "이(가) " + discountRate + "% 할인 중입니다!")
                        .build())
                .build();

        try {
            BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(message);
            cleanupInvalidTokens(response, tokenList);
        } catch (FirebaseMessagingException e) {
            log.error("[Notification] FCM send error: {}", e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 만료/무효 토큰 정리
     */
    @Transactional
    public void cleanupInvalidTokens(BatchResponse response, List<String> tokens) {
        if (response.getFailureCount() > 0) {
            List<String> invalidOnes = new ArrayList<>();
            List<SendResponse> responses = response.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                if (!responses.get(i).isSuccessful()) {
                    MessagingErrorCode code = responses.get(i).getException().getMessagingErrorCode();
                    if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT||
                            code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                        invalidOnes.add(tokens.get(i));
                    }
                }
            }
            if (!invalidOnes.isEmpty()) {
                fcmTokenRepository.deleteByTokenIn(invalidOnes);
                log.info("[Notification] Purged {} invalid tokens", invalidOnes.size());
            }
        }
    }

    /**
     * 사용자가 개별 알림을 수동으로 삭제
     */
    @Transactional
    public void deleteNotification(Long userId, Long notificationId) {
        NotificationHistory history = notificationHistoryRepository.findById(notificationId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_NOTIFICATION)); // 에러 코드 추가 필요

        // 본인의 알림인지 권한 검증
        if (!history.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED); // 또는 권한 없음 에러코드
        }

        notificationHistoryRepository.delete(history);
        log.info("[Notification] User {} manually deleted notification {}", userId, notificationId);
    }

    /**
     * 가입된 모든 사용자의 만료된 알림 일괄 삭제
     */
    @Transactional
    public void cleanupExpiredNotifications() {
        // 기준일 계산 (3일)
        LocalDateTime threshold = LocalDateTime.now().minusDays(3);

        // QueryDSL 삭제 로직 호출
        long deletedCount = notificationHistoryRepository.deleteExpiredNotifications(threshold);
        log.info("[Notification] Purged {} expired notifications. Threshold: {}", deletedCount, threshold);
    }
}
