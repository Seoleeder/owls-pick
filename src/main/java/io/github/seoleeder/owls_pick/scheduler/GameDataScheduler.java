package io.github.seoleeder.owls_pick.scheduler;

import io.github.seoleeder.owls_pick.global.config.properties.SchedulerProperties;
import io.github.seoleeder.owls_pick.service.client.hltb.HltbSyncService;
import io.github.seoleeder.owls_pick.service.client.igdb.IgdbSyncService;
import io.github.seoleeder.owls_pick.service.client.itad.ItadSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamAppSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamDashboardSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamReviewSyncService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameDataScheduler {
    private final SteamAppSyncService steamAppService;
    private final SteamDashboardSyncService steamDashboardService;
    private final SteamReviewSyncService steamReviewService;
    private final HltbSyncService hltbSyncService;
    private final IgdbSyncService igdbService;
    private final ItadSyncService itadService;
    private final SchedulerProperties schedulerProperties;

    /**
     * 컨테이너 구동 시 스케줄러 활성화 설정 주입 여부 검증
     */
    @PostConstruct
    public void init() {
        log.warn("[Scheduler Config] owls-pick.scheduler.enabled: {}", schedulerProperties.enabled());
    }

    /**
     * 스케줄러 실행 비활성화 여부 검증
     */
    private boolean isSchedulerDisabled() {
        if (!schedulerProperties.enabled()) {
            log.warn("[Scheduler] Scheduled task skipped: background execution is currently disabled.");
            return true;
        }
        return false;
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void scheduleDailyFullSync(){
        if (isSchedulerDisabled()) return;
        log.debug("[Scheduler] Daily Full Sync Started");

        try {
            // 1. Steam App List
            log.info("[Scheduler] 1. Steam App List Sync");
            steamAppService.syncAppList();

            // 2. IGDB Metadata
            log.info("[Scheduler] 2. IGDB Metadata Sync");
            igdbService.syncUpdatedGames();

            // 3. Steam Review Data
            log.info("[Scheduler] 3. Steam Review Data Sync");
            steamReviewService.syncReviews();

            log.info("[Scheduler] Daily Full Sync Finished!");

            // 4. HLTB PlaytimeData
            triggerAsyncHltbPipeline();

        } catch (Exception e) {
            log.error("[Scheduler] Daily Full Sync Failed", e);
        }
    }

    /**
     * HLTB 플레이타임 데이터 동기화
     */
    private void triggerAsyncHltbPipeline() {
        CompletableFuture.runAsync(() -> {
            log.info("[Scheduler] Starting Daily HLTB Sync Pipeline...");
            try {
                hltbSyncService.runSyncPipeline();
            } catch (Exception e) {
                log.error("[Scheduler] Daily HLTB Sync Failed", e);
            }
        });
    }

    @Scheduled(cron = "0 0 0,6,12,18 * * *")
    public void schedulePriceSync() {
        if (isSchedulerDisabled()) return;
        log.debug("[Scheduler] 6-Hour Price Sync Started");
        try {
            itadService.syncMissingItadIds();
            itadService.syncPrices();
            log.info("[Scheduler] Price Sync Completed!");
        } catch (Exception e) {
            log.error("[Scheduler] Price Sync Failed", e);
        }
    }

    // 실시간 차트 업데이트 스케줄링
    /**
     * 현재 최다 동시 접속자 수 게임 업데이트 (Concurrent Player Top Games)
     * - 주기 : 15분 간격 (0, 15, 30, 45)
     * */
    @Scheduled(cron = "0 0/15 * * * *")
    public void scheduleConcurrentPlayers(){
        if (isSchedulerDisabled()) return;
        log.debug("[Scheduler] 15-min Concurrent Players Sync Started");
        steamDashboardService.syncConcurrentPlayers();
        log.info("[Scheduler] 15-min Concurrent Players Finished!");
    }

    /**
     * 지난 24시간 최다 플레이 게임 업데이트 (Most Played Games)
     * - 주기 : 1시간 간격
     * */
    @Scheduled(cron =  "0 0 * * * *")
    public void scheduleMostPlayed(){
        if (isSchedulerDisabled()) return;
        log.debug("[Scheduler] Most Played (24h) Sync Started");
        steamDashboardService.syncMostPlayed();
        log.info("[Scheduler] Most Played Sync Finished!");
    }

    // 주기별 차트 업데이트 스케줄링 (Weekly / Monthly / Yearly)

    /**
     * 주간 최고 매출 게임 업데이트 (Weekly Top Sellers)
     *  - 주기 : 매주 회요일 01시 (PST) -> 매주 화요일 18시 (KST)
     */
    @Scheduled(cron = "0 0 18 * * TUE")
    public void scheduleWeeklyTopSellers(){
        if (isSchedulerDisabled()) return;
        log.debug("[Scheduler] Weekly Top Seller Game Sync Started (Tue 18:00 KST)");
        steamDashboardService.syncScheduledWeekly();
        log.info("[Scheduler] Weekly Top Seller Game Sync Finished!");
    }

    /**
     * 월간 최고 인기 게임 업데이트 (Month Top App)
     *  - 주기 : 매월 15일 10시 (PST) -> 매월 16일 03시 (KST)
     */
    @Scheduled(cron = "0 0 3 16 * *")
    public void scheduleMonthlyTopApp(){
        if (isSchedulerDisabled()) return;
        log.debug("[Scheduler] Monthly Top Game Sync Started (16th 03:00 KST)");
        steamDashboardService.syncScheduledMonthly();
        log.info("[Scheduler] Monthly Top Game Sync Finished!");
    }

    /**
     * 연간 최고 인기 게임 업데이트 (Year Top App)
     *  - 주기 : 매년 1월 15일 01:00 (PST) -> 매년 1월 15일 18:00 (KST)
     */
    @Scheduled(cron = "0 0 18 15 1 *")
    public void scheduleYearlyTopApp(){
        if (isSchedulerDisabled()) return;
        log.debug("[Scheduler] Yearly Top Game Sync Started (Jan 15th 18:00 KST)");
        steamDashboardService.syncScheduledYearly();
        log.info("[Scheduler] Yearly Top Game Sync Finished!");
    }
}
