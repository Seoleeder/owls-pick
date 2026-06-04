package io.github.seoleeder.owls_pick.service.client.steam;

import io.github.seoleeder.owls_pick.client.steam.SteamDataCollector;
import io.github.seoleeder.owls_pick.client.steam.dto.Dashboard.SteamDashboardResponse;
import io.github.seoleeder.owls_pick.global.config.properties.SteamProperties;
import io.github.seoleeder.owls_pick.global.util.TimestampUtils;
import io.github.seoleeder.owls_pick.entity.game.Dashboard;
import io.github.seoleeder.owls_pick.entity.game.Dashboard.CurationType;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.DashboardRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import io.github.seoleeder.owls_pick.service.DashboardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.seoleeder.owls_pick.global.util.TimestampUtils.toEpoch;

@Slf4j
@Service
public class SteamDashboardSyncService {
    private final SteamDataCollector collector;
    private final StoreDetailRepository storeDetailRepository;
    private final DashboardRepository dashboardRepository;

    // Redis 캐시 관리용
    private final DashboardService dashboardService;

    // 트랜잭션 수동 제어용
    private final TransactionTemplate transactionTemplate;
    
    private final SteamProperties props;

    public SteamDashboardSyncService(
            SteamDataCollector collector,
            StoreDetailRepository storeDetailRepository,
            DashboardRepository dashboardRepository,
            DashboardService dashboardService,
            TransactionTemplate transactionTemplate,
            SteamProperties props
    ) {
        this.collector = collector;
        this.storeDetailRepository = storeDetailRepository;
        this.dashboardRepository = dashboardRepository;
        this.dashboardService = dashboardService;
        this.transactionTemplate = transactionTemplate;
        this.props = props;
    }
    
    /**
     * 최소 수집 일자부터 현재까지의 모든 대시보드 데이터 수집
     * */
    public void syncHistoricalDashboards(){
        log.info("Starting Historical Dashboard Data Backfill from {}...", props.dashboard().minCollectionDate());
        
        // WeeklyTopSellers (매주 화요일부터 집계 (UTC 기준))
        LocalDate weeklyCursor = props.dashboard().minCollectionDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
        while (weeklyCursor.isBefore(LocalDate.now())) {
            processWeeklyCollection(toEpoch(weeklyCursor),0, props.dashboard().pageCount());
            weeklyCursor = weeklyCursor.plusWeeks(1);
            sleep(100); // API 과부하 방지
        }

        // MonthTopApps (매달 1일부터 그 달에 출시된 인기 게임 집계 (UTC 기준))
        LocalDate monthlyCursor = props.dashboard().minCollectionDate().withDayOfMonth(1);
        while (monthlyCursor.isBefore(LocalDate.now())) {
                processMonthlyCollection(toEpoch(monthlyCursor));
            monthlyCursor = monthlyCursor.plusMonths(1);
            sleep(100);
        }

        // YearTopApps (매년 1일 부터 그 연도에 출시된 인기게임 집계 (UTC 기준))
        LocalDate yearlyCursor = props.dashboard().minCollectionDate().withDayOfYear(1);
        while (yearlyCursor.getYear() < LocalDate.now().getYear()) {
            processYearlyCollection(toEpoch(yearlyCursor));
            yearlyCursor = yearlyCursor.plusYears(1);
            sleep(100);
        }

        // 타입별 최신 데이터로 Redis 캐시 갱신
        for (CurationType type : CurationType.values()) {
            dashboardService.refreshCache(type);
        }

        log.info("Historical Data Backfill Completed!");
    }

    /**
     * 실시간 차트 업데이트 (ConcurrentPlayersTopApp / MostPlayedApp)
     * ConcurrentPlayersTopApp : 15분 주기
     * MostPlayedApp : 1시간 주기
     */
    public void syncRealTimeData() {
        // 동시 접속자수 최다 게임
        syncConcurrentPlayers();
        // 최다 플레이 게임 (24h)
        syncMostPlayed();

        log.info("Realtime Dashboard Data Synced.");
    }

    /**
     * 주간 차트 정기 업데이트
     *  - 스팀 갱신 : 매주 화요일 01:00 PST
     *  - 갱신 주기 : 매주 화요일 19:00 KST (수요일 새벽?)
     */
    public void syncScheduledWeekly() {
        LocalDate lastMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.TUESDAY));
        processWeeklyCollection(toEpoch(lastMonday), 1, props.dashboard().pageCount());
        dashboardService.refreshCache(CurationType.WEEKLY_TOP_SELLER);
        log.info("Weekly Top Sellers Dashboard Data Synced.");
    }

    /**
     * 월간 차트 정기 업데이트 (매월 15일)
     *  - 스팀 갱신 : 매월 15일 10:00 PST
     *  - 갱신 주기 : 매월 16일 12:00 KST */

    public void syncScheduledMonthly() {
        LocalDate lastMonthFirstDay = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        processMonthlyCollection(toEpoch(lastMonthFirstDay));
        dashboardService.refreshCache(CurationType.MONTHLY_TOP);
        log.info("Monthly Top Apps Dashboard Data Synced.");
    }

    /**
     * 연간 차트 정기 업데이트
     * - 스팀 갱신: 보통 연초 (1월 초중순)
     * - 갱신 주기: 매년 1월 15일 ~ 20일
     */
    public void syncScheduledYearly() {
        // 작년 1월 1일 (API 요청 파라미터)
        LocalDate lastYearFirstDay = LocalDate.now().minusYears(1).withDayOfYear(1);
        processYearlyCollection(TimestampUtils.toEpoch(lastYearFirstDay));
        dashboardService.refreshCache(CurationType.YEARLY_TOP);
        log.info("Yearly Top Apps Dashboard Data Synced.");

    }

    /**
     * [15분 주기] 현재 동시 접속자 수 Top
     */
    public void syncConcurrentPlayers() {
        try {
            SteamDashboardResponse concurrent = collector.collectConcurrentPlayersTopApp(props.dashboard().countryCode());
            saveDashboard(CurationType.CONCURRENT_PLAYER, concurrent);

            //Redis 캐시 갱신
            dashboardService.refreshCache(CurationType.CONCURRENT_PLAYER);
            log.info("Concurrent Players Synced. (Updated: {} games)", concurrent.ranks().size());
        } catch (RestClientException e) {
            log.warn("Concurrent Players API Failed: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Concurrent Players Sync Failed", e);
        }
    }

    /**
     * [1시간 주기] 24시간 내 최다 플레이 게임
     */
    public void syncMostPlayed() {
        try {
            SteamDashboardResponse mostPlayed = collector.collectMostPlayedApp(props.dashboard().countryCode());
            saveDashboard(CurationType.MOST_PLAYED, mostPlayed);

            dashboardService.refreshCache(CurationType.MOST_PLAYED);

            log.info("Most Played Synced. (Updated: {} games)", mostPlayed.ranks().size());
        } catch (RestClientException e) {
            log.warn("Most Played API Failed: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Most Played Sync Failed", e);
        }
    }

    /**
     * 주간 인기 게임 중복 체크 & 데이터 수집 메서드
     * @param startDate 집계 시작일
     * @param pageStart 페이지 내 조회 시작 위치
     * @param pageCount 페이지 내 조회할 게임 수
     * */
    private void processWeeklyCollection(Long startDate, Integer pageStart, Integer pageCount) {
        try {
            LocalDateTime referenceAt = TimestampUtils.toLocalDateTime(startDate);
            if (dashboardRepository.existsByCurationTypeAndReferenceAt(CurationType.WEEKLY_TOP_SELLER, referenceAt)) {
                return;
            }
            SteamDashboardResponse response = collector.collectWeeklyTopSellers(props.dashboard().countryCode(), startDate, pageStart, props.dashboard().pageCount());
            saveDashboard(CurationType.WEEKLY_TOP_SELLER, response);
        } catch (RestClientException e) {
            log.warn("Weekly Top Sellers Data Fetch Failed (Date: {}): {}", startDate, e.getMessage());
        } catch (Exception e) {
            log.error("Weekly Top Sellers Data Sync Error (Date: {})", startDate, e);
        }
    }

    /**
     * 월간 인기 게임 중복 체크 & 데이터 수집 메서드
     * @param rtimeMonth 기준 일자 (Timestamp)
     * */
    private void processMonthlyCollection(Long rtimeMonth) {
        try {
            LocalDateTime referenceAt = TimestampUtils.toLocalDateTime(rtimeMonth);
            if (dashboardRepository.existsByCurationTypeAndReferenceAt(CurationType.MONTHLY_TOP, referenceAt)) {
                return;
            }
            SteamDashboardResponse response = collector.collectMonthTopApp(rtimeMonth);
            saveDashboard(CurationType.MONTHLY_TOP, response);
        } catch (RestClientException e) {
            log.warn("Monthly Top App Data Fetch Failed (Date: {}): {}", rtimeMonth, e.getMessage());
        } catch (Exception e) {
            log.error("Monthly Top App Data Sync Error (Date: {})", rtimeMonth, e);
        }
    }

    /**
     * 연간 인기 게임 중복 체크 & 데이터 수집 메서드
     * @param rtimeYear 기준 일자 (Timestamp)
     * */
    private void processYearlyCollection(Long rtimeYear) {
        try {
            LocalDateTime referenceAt = TimestampUtils.toLocalDateTime(rtimeYear);
            if (dashboardRepository.existsByCurationTypeAndReferenceAt(CurationType.YEARLY_TOP, referenceAt)) {
                return;
            }
            SteamDashboardResponse response = collector.collectYearTopApp(rtimeYear);
            saveDashboard(CurationType.YEARLY_TOP, response);
        } catch (RestClientException e) {
            log.warn("Yearly Data Fetch Failed (Date: {}): {}", rtimeYear, e.getMessage());
        } catch (Exception e) {
            log.error("Yearly Data Sync Error (Date: {})", rtimeYear, e);
        }
    }

    /**
     * 대시보드 엔티티 저장 메서드
     * */
    private void saveDashboard(CurationType type, SteamDashboardResponse response) {
        if (response == null || response.ranks().isEmpty()) return;

        try {
            // TransactionTemplate 안에서 원자적으로 실행
            transactionTemplate.executeWithoutResult( status -> {

                // 동일 큐레이션 타입과 동일한 수집 시각을 가진 기존 데이터 삭제 후 삽입
                dashboardRepository.deleteByCurationTypeAndReferenceAt(type, response.referenceAt());

                // 1. 수집된 AppID 리스트 추출 (String 변환)
                List<String> appIds = response.ranks().stream()
                        .map(rank -> String.valueOf(rank.appId()))
                        .toList();

                // 2. DB에서 해당 AppID를 가진 Game 조회 (Bulk 조회 - QueryDSL 활용)
                List<StoreDetail> details = storeDetailRepository.findByStoreNameAndStoreAppIdIn(
                        StoreDetail.StoreName.STEAM,
                        appIds
                );

                // 3. Map으로 변환 (AppId(Long) -> Game Entity)
                Map<Long, Game> gameMap = details.stream()
                        .collect(Collectors.toMap(
                                detail -> Long.parseLong(detail.getStoreAppId()),
                                StoreDetail::getGame,
                                (existing, replacement) -> existing // 혹시 모를 중복 방지
                        ));

                // 4. 엔티티 생성
                List<Dashboard> dashboards = new ArrayList<>();
                int missingGames = 0;

                for (SteamDashboardResponse.Rank rank : response.ranks()) {
                    Game game = gameMap.get(rank.appId());

                    if (game == null) {
                        // 우리 DB에 없는 게임은 랭킹에서 제외 (혹은 추후 수집 대상으로 로깅)
                        missingGames++;
                        continue;
                    }

                    Dashboard dashboard = Dashboard.builder()
                            .game(game)                   // FK 매핑
                            .curationType(type)
                            .rank(rank.rank())
                            .referenceAt(response.referenceAt()) // 기준 시각
                            .updatedAt(LocalDateTime.now())     // 저장 시각
                            .build();

                    dashboards.add(dashboard);
                }

                // 5. Bulk Insert
                if (!dashboards.isEmpty()) {
                    dashboardRepository.saveAll(dashboards);
                }

                log.debug("Saved {} dashboards for type [{}]. (Missing games: {})", dashboards.size(), type, missingGames);
            });
        } catch (DataAccessException e) {
            // 트랜잭션 롤백 후 여기서 잡힘
            log.error("DB Save Failed for {}: {}", type, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected Error saving {}: ", type, e);
        }
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
