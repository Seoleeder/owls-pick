package io.github.seoleeder.owls_pick.controller.admin;

import io.github.seoleeder.owls_pick.global.response.CommonResponse;
import io.github.seoleeder.owls_pick.service.client.hltb.HltbSyncService;
import io.github.seoleeder.owls_pick.service.client.igdb.IgdbSyncService;
import io.github.seoleeder.owls_pick.service.client.itad.ItadSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamAppSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamDashboardSyncService;
import io.github.seoleeder.owls_pick.service.client.steam.SteamReviewSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Tag(name = "[ADMIN] 게임 데이터 초기화", description = "게임 데이터 초기 구축 및 수동 제어 (Required Header 'X-ADMIN-KEY')")
@RestController
@RequestMapping("/admin/init")
@RequiredArgsConstructor
public class GameDataInitializer {
    private final SteamAppSyncService steamAppService;
    private final SteamDashboardSyncService steamDashboardService;
    private final SteamReviewSyncService steamReviewService;
    private final IgdbSyncService igdbService;
    private final ItadSyncService itadService;
    private final HltbSyncService hltbSyncService;


    @Operation(summary = "Steam 앱 리스트 초기화",
                description = "Steam에 등록된 앱의 ID, 타이틀 수집",
                parameters = {
                    @Parameter(name = "X-ADMIN-KEY", description = "관리자 키", required = true, in = ParameterIn.HEADER)
                })
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Steam 앱 리스트 수집 백그라운드 작업 시작 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": "Steam App List Sync Started",
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "관리자 인증 실패 (X-ADMIN-KEY 누락 또는 불일치)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": 40100,
                                        "message": "권한이 없습니다."
                                      }
                                    }
                                    """)))
    })
    @PostMapping("/steam-app-list")
    public CommonResponse<String> initSteamAppList(){

        log.info("[Admin] Steam App List Sync Requested");

        //TimeOut 방지용 비동기 호출 전략
        CompletableFuture.runAsync(() -> {
            try {
                steamAppService.syncAppList();
                log.info("[Admin] Steam App List Sync Completed!");
            }catch (Exception e){
                log.error("[Admin] Steam App List Failed", e);
            }
        });

        return CommonResponse.ok("Steam App List Sync Started");
    }

    @Operation( summary = "Steam 게임 리뷰 데이터 초기화",
            description = "스팀 리뷰 통계 데이터 수집 -> 적응형 샘플링 및 필터링으로 유용함 지수가 높은 리뷰 데이터 수집",
            parameters = {
                    @Parameter(name = "X-ADMIN-KEY", description = "관리자 키", required = true, in = ParameterIn.HEADER)
            }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Steam 리뷰 데이터 수집 백그라운드 작업 시작 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": "Steam App Review Sync Started",
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "관리자 인증 실패",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": 40100,
                                        "message": "권한이 없습니다."
                                      }
                                    }
                                    """)))
    })
    @PostMapping("/reviews")
    public CommonResponse<String> initReviews(){
        log.info("[Admin] Steam App Review Sync Requested");

        CompletableFuture.runAsync(() -> {
            try {
                steamReviewService.initAllReviews();
                log.info("[Admin] Steam App Review Sync Completed!");

            } catch (Exception e) {
                log.error("[Admin] Steam App Review Sync Failed", e);
            }
        });

        return CommonResponse.ok("Steam App Review Sync Started");
    }

    @Operation(summary = "Steam 대시보드 데이터 초기화",
            description = "기간별 (주간, 월간, 연간) 인기 차트 데이터 수집 & 실시간 차트 데이터 (최다 플레이, 최다 동접자) 수집",
            parameters = {
                    @Parameter(name = "X-ADMIN-KEY", description = "관리자 키", required = true, in = ParameterIn.HEADER)
            }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Steam 대시보드 데이터 수집 백그라운드 작업 시작 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": "Steam Dashboard Sync Started",
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "관리자 인증 실패",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": 40100,
                                        "message": "권한이 없습니다."
                                      }
                                    }
                                    """)))
    })
    @PostMapping("/dashboard")
    public CommonResponse<String> initDashboard() {
        log.info("[Admin] Steam Chart Dashboard Sync Requested.");

        CompletableFuture.runAsync(() -> {
            try {
                // 수집 기준 시각 이후의 모든 주간, 월간, 연간 인기 차트 수집
                log.info("[Admin] Steam Weekly, Monthly, Yearly Dashboard Sync");
                steamDashboardService.syncHistoricalDashboards();

                // 현재 시각 기준 실시간 차트 데이터 (일일 최다 플레이 게임, 실시간 최다 동접자 수) 수집
                log.info("[Admin] Steam RealTime Dashboard Sync");
                steamDashboardService.syncRealTimeData();

                log.info("[Admin] Steam Dashboard Sync Completed!");
            } catch (Exception e) {
                log.error("[Admin] Steam Dashboard Sync Failed", e);
            }
        });

        return CommonResponse.ok("Steam Dashboard Sync Started");
    }

    @Operation(summary = "Igdb 메타데이터 초기화",
            description = "게임 주요 데이터 (출시 상태, 심의, 플랫폼, 설명, 태그, 언어 지원 등)",
            parameters = {
                @Parameter(name = "X-ADMIN-KEY", description = "관리자 키", required = true, in = ParameterIn.HEADER)
            }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "IGDB 메타데이터 수집 백그라운드 작업 시작 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": "IGDB Sync Started",
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "관리자 인증 실패",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CommonResponse.class)))
    })
    @PostMapping("/igdb")
    public CommonResponse<String> initIgdb() {
        log.info("[Admin] IGDB Sync Requested.");

        CompletableFuture.runAsync(() -> {
            try {
                igdbService.backfillAllGames();
                log.info("[Admin] IGDB Sync Completed!");
            } catch (Exception e) {
                log.error("[Admin] IGDB Sync Failed", e);
            }
        });

        return CommonResponse.ok("IGDB Sync Started");
    }

    @Operation(summary = "ITAD 데이터 초기화",
            description = "ITAD ID 수집 -> 스토어 별 가격 데이터 초기화",
            parameters = {
                    @Parameter(name = "X-ADMIN-KEY", description = "관리자 키", required = true, in = ParameterIn.HEADER)
            }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "ITAD 가격 데이터 수집 백그라운드 작업 시작 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": "ITAD Sync Started",
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "관리자 인증 실패",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": 40100,
                                        "message": "권한이 없습니다."
                                      }
                                    }
                                    """)))
    })
    @PostMapping("/itad")
    public CommonResponse<String> initItad() {
        log.info("[Admin] ITAD Sync Requested.");

        CompletableFuture.runAsync(() -> {
            try {
                // ITAD ID 저장
                log.info("[Admin] ITAD ID Sync");
                itadService.syncMissingItadIds();
                // ITAD 가격 정보 수집
                log.info("[Admin] ITAD Price Sync");
                itadService.syncPrices();

                log.info("[Admin] ITAD Sync Completed!");
            } catch (Exception e) {
                log.error("[Admin] ITAD Sync Failed", e);
            }
        });

        return CommonResponse.ok("ITAD Sync Started");
    }

    @Operation(summary = "HLTB 플레이타임 데이터 초기화", description = "FastAPI 서버를 통한 HowLongToBeat 플레이타임 데이터 동기화")
    @PostMapping("/hltb")
    public CommonResponse<String> initHltb() {
        log.info("[Admin] HLTB Sync Requested.");
        CompletableFuture.runAsync(() -> {
            try {
                hltbSyncService.runSyncPipeline();
                log.info("[Admin] HLTB Sync Completed!");
            } catch (Exception e) {
                log.error("[Admin] HLTB Sync Failed", e);
            }
        });
        return CommonResponse.ok("HLTB Sync Started");
    }

    @Operation(summary = "전체 게임 데이터 구축",
            description = "스팀 앱 리스트 수집  -> IGDB, ITAD, 스팀 리뷰 및 대시보드 데이터 병렬 수집",
            parameters = {
                    @Parameter(name = "X-ADMIN-KEY", description = "관리자 키", required = true, in = ParameterIn.HEADER)
            }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "전체 게임 데이터 병렬 수집 백그라운드 작업 시작 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": "Game Data Initialization Started in Background",
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "관리자 인증 실패",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": 40100,
                                        "message": "권한이 없습니다."
                                      }
                                    }
                                    """)))
    })
    @PostMapping("/init-all")
    public CommonResponse<String> initAllGameData(){
        log.info("[Admin] All Game Data Initialization Request Received.");

        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {

                // 1. Steam App List (Blocking)
                log.info("Steam App List Sync Started");
                steamAppService.syncAppList();
                log.info("Completed!");

                // 2. Parallel Execution
                // IGDB 데이터 동기화
                CompletableFuture<Void> futureIgdb = CompletableFuture.runAsync(() -> {
                    log.info("IGDB Sync Started");
                    igdbService.backfillAllGames();
                    log.info("IGDB Sync Finished");
                });

                // ITAD 데이터 초기화 (ID 수집 -> 가격 정보 수집)
                CompletableFuture<Void> futureItad = CompletableFuture.runAsync(() -> {
                    log.info("ITAD ID Sync Started");
                    itadService.syncMissingItadIds();

                    log.info("ITAD Price Sync Started");
                    itadService.syncPrices();

                    log.info("ITAD ID & Price Sync Finished");
                });

                // 스팀 대시보드 데이터 초기화
                CompletableFuture<Void> futureDashboard = CompletableFuture.runAsync(() -> {
                    log.info("Steam Weekly, Monthly, Yearly Dashboard Sync Started");
                    steamDashboardService.syncHistoricalDashboards();

                    log.info("Steam RealTime Dashboard Sync Started");
                    steamDashboardService.syncRealTimeData();

                    log.info("Steam Dashboard Sync Finished");
                });

                // 스팀 리뷰 데이터 초기화
                CompletableFuture<Void> futureReview = CompletableFuture.runAsync(() -> {
                    log.info("Steam Review Sync Started");
                    steamReviewService.initAllReviews();
                    log.info("Steam Review Sync Finished");
                });

                // 모든 병렬 수집 작업이 다 완료될 때까지 대기
                CompletableFuture.allOf(futureIgdb, futureItad, futureDashboard, futureReview).join();

                long endTime = System.currentTimeMillis();
                log.info("[Admin] All Initialization Finished! (Duration: {}ms)", (endTime - startTime));

            } catch (Exception e) {
                log.error("[Admin] Critical Error", e);
            }
        });

        return CommonResponse.ok("Game Data Initialization Started in Background");
    }
}
