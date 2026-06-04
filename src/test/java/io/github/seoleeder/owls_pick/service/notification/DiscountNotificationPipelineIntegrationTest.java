package io.github.seoleeder.owls_pick.service.notification;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import io.github.seoleeder.owls_pick.client.itad.ItadDataCollector;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal.Price;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal.OriginalPrice;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal.Shop;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail.StoreName;
import io.github.seoleeder.owls_pick.entity.notification.FcmToken;
import io.github.seoleeder.owls_pick.entity.notification.NotificationHistory;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.repository.*;
import io.github.seoleeder.owls_pick.service.client.itad.ItadSyncService;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@Tag("integration")
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DiscountNotificationPipelineIntegrationTest.TestConfig.class)
class DiscountNotificationPipelineIntegrationTest extends AbstractContainerBaseTest {

    // @Async 비동기 로직을 메인 스레드에서 실행하도록 TaskExecutor 단일 스레드 설정
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public AsyncTaskExecutor applicationTaskExecutor() {
            return new TaskExecutorAdapter(new SyncTaskExecutor());
        }
    }

    @Autowired private ItadSyncService itadSyncService;
    @Autowired private GameRepository gameRepository;
    @Autowired private StoreDetailRepository storeDetailRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FcmTokenRepository fcmTokenRepository;

    // AFTER_COMMIT 시점의 트랜잭션 쓰기 무시(Flush 누락)를 우회하여 저장 데이터를 캡처하기 위한 모킹
    @MockitoBean private NotificationHistoryRepository notificationHistoryRepository;

    @MockitoBean private ItadDataCollector itadDataCollector;
    @MockitoBean private WishlistRepository wishlistRepository;

    private MockedStatic<FirebaseMessaging> firebaseMessagingMock;
    private FirebaseMessaging mockFirebaseMessaging;

    @Captor private ArgumentCaptor<NotificationHistory> historyCaptor;

    private Game targetGame;

    @BeforeEach
    void setUp() {
        // FCM 발송 클래스 정적 모킹 (네트워크 I/O 차단)
        mockFirebaseMessaging = mock(FirebaseMessaging.class);
        firebaseMessagingMock = mockStatic(FirebaseMessaging.class);
        firebaseMessagingMock.when(FirebaseMessaging::getInstance).thenReturn(mockFirebaseMessaging);

        // 테스트 대상 게임 및 스토어 데이터 세팅
        targetGame = gameRepository.save(Game.builder().title("Sale Game").itadId("ITAD_SALE_1").type("Main Game").build());
        storeDetailRepository.save(StoreDetail.builder()
                .game(targetGame).storeName(StoreName.STEAM).originalPrice(20000).discountPrice(20000).discountRate(0).build());

        // 알림 수신 동의 유저(2명) 및 비동의 유저(1명) 데이터 세팅
        User user1 = userRepository.save(User.builder().email("user1@test.com").name("동의자A").isDiscountNotificationEnabled(true).build());
        User user2 = userRepository.save(User.builder().email("user2@test.com").name("동의자B").isDiscountNotificationEnabled(true).build());
        User user3 = userRepository.save(User.builder().email("user3@test.com").name("미동의자").isDiscountNotificationEnabled(false).build());

        fcmTokenRepository.save(FcmToken.builder().user(user1).token("TOKEN_A").build());
        fcmTokenRepository.save(FcmToken.builder().user(user2).token("TOKEN_B").build());
        fcmTokenRepository.save(FcmToken.builder().user(user3).token("TOKEN_C").build());

        // 대상자 위시리스트 조회 응답 모킹
        given(wishlistRepository.findTargetUserIdsForDiscountPush(targetGame.getId()))
                .willReturn(List.of(user1.getId(), user2.getId(), user3.getId()));
    }

    @AfterEach
    void tearDown() {
        firebaseMessagingMock.close();
        fcmTokenRepository.deleteAllInBatch();
        storeDetailRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[통합 검증] 게임 가격 동기화 시 이벤트 발행 및 수신 동의자 대상 알림 파이프라인 작동 확인")
    void discount_notification_pipeline_integration_success() throws Exception {
        // [Given] ITAD 50% 할인 API 응답 모킹
        Deal deal = new Deal(
                new Shop("61", "Steam"), new Price(10000), new OriginalPrice(20000),
                null, 50, OffsetDateTime.now().plusDays(1), "http://steam-url.com"
        );
        ItadPriceResponse priceResponse = new ItadPriceResponse("ITAD_SALE_1", List.of(deal));
        given(itadDataCollector.collectPrices(anyList())).willReturn(List.of(priceResponse));

        // [Given] FCM 전송 NPE 방지용 모킹
        given(mockFirebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                .willReturn(mock(BatchResponse.class));

        // [When] 가격 동기화 실행 (트랜잭션 커밋 -> AFTER_COMMIT 리스너 실행 흐름 연계)
        itadSyncService.syncPrices();

        // [Then] 수신 동의 유저 수(2명)만큼 FCM 발송 호출 횟수 검증
        verify(mockFirebaseMessaging, times(2)).sendEachForMulticast(any(MulticastMessage.class));

        // [Then] 비동의 유저 필터링 및 동의 유저 대상 알림 이력 저장 상태 확인
        verify(notificationHistoryRepository, times(2)).save(historyCaptor.capture());

        List<NotificationHistory> capturedHistories = historyCaptor.getAllValues();
        assertThat(capturedHistories).hasSize(2);
        assertThat(capturedHistories).extracting(h -> h.getUser().getName())
                .containsExactlyInAnyOrder("동의자A", "동의자B")
                .doesNotContain("미동의자");
    }
}