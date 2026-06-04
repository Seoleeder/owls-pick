package io.github.seoleeder.owls_pick.service.client.steam;

import io.github.seoleeder.owls_pick.client.steam.SteamDataCollector;
import io.github.seoleeder.owls_pick.client.steam.dto.SteamAppListResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.SteamAppListResponse.Response;
import io.github.seoleeder.owls_pick.client.steam.dto.SteamAppListResponse.Response.App;
import io.github.seoleeder.owls_pick.client.steam.util.SteamGameUrlBuilder;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SteamAppSyncServiceTest {

    @InjectMocks
    private SteamAppSyncService steamAppSyncService;

    @Mock private SteamDataCollector collector;
    @Mock private GameRepository gameRepository;
    @Mock private StoreDetailRepository storeDetailRepository;
    @Mock private SteamGameUrlBuilder urlBuilder;
    @Mock private TransactionTemplate transactionTemplate;

    @Captor
    private ArgumentCaptor<List<Game>> gameListCaptor;

    @Captor
    private ArgumentCaptor<List<StoreDetail>> detailListCaptor;

    @BeforeEach
    void setUp() {
        // TransactionTemplate 내부 람다 콜백 강제 실행 모킹
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("DB 미존재 신규 게임 데이터 필터링 및 영속화 검증")
    void syncAppListSuccessSaveNewGames() {
        // [Given] 기존 DB에 앱 ID 100번이 이미 저장되어 있는 상태 구성
        given(storeDetailRepository.findAllAppIdsByStore(StoreDetail.StoreName.STEAM))
                .willReturn(Set.of("100"));

        // [Given] API 응답 데이터 구성 (ID 100: 중복, ID 200: 신규, ID 300: 이름 X 필터링 대상)
        List<App> apps = List.of(
                new App(100L, "Existing Game"),
                new App(200L, "New Game"),
                new App(300L, "")
        );
        Response responseBody = new Response(apps, false, 200L);
        SteamAppListResponse responseWrapper = new SteamAppListResponse(responseBody);
        given(collector.collectAppList(null)).willReturn(responseWrapper);

        // [Given] saveAll 호출 시 IndexOutOfBounds 방어를 위한 리스트 원본 반환 모킹 및 URL 설정
        given(gameRepository.saveAll(anyList())).willAnswer(invocation -> invocation.getArgument(0));
        given(urlBuilder.buildUrl("200")).willReturn("https://store.steampowered.com/app/200");

        // [When] 스팀 앱 리스트 동기화 파이프라인 실행
        steamAppSyncService.syncAppList();

        // [Then] ArgumentCaptor 기반 타입 안정성 검증: 신규 데이터(ID: 200) 1건 정상 필터링 확인
        verify(gameRepository, times(1)).saveAll(gameListCaptor.capture());
        List<Game> savedGames = gameListCaptor.getValue();
        assertThat(savedGames).hasSize(1);
        assertThat(savedGames.get(0).getTitle()).isEqualTo("New Game");

        verify(storeDetailRepository, times(1)).saveAll(detailListCaptor.capture());
        List<StoreDetail> savedDetails = detailListCaptor.getValue();
        assertThat(savedDetails).hasSize(1);
        assertThat(savedDetails.get(0).getStoreAppId()).isEqualTo("200");
    }

    @Test
    @DisplayName("API 호출 타임아웃 발생 시 에러 격리 및 파이프라인 조기 종료 검증")
    void syncAppListApiErrorStopLoop() {
        // [Given] 외부 API 통신 예외(타임아웃) 발생 상태 구성
        given(storeDetailRepository.findAllAppIdsByStore(any())).willReturn(Collections.emptySet());
        given(collector.collectAppList(null)).willThrow(new RestClientException("Connection Timeout"));

        // [When] 동기화 로직 실행
        steamAppSyncService.syncAppList();

        // [Then] 예외 발생에 따른 DB 영속성 연산 차단(never) 및 메인 루프 종료 검증
        then(gameRepository).shouldHaveNoInteractions();
        then(transactionTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("수집 데이터 모두 중복일 경우 저장 로직 생략 및 트랜잭션 차단 검증")
    void syncAppListAllExistingNoSave() {
        // [Given] 수집 대상 데이터가 기존 DB에 모두 존재하는 상태 구성
        given(storeDetailRepository.findAllAppIdsByStore(any())).willReturn(Set.of("100", "200"));

        List<App> apps = List.of(
                new App(100L, "Game 1"),
                new App(200L, "Game 2")
        );
        Response responseBody = new Response(apps, false, 200L);
        SteamAppListResponse responseWrapper = new SteamAppListResponse(responseBody);
        given(collector.collectAppList(null)).willReturn(responseWrapper);

        // [When] 동기화 로직 실행
        steamAppSyncService.syncAppList();

        // [Then] 신규 적재 대상이 없으므로 내부 저장 트랜잭션 및 Repository 호출 생략 검증
        verify(transactionTemplate, never()).executeWithoutResult(any());
        verify(gameRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("청크 단위 저장 실패 시 개별 저장(Fallback)으로 전환하여 정상 데이터만 복구하는지 검증")
    void syncAppList_ChunkSaveFail_IndividualRecovery() {
        // [Given] DB에 스팀 앱 데이터가 없는 상황 및 대상 데이터 2건(정상/불량 혼합) 구성
        given(storeDetailRepository.findAllAppIdsByStore(any())).willReturn(Collections.emptySet());

        List<App> apps = List.of(
                new App(1L, "Bad Game"),
                new App(2L, "Good Game")
        );
        SteamAppListResponse responseWrapper = new SteamAppListResponse(new Response(apps, false, 2L));
        given(collector.collectAppList(null)).willReturn(responseWrapper);

        given(gameRepository.saveAll(anyList())).willAnswer(invocation -> invocation.getArgument(0));
        given(urlBuilder.buildUrl(anyString())).willReturn("https://url");

        // [Given] 트랜잭션 단위 연속 예외 시나리오 조립 (1회차 청크 실패 -> 2회차 단건 실패 -> 3회차 단건 성공)
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Chunk Fail"))
                .doThrow(new org.springframework.dao.DataIntegrityViolationException("Single App 1 Fail"))
                .doAnswer(invocation -> {
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate).executeWithoutResult(any());

        // [When] 동기화 로직 실행
        steamAppSyncService.syncAppList();

        // [Then] 전체 청크 실패 후 단건 순회 분기로 진입하여 총 3회의 트랜잭션 시도가 발생했는지 확인
        verify(transactionTemplate, times(3)).executeWithoutResult(any());

        // [Then] 제약 조건을 위반한 데이터는 격리 폐기되고, 정상 데이터 1건만 영속 계층으로 유입되었는지 검증
        verify(gameRepository, times(1)).saveAll(gameListCaptor.capture());
        List<Game> savedGames = gameListCaptor.getValue();
        assertThat(savedGames).hasSize(1);
        assertThat(savedGames.get(0).getTitle()).isEqualTo("Good Game");
    }

    @Test
    @DisplayName("API 응답에 haveMoreResults=true 포함 시 커서를 갱신하여 다중 페이지를 모두 수집하는지 검증")
    void syncAppList_Pagination_FetchAllPages() {
        // [Given] 커서 기반 페이지네이션 응답 객체 모킹
        given(storeDetailRepository.findAllAppIdsByStore(any())).willReturn(Collections.emptySet());
        given(gameRepository.saveAll(anyList())).willAnswer(invocation -> invocation.getArgument(0));
        given(urlBuilder.buildUrl(anyString())).willReturn("https://url");

        // 1페이지 응답: 다음 페이지가 존재하며, 마지막 조회 커서(1000L) 포함
        SteamAppListResponse page1 = new SteamAppListResponse(
                new Response(List.of(new App(1L, "Game 1")), true, 1000L)
        );
        given(collector.collectAppList(null)).willReturn(page1);

        // 2페이지 응답: 가져올 데이터가 더 이상 없음
        SteamAppListResponse page2 = new SteamAppListResponse(
                new Response(List.of(new App(2L, "Game 2")), false, 2000L)
        );
        given(collector.collectAppList(1000L)).willReturn(page2);

        // [When] 동기화 로직 실행
        steamAppSyncService.syncAppList();

        // [Then] 1페이지 응답 커서(1000L)가 다음 API 호출 인자로 바인딩되어 두 번의 쿼리가 정상 수행되었는지 검증
        verify(collector, times(1)).collectAppList(null);
        verify(collector, times(1)).collectAppList(1000L);
        verify(gameRepository, times(2)).saveAll(anyList());
    }
}