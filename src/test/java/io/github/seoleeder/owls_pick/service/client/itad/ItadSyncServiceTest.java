package io.github.seoleeder.owls_pick.service.client.itad;

import io.github.seoleeder.owls_pick.client.itad.ItadDataCollector;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadBulkResponse;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal.Price;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal.OriginalPrice;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal.StoreLow;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse.Deal.Shop;
import io.github.seoleeder.owls_pick.global.config.properties.ItadProperties;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail.StoreName;
import io.github.seoleeder.owls_pick.service.client.itad.event.GameDiscountEvent;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItadSyncServiceTest {

    private ItadSyncService itadSyncService;

    @Mock private ItadDataCollector collector;
    @Mock private GameRepository gameRepository;
    @Mock private StoreDetailRepository storeDetailRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Captor private ArgumentCaptor<List<Game>> gameListCaptor;
    @Captor private ArgumentCaptor<List<StoreDetail>> storeDetailListCaptor;
    @Captor private ArgumentCaptor<GameDiscountEvent> eventCaptor;

    @BeforeEach
    void setUp() {
        ItadProperties props = new ItadProperties("test-key", "api.test.com", 100, 20);

        // 서비스 객체 생성 (플랫폼 스레드 풀 내부 생성)
        itadSyncService = new ItadSyncService(
                collector, gameRepository, storeDetailRepository,
                transactionTemplate, eventPublisher, props
        );

        // 반환형 TransactionTemplate 콜백 내부 로직 강제 실행 모킹
        lenient().doAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("[초기 수집] ITAD ID 누락 게임 대상 스팀 ID 기반 UUID 조회 및 매핑 검증")
    void syncMissingItadIds_Success() {
        // [Given] ITAD ID가 누락된 게임 엔티티 구성 및 커서(ID: 10L) 세팅
        Game game = Game.builder().id(1L).title("Target Game").build();
        StoreDetail detail = StoreDetail.builder().id(10L).game(game).storeAppId("STEAM_123").build();

        // 1회차(0L) 조회 시 데이터 반환, 2회차(10L) 조회 시 Empty 반환으로 루프 종료
        given(storeDetailRepository.findValidGamesMissingItadId(eq(StoreName.STEAM), eq(0L), anyInt()))
                .willReturn(List.of(detail));
        given(storeDetailRepository.findValidGamesMissingItadId(eq(StoreName.STEAM), eq(10L), anyInt()))
                .willReturn(Collections.emptyList());

        // [Given] 스팀 ID(STEAM_123)로 ITAD Bulk API 호출 시 반환될 UUID 응답 모킹
        ItadBulkResponse bulkResponse = mock(ItadBulkResponse.class);
        given(bulkResponse.getUuidBySteamId("STEAM_123")).willReturn("ITAD_456");
        given(collector.collectItadIdsBulk(anyInt(), anyList())).willReturn(bulkResponse);

        // [When] ITAD ID 백필 파이프라인 실행
        itadSyncService.syncMissingItadIds();

        // [Then] ITAD ID 정상 매핑 검증
        verify(gameRepository, times(1)).saveAll(gameListCaptor.capture());
        assertThat(gameListCaptor.getValue().get(0).getItadId()).isEqualTo("ITAD_456");
    }

    @Test
    @DisplayName("[가격 갱신] 동일 스토어 중복 딜 응답 시 최저가 필터링 및 이벤트 발행 검증")
    void syncPrices_UpdateBestDealAndPublishEvent() {
        // [Given] ITAD ID 보유 타겟 게임 설정 (커서 기반 페이징 조회 모킹)
        Game game = Game.builder().id(1L).itadId("ITAD_1").title("Sale Game").build();
        given(gameRepository.findValidGamesWithItadId(eq(0L), anyInt())).willReturn(List.of(game));
        given(gameRepository.findValidGamesWithItadId(eq(1L), anyInt())).willReturn(Collections.emptyList());

        // [Given] 동일 스토어(Steam) 내 최고가(1500) 및 최저가(1000) 중복 딜 응답 모킹
        Deal deal1 = new Deal(new Shop("61", "Steam"), new Price(1500), new OriginalPrice(2000), new StoreLow(500), 25, OffsetDateTime.now().plusDays(1), "url1");
        Deal deal2 = new Deal(new Shop("61", "Steam"), new Price(1000), new OriginalPrice(2000), new StoreLow(500), 50, OffsetDateTime.now().plusDays(2), "url2");

        ItadPriceResponse priceRes = new ItadPriceResponse("ITAD_1", List.of(deal1, deal2));
        given(collector.collectPrices(anyList())).willReturn(List.of(priceRes));

        // [Given] 기존 DB에 존재하던 스토어 상세 정보 세팅 (IN 쿼리 일괄 조회 대응)
        StoreDetail existingDetail = StoreDetail.builder()
                .id(10L)
                .game(game)
                .storeName(StoreName.STEAM)
                .originalPrice(2000)
                .discountPrice(2000)
                .discountRate(0)
                .build();
        given(storeDetailRepository.findAllByGamesAndStoreNames(anyList(), anyList()))
                .willReturn(List.of(existingDetail));

        // [When] 가격 동기화 로직 실행
        itadSyncService.syncPrices();

        // [Then] 최저가(1000원) 딜로 엔티티 필드 업데이트 확인 (기존 엔티티는 Dirty Checking 대상이므로 saveAll 미호출)
        assertThat(existingDetail.getDiscountPrice()).isEqualTo(1000);
        assertThat(existingDetail.getDiscountRate()).isEqualTo(50);
        verify(storeDetailRepository, never()).saveAll(anyList());

        // [Then] 할인 조건(cut > 0) 충족에 따른 이벤트 발행 확인
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().gameId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().discountRate()).isEqualTo(50);
    }

    @Test
    @DisplayName("[가격 갱신] DB 데이터와 최신 응답 가격 일치 시 저장 스킵(Skip) 검증")
    void syncPrices_SkipIfSame() {
        // [Given] 타겟 게임 엔티티 세팅 (커서 기반 페이징 조회 모킹)
        Game game = Game.builder().id(1L).itadId("ITAD_1").build();
        given(gameRepository.findValidGamesWithItadId(eq(0L), anyInt())).willReturn(List.of(game));
        given(gameRepository.findValidGamesWithItadId(eq(1L), anyInt())).willReturn(Collections.emptyList());

        // [Given] 할인율 0% 및 만료일 null 응답 모킹
        Deal deal = new Deal(
                new Shop("61", "Steam"), new Price(1000), new OriginalPrice(1000),
                null, 0, null, "http://steam.com"
        );
        ItadPriceResponse priceRes = new ItadPriceResponse("ITAD_1", List.of(deal));
        given(collector.collectPrices(anyList())).willReturn(List.of(priceRes));

        // [Given] 응답 데이터와 동일한 기존 DB 엔티티 모킹
        StoreDetail sameDetail = StoreDetail.builder()
                .id(1L).game(game).storeName(StoreName.STEAM)
                .originalPrice(1000).discountPrice(null).discountRate(0)
                .historicalLow(null).url("http://steam.com").build();

        given(storeDetailRepository.findAllByGamesAndStoreNames(anyList(), anyList())).willReturn(List.of(sameDetail));

        // [When] 가격 동기화 로직 실행
        itadSyncService.syncPrices();

        // [Then] isSamePriceInfo 필터 조건 충족으로 인한 DB I/O 및 이벤트 발행 스킵 검증
        verify(storeDetailRepository, never()).saveAll(anyList());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("[엔티티 필터링] 단일 ITAD ID 다수 매핑 시 본편(Main Game) 우선 갱신 및 신규 엔티티 saveAll 검증")
    void syncPrices_CanonicalGameFiltering() {
        // [Given] 동일 ITAD ID를 공유하는 본편(Main Game)과 DLC 혼합 조회 모킹 (마지막 ID 2L 기준 탈출)
        Game mainGame = Game.builder().id(1L).itadId("ITAD_1").type("Main Game").firstRelease(LocalDate.of(2020,1,1)).build();
        Game dlcGame = Game.builder().id(2L).itadId("ITAD_1").type("DLC").firstRelease(LocalDate.of(2021,1,1)).build();

        given(gameRepository.findValidGamesWithItadId(eq(0L), anyInt())).willReturn(List.of(mainGame, dlcGame));
        given(gameRepository.findValidGamesWithItadId(eq(2L), anyInt())).willReturn(Collections.emptyList());

        // [Given] 스토어 가격 응답 스텁 객체 세팅
        Deal deal = new Deal(new Shop("61", "Steam"), new Price(1000), new OriginalPrice(2000), new StoreLow(500), 50, null, "url");
        ItadPriceResponse priceRes = new ItadPriceResponse("ITAD_1", List.of(deal));
        given(collector.collectPrices(anyList())).willReturn(List.of(priceRes));

        // [Given] DB에 기존 데이터가 없는 신규 생성 상황 세팅
        given(storeDetailRepository.findAllByGamesAndStoreNames(anyList(), anyList())).willReturn(Collections.emptyList());

        // [When] 가격 동기화 로직 실행
        itadSyncService.syncPrices();

        // [Then] DLC 엔티티를 제외하고 본편(Main Game, ID 1L) 객체 기반 신규 StoreDetail만 saveAll 전달됨을 검증
        verify(storeDetailRepository).saveAll(storeDetailListCaptor.capture());
        StoreDetail savedDetail = storeDetailListCaptor.getValue().get(0);
        assertThat(savedDetail.getGame().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[예외 처리] API 통신 장애 발생 시 스레드 종료 방어 및 롤백 제어 검증")
    void processPriceBatchSafe_FaultTolerance() {
        // [Given] 외부 API 타임아웃 예외(RestClientException) 강제 발생 모킹
        Game game = Game.builder().id(1L).itadId("ITAD_1").build();
        given(collector.collectPrices(anyList())).willThrow(new RestClientException("Connection Timeout"));

        // [When] 단일 배치 단위 처리 메서드 직접 호출
        int updatedCount = itadSyncService.processPriceBatchSafe(List.of(game));

        // [Then] 예외 캐치 후 다운 방어 및 단건 롤백(0 반환) 처리 검증
        assertThat(updatedCount).isEqualTo(0);
        verify(transactionTemplate, never()).execute(any());
    }
}