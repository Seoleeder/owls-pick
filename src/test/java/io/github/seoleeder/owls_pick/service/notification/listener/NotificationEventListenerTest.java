package io.github.seoleeder.owls_pick.service.notification.listener;

import io.github.seoleeder.owls_pick.service.client.itad.event.GameDiscountEvent;
import io.github.seoleeder.owls_pick.repository.WishlistRepository;
import io.github.seoleeder.owls_pick.service.notification.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationEventListenerTest {

    @InjectMocks
    private NotificationEventListener notificationEventListener;

    @Mock
    private WishlistRepository wishlistRepository;

    @Mock
    private NotificationService notificationService;

    @Test
    @DisplayName("특정 유저 알림 발송에 실패해도 전체 루프가 중단되지 않고 다음 유저에게 발송되는지 확인")
    void handleGameDiscountEvent_ExceptionIsolation_ContinueLoop() {
        // [Given] 푸시 알림을 받을 유저 3명과 할인 이벤트 데이터 세팅
        when(wishlistRepository.findTargetUserIdsForDiscountPush(100L))
                .thenReturn(List.of(1L, 2L, 3L));
        GameDiscountEvent event = new GameDiscountEvent(100L, 50, LocalDateTime.now());

        // [Given] 1번 유저에게 알림 발송 시 FCM 타임아웃 같은 런타임 에러가 발생하도록 모킹
        doThrow(new RuntimeException("FCM Timeout"))
                .when(notificationService).sendDiscountPush(eq(1L), eq(100L), eq(50), any());

        // [When] 비동기 할인 이벤트 리스너 실행
        notificationEventListener.handleGameDiscountEvent(event);

        // [Then] 1번 유저 발송에 실패하더라도 루프가 끊기지 않고 2, 3번 유저 발송 로직이 정상 호출되는지 검증
        verify(notificationService, times(1)).sendDiscountPush(eq(1L), eq(100L), eq(50), any());
        verify(notificationService, times(1)).sendDiscountPush(eq(2L), eq(100L), eq(50), any());
        verify(notificationService, times(1)).sendDiscountPush(eq(3L), eq(100L), eq(50), any());
    }
}