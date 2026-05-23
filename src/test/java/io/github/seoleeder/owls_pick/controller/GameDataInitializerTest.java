package io.github.seoleeder.owls_pick.controller;

import io.github.seoleeder.owls_pick.controller.admin.GameDataInitializer;
import io.github.seoleeder.owls_pick.global.config.AdminAuthorizationInterceptor;
import io.github.seoleeder.owls_pick.global.config.WebConfig;
import io.github.seoleeder.owls_pick.global.config.properties.AdminProperties;
import io.github.seoleeder.owls_pick.service.client.igdb.IgdbSyncService;
import io.github.seoleeder.owls_pick.service.client.itad.ItadSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamAppSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamDashboardSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamReviewSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameDataInitializer.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, AdminAuthorizationInterceptor.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = "owls-pick.admin-key=valid-key") // 인터셉터 통과를 위해 설정
class GameDataInitializerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private SteamAppSyncService steamAppService;
    @MockitoBean private SteamDashboardSyncService steamDashboardService;
    @MockitoBean private SteamReviewSyncService steamReviewService;
    @MockitoBean private IgdbSyncService igdbService;
    @MockitoBean private ItadSyncService itadService;

    private final String ADMIN_KEY = "valid-key";

    @Test
    @DisplayName("[컨트롤러] Steam 앱 리스트 초기화")
    void initSteamAppList_Logic() throws Exception {
        mockMvc.perform(post("/admin/init/steam-app-list")
                        .header("X-ADMIN-KEY", ADMIN_KEY))
                .andExpect(status().isOk());

        // 로직 확인에만 집중
        verify(steamAppService, timeout(1000)).syncAppList();
    }

    @Test
    @DisplayName("[컨트롤러] Steam 게임 리뷰 데이터 초기화")
    void initReviews_Logic() throws Exception {
        mockMvc.perform(post("/admin/init/reviews")
                        .header("X-ADMIN-KEY", ADMIN_KEY))
                .andExpect(status().isOk());

        verify(steamReviewService, timeout(1000)).initAllReviews();
    }

    @Test
    @DisplayName("[컨트롤러] Steam 대시보드 데이터 초기화 (이력 & 실시간)")
    void initDashboard_Logic() throws Exception {
        mockMvc.perform(post("/admin/init/dashboard")
                        .header("X-ADMIN-KEY", ADMIN_KEY))
                .andExpect(status().isOk());

        verify(steamDashboardService, timeout(1000)).syncHistoricalDashboards();
        verify(steamDashboardService, timeout(1000)).syncRealTimeData();
    }

    @Test
    @DisplayName("[컨트롤러] IGDB 메타데이터 초기화")
    void initIgdb_Logic() throws Exception {
        mockMvc.perform(post("/admin/init/igdb")
                        .header("X-ADMIN-KEY", ADMIN_KEY))
                .andExpect(status().isOk());

        verify(igdbService, timeout(1000)).backfillAllGames();
    }

    @Test
    @DisplayName("[컨트롤러] ITAD 데이터 초기화 (ID & 가격)")
    void initItad_Logic() throws Exception {
        mockMvc.perform(post("/admin/init/itad")
                        .header("X-ADMIN-KEY", ADMIN_KEY))
                .andExpect(status().isOk());

        verify(itadService, timeout(1000)).syncMissingItadIds();
        verify(itadService, timeout(1000)).syncPrices();
    }

    @Test
    @DisplayName("[컨트롤러] 전체 게임 데이터 병렬 초기화")
    void initAll_Logic() throws Exception {
        mockMvc.perform(post("/admin/init/init-all")
                        .header("X-ADMIN-KEY", ADMIN_KEY))
                .andExpect(status().isOk());

        // 전체 초기화 시 호출되는 모든 서비스 메서드 검증
        verify(steamAppService, timeout(1000)).syncAppList();
        verify(igdbService, timeout(1000)).backfillAllGames();
        verify(itadService, timeout(1000)).syncMissingItadIds();
        verify(itadService, timeout(1000)).syncPrices();
        verify(steamDashboardService, timeout(1000)).syncHistoricalDashboards();
        verify(steamReviewService, timeout(1000)).initAllReviews();
    }
}