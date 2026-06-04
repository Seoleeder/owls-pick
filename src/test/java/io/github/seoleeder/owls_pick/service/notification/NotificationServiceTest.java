package io.github.seoleeder.owls_pick.service.notification;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import io.github.seoleeder.owls_pick.entity.notification.FcmToken;
import io.github.seoleeder.owls_pick.entity.notification.NotificationHistory;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.FcmTokenRepository;
import io.github.seoleeder.owls_pick.repository.NotificationHistoryRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {

    @InjectMocks
    private NotificationService notificationService;

    @Mock
    private FcmTokenRepository fcmTokenRepository;

    @Mock
    private NotificationHistoryRepository notificationHistoryRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("기존 토큰 재등록 시 기기 소유권자 불일치에 따른 소유자 갱신 로직 검증")
    void registerToken_OwnerChanged_UpdateOwner() {
        // [Given] 신규 유저(ID: 2)와 이전 유저 (ID: 1) 세팅
        User newUser = mock(User.class);
        when(newUser.isDiscountNotificationEnabled()).thenReturn(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(newUser));

        User oldUser = mock(User.class);
        when(oldUser.getId()).thenReturn(1L);

        // [Given] 이전 유저가 등록했던 토큰을 조회하도록 세팅
        FcmToken existingToken = mock(FcmToken.class);
        when(existingToken.getUser()).thenReturn(oldUser);
        when(fcmTokenRepository.findByToken("test-token")).thenReturn(Optional.of(existingToken));

        // [When] 신규 유저(ID: 2)가 동일한 토큰으로 등록 요청
        notificationService.registerToken(2L, "test-token");

        // [Then] DB에 새로 저장하지 않고, 기존 토큰의 소유자 정보만 갱신(updateOwner)하는지 검증
        verify(existingToken, times(1)).updateOwner(newUser);
        verify(fcmTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("다른 유저의 알림을 삭제하려고 할 때 권한 없음 예외가 발생하는지 확인")
    void deleteNotification_Unauthorized_ThrowException() {
        // [Given] 알림 작성자(ID: 1)의 알림 이력 데이터 세팅
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(1L);

        NotificationHistory history = mock(NotificationHistory.class);
        when(history.getUser()).thenReturn(owner);
        when(notificationHistoryRepository.findById(100L)).thenReturn(Optional.of(history));

        // [When & Then] 다른 유저(ID: 2)가 삭제를 요청하면 UNAUTHORIZED 예외가 터지는지 확인
        assertThatThrownBy(() -> notificationService.deleteNotification(2L, 100L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.UNAUTHORIZED.getMessage());
    }

    @Test
    @DisplayName("FCM 전체 발송 결과에서 유효하지 않은 토큰만 추려내어 삭제하는지 확인")
    void cleanupInvalidTokens_UnregisteredToken_DeleteFromDB() {
        // [Given] 정상 응답 1건과 무효 응답(UNREGISTERED) 1건이 섞인 발송 결과 세팅
        BatchResponse batchResponse = mock(BatchResponse.class);
        when(batchResponse.getFailureCount()).thenReturn(1);

        SendResponse successResponse = mock(SendResponse.class);
        when(successResponse.isSuccessful()).thenReturn(true);

        SendResponse failResponse = mock(SendResponse.class);
        when(failResponse.isSuccessful()).thenReturn(false);

        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(failResponse.getException()).thenReturn(exception);

        when(batchResponse.getResponses()).thenReturn(List.of(successResponse, failResponse));

        // [When] 전체 토큰 리스트를 넘겨서 무효 토큰 정리 로직 실행
        List<String> tokens = List.of("valid-token", "invalid-token");
        notificationService.cleanupInvalidTokens(batchResponse, tokens);

        // [Then] 에러가 발생한 토큰("invalid-token")만 정확히 찾아서 리포지토리 삭제 메서드로 넘기는지 검증
        verify(fcmTokenRepository, times(1)).deleteByTokenIn(List.of("invalid-token"));
    }
}