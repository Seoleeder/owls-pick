package io.github.seoleeder.owls_pick.controller;

import io.github.seoleeder.owls_pick.controller.admin.GameDataInitializer;
import io.github.seoleeder.owls_pick.service.client.hltb.HltbSyncService;
import io.github.seoleeder.owls_pick.service.client.igdb.IgdbSyncService;
import io.github.seoleeder.owls_pick.service.client.itad.ItadSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamAppSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamDashboardSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamReviewSyncService;
import io.github.seoleeder.owls_pick.service.genai.ReviewSummaryService;
import io.github.seoleeder.owls_pick.service.genai.localization.KeywordLocalizationService;
import io.github.seoleeder.owls_pick.service.genai.localization.LocalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 전용 게임 데이터 초기화 컨트롤러 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
class GameDataInitializerTest {

    private MockMvc mockMvc;

    @InjectMocks
    private GameDataInitializer gameDataInitializer;

    // 외부 API 및 AI 파이프라인 서비스 모킹
    @Mock private SteamAppSyncService steamAppService;
    @Mock private SteamDashboardSyncService steamDashboardService;
    @Mock private SteamReviewSyncService steamReviewService;
    @Mock private IgdbSyncService igdbService;
    @Mock private ItadSyncService itadService;
    @Mock private HltbSyncService hltbSyncService;
    @Mock private LocalizationService localizationService;
    @Mock private KeywordLocalizationService keywordLocalizationService;
    @Mock private ReviewSummaryService reviewSummaryService;

    @BeforeEach
    void setUp() {
        // [Given] 컨텍스트 로딩을 배제하고 타겟 컨트롤러만 단독 격리하여 MockMvc 세팅
        mockMvc = MockMvcBuilders.standaloneSetup(gameDataInitializer).build();
    }

    @Test
    @DisplayName("Steam 앱 리스트 초기화 엔드포인트 호출 검증")
    void initSteamAppList_Logic() throws Exception {
        // [When] 앱 리스트 동기화 호출
        mockMvc.perform(post("/admin/init/steam-app-list"))
                .andExpect(status().isOk());

        // [Then] 대상 백그라운드 수집 서비스 위임 확인
        verify(steamAppService, timeout(1000)).syncAppList();
    }

    @Test
    @DisplayName("Steam 게임 리뷰 데이터 초기화 엔드포인트 호출 검증")
    void initReviews_Logic() throws Exception {
        // [When] 리뷰 데이터 동기화 호출
        mockMvc.perform(post("/admin/init/reviews"))
                .andExpect(status().isOk());

        // [Then] 리뷰 수집 서비스 위임 확인
        verify(steamReviewService, timeout(1000)).initAllReviews();
    }

    @Test
    @DisplayName("Steam 대시보드 데이터 초기화 엔드포인트 호출 검증")
    void initDashboard_Logic() throws Exception {
        // [When] 스팀 대시보드 초기화 호출
        mockMvc.perform(post("/admin/init/dashboard"))
                .andExpect(status().isOk());

        // [Then] 이력 및 실시간 통계 수집 서비스 위임 확인
        verify(steamDashboardService, timeout(1000)).syncHistoricalDashboards();
        verify(steamDashboardService, timeout(1000)).syncRealTimeData();
    }

    @Test
    @DisplayName("IGDB 메타데이터 초기화 엔드포인트 호출 검증")
    void initIgdb_Logic() throws Exception {
        // [When] IGDB 백필(Backfill) 호출
        mockMvc.perform(post("/admin/init/igdb"))
                .andExpect(status().isOk());

        // [Then] 메타데이터 백필(Backfill) 파이프라인으로 작업 위임 확인
        verify(igdbService, timeout(1000)).backfillAllGames();
    }

    @Test
    @DisplayName("ITAD 데이터 초기화 엔드포인트 라우팅 검증")
    void initItad_Logic() throws Exception {
        // [When] ITAD 가격 및 ID 동기화 호출
        mockMvc.perform(post("/admin/init/itad"))
                .andExpect(status().isOk());

        // [Then] ID 및 가격 동기화 서비스로 순차적 위임 확인
        verify(itadService, timeout(1000)).syncMissingItadIds();
        verify(itadService, timeout(1000)).syncPrices();
    }

    @Test
    @DisplayName("HLTB 플레이타임 데이터 초기화 엔드포인트 라우팅 검증")
    void initHltb_Logic() throws Exception {
        // [When] HLTB 플레이타임 동기화 호출
        mockMvc.perform(post("/admin/init/hltb"))
                .andExpect(status().isOk());

        // [Then] FastAPI 연동 파이프라인으로 작업 위임 확인
        verify(hltbSyncService, timeout(1000)).runSyncPipeline();
    }

    @Test
    @DisplayName("전체 게임 데이터 병렬 초기화 엔드포인트 라우팅 일괄 검증")
    void initAll_Logic() throws Exception {
        // [When] 일괄 초기화 엔드포인트 호출
        mockMvc.perform(post("/admin/init/init-all"))
                .andExpect(status().isOk());

        // [Then] 설계된 모든 비동기 수집 서비스로 누락 없이 작업 분배 확인
        verify(steamAppService, timeout(1000)).syncAppList();
        verify(igdbService, timeout(1000)).backfillAllGames();
        verify(itadService, timeout(1000)).syncMissingItadIds();
        verify(itadService, timeout(1000)).syncPrices();
        verify(steamDashboardService, timeout(1000)).syncHistoricalDashboards();
        verify(steamReviewService, timeout(1000)).initAllReviews();
    }
}