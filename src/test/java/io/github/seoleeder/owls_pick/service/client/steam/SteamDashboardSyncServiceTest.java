package io.github.seoleeder.owls_pick.service.client.steam;

import io.github.seoleeder.owls_pick.client.steam.SteamDataCollector;
import io.github.seoleeder.owls_pick.client.steam.dto.Dashboard.SteamDashboardResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.Dashboard.SteamDashboardResponse.Rank;
import io.github.seoleeder.owls_pick.global.config.properties.SteamProperties;
import io.github.seoleeder.owls_pick.entity.game.Dashboard;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.DashboardRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import io.github.seoleeder.owls_pick.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SteamDashboardSyncServiceTest {

    private SteamDashboardSyncService steamDashboardSyncService;

    @Mock private SteamDataCollector collector;
    @Mock private StoreDetailRepository storeDetailRepository;
    @Mock private DashboardRepository dashboardRepository;
    @Mock private DashboardService dashboardService;
    @Mock private TransactionTemplate transactionTemplate;

    @Captor
    private ArgumentCaptor<List<Dashboard>> dashboardListCaptor;

    @BeforeEach
    void setUp() {
        // 프로퍼티 설정값 수동 생성 및 의존성 주입 구성(최소 수집일: 2022-01-01)
        SteamProperties props = new SteamProperties(
                null, null, null, null,
                new SteamProperties.Dashboard(LocalDate.of(2022, 1, 1), "KR", 100)
        );

        steamDashboardSyncService = new SteamDashboardSyncService(
                collector, storeDetailRepository, dashboardRepository,
                dashboardService, transactionTemplate, props
        );

        // TransactionTemplate 내부 람다 콜백 강제 실행 모킹
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("[실시간] 동시 접속자 데이터 수집 시 기존 삭제 보장 및 미보유 게임 제외 필터링 검증")
    void syncConcurrentPlayers_Success() {
        // [Given] 실시간 API 응답 데이터 설정 (동시 접속자 수, 최다 플레이)
        List<Rank> ranks = List.of(new Rank(1, 100L), new Rank(2, 200L));
        SteamDashboardResponse response = new SteamDashboardResponse(LocalDateTime.now(), ranks);

        // [Given] 실시간 배치 내 순차적인 외부 API 호출(동시 접속자, 최다 플레이)에 대한 모킹 정의
        given(collector.collectConcurrentPlayersTopApp("KR")).willReturn(response);
        given(collector.collectMostPlayedApp("KR")).willReturn(response);

        // [Given] DB에 없는 게임(200번)의 필터링 동작을 검증하기 위해 100번 게임 데이터만 매핑되도록 상태 구성
        Game game100 = Game.builder().id(1L).title("PUBG").build();
        StoreDetail detail100 = StoreDetail.builder().game(game100).storeAppId("100").build();
        given(storeDetailRepository.findByStoreNameAndStoreAppIdIn(eq(StoreDetail.StoreName.STEAM), anyList()))
                .willReturn(List.of(detail100));

        // [When] 실시간 데이터 동기화 메인 로직 실행
        steamDashboardSyncService.syncRealTimeData();

        // [Then] 데이터 갱신 전 기존 데이터 삭제 로직 정상 호출 검증
        verify(dashboardRepository, times(2)).deleteByCurationTypeAndReferenceAt(any(), any());

        // [Then] DB에 없는 게임(200번) 필터링 처리 후 정상 데이터 1건만 유입되었는지 검증
        verify(dashboardRepository, times(2)).saveAll(dashboardListCaptor.capture());
        List<Dashboard> savedDashboards = dashboardListCaptor.getValue();
        assertThat(savedDashboards).hasSize(1);
        assertThat(savedDashboards.get(0).getGame().getTitle()).isEqualTo("PUBG");
        assertThat(savedDashboards.get(0).getRank()).isEqualTo(1);

        // [Then] 실시간 차트별(동시 접속자 수, 최다 플레이) 캐시 갱신 호출 검증
        verify(dashboardService).refreshCache(Dashboard.CurationType.CONCURRENT_PLAYER);
        verify(dashboardService).refreshCache(Dashboard.CurationType.MOST_PLAYED);
    }

    @Test
    @DisplayName("[정기/주간] 차트에 기존 데이터가 없을 시 외부 API 수집 및 DB 저장 동작 검증")
    void syncScheduledWeekly_Success() {
        // [Given] DB 내 집계 데이터가 없는 상태 반환 설정
        given(dashboardRepository.existsByCurationTypeAndReferenceAt(eq(Dashboard.CurationType.WEEKLY_TOP_SELLER), any()))
                .willReturn(false);

        // [Given] 통신 응답 객체 생성 및 DB 매핑용 조회 결과 모킹
        List<Rank> ranks = List.of(new Rank(1, 100L));
        SteamDashboardResponse response = new SteamDashboardResponse(LocalDateTime.now(), ranks);
        given(collector.collectWeeklyTopSellers(any(), any(), any(), any())).willReturn(response);

        Game game = Game.builder().id(1L).title("Elden Ring").build();
        StoreDetail detail = StoreDetail.builder().game(game).storeAppId("100").build();
        given(storeDetailRepository.findByStoreNameAndStoreAppIdIn(eq(StoreDetail.StoreName.STEAM), anyList()))
                .willReturn(List.of(detail));

        // [When] 주간 정기 차트 업데이트 로직 실행
        steamDashboardSyncService.syncScheduledWeekly();

        // [Then] 큐레이션 타입(WEEKLY_TOP_SELLER) 정합성 및 저장 호출 검증
        verify(dashboardRepository, times(1)).saveAll(dashboardListCaptor.capture());
        List<Dashboard> savedDashboards = dashboardListCaptor.getValue();
        assertThat(savedDashboards).hasSize(1);
        assertThat(savedDashboards.get(0).getCurationType()).isEqualTo(Dashboard.CurationType.WEEKLY_TOP_SELLER);

        // [Then] 영속성 저장 로직 완료 후 캐시 갱신 1회 호출 상태 확인
        verify(dashboardService).refreshCache(Dashboard.CurationType.WEEKLY_TOP_SELLER);
    }

    @Test
    @DisplayName("[정기/주간] 대상 기간 차트 데이터 이미 존재 시 외부 API 통신 차단 검증")
    void syncScheduledWeekly_SkipIfExists() {
        // [Given] DB 내 해당 기간 데이터 이미 존재 상태 반환 (중복 수집 방어 목적)
        given(dashboardRepository.existsByCurationTypeAndReferenceAt(eq(Dashboard.CurationType.WEEKLY_TOP_SELLER), any()))
                .willReturn(true);

        // [When] 주간 정기 차트 업데이트 로직 실행
        steamDashboardSyncService.syncScheduledWeekly();

        // [Then] 불필요한 네트워크 I/O 및 트랜잭션 쿼리 호출 사전 차단 검증
        verify(collector, never()).collectWeeklyTopSellers(any(), any(), any(), any());
        verify(dashboardRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("과거 대시보드 백필(Backfill) 수행 시 반복 호출 및 지연된 일괄 캐시 갱신 검증")
    void syncHistoricalDashboards_LoopUntilPresent() {
        // [Given] 백필 루프 순회 검증을 위한 중복 판별 패스(false) 및 더미 응답 데이터 구성
        given(dashboardRepository.existsByCurationTypeAndReferenceAt(any(), any())).willReturn(false);

        SteamDashboardResponse emptyResponse = new SteamDashboardResponse(LocalDateTime.now(), Collections.emptyList());
        given(collector.collectWeeklyTopSellers(anyString(), anyLong(), anyInt(), anyInt())).willReturn(emptyResponse);
        given(collector.collectMonthTopApp(anyLong())).willReturn(emptyResponse);
        given(collector.collectYearTopApp(anyLong())).willReturn(emptyResponse);

        // [When] 과거 대시보드 데이터 백필 파이프라인 실행
        steamDashboardSyncService.syncHistoricalDashboards();

        // [Then] 최소 수집일부터 현재까지의 기간 산정에 기반한 외부 API 반복 다회 호출(atLeastOnce) 검증
        verify(collector, atLeastOnce()).collectWeeklyTopSellers(anyString(), anyLong(), anyInt(), anyInt());
        verify(collector, atLeastOnce()).collectMonthTopApp(anyLong());
        verify(collector, atLeastOnce()).collectYearTopApp(anyLong());

        // [Then] 전체 백필 종료 후 모든 큐레이션 타입에 대한 Redis 캐시 초기화 발생 횟수 검증
        verify(dashboardService, times(Dashboard.CurationType.values().length)).refreshCache(any());
    }
}