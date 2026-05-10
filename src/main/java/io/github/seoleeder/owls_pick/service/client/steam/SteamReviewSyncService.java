package io.github.seoleeder.owls_pick.service.client.steam;

import io.github.seoleeder.owls_pick.client.steam.SteamDataCollector;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewDetailResponse.SteamReviewDetail;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewStatsResponse.SteamReviewStats;
import io.github.seoleeder.owls_pick.global.config.properties.CurationProperties;
import io.github.seoleeder.owls_pick.global.config.properties.SteamProperties;
import io.github.seoleeder.owls_pick.global.util.TimestampUtils;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.Review;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.ReviewRepository;
import io.github.seoleeder.owls_pick.repository.ReviewStatRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class SteamReviewSyncService {
    private final SteamDataCollector collector;
    private final StoreDetailRepository storeDetailRepository;
    private final ReviewStatRepository reviewStatRepository;
    private final ReviewRepository reviewRepository;
    private final GameRepository gameRepository;

    private final AsyncTaskExecutor taskExecutor;   // Trace ID 전파용 실행기
    private final TransactionTemplate transactionTemplate; // 트랜잭션 수동 제어용

    private final SteamProperties steamProps;
    private final CurationProperties curationProps;

    public SteamReviewSyncService(
            SteamDataCollector collector,
            StoreDetailRepository storeDetailRepository,
            ReviewStatRepository reviewStatRepository,
            ReviewRepository reviewRepository,
            GameRepository gameRepository,
            // applicationTaskExecutor : Trace ID 전파와 가상 스레드 처리를 담당하는 스프링 기본 실행기
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
            TransactionTemplate transactionTemplate,
            SteamProperties steamProps,
            CurationProperties curationProps)
    {
        this.collector = collector;
        this.storeDetailRepository = storeDetailRepository;
        this.reviewStatRepository = reviewStatRepository;
        this.reviewRepository = reviewRepository;
        this.gameRepository = gameRepository;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.steamProps = steamProps;
        this.curationProps = curationProps;
    }

    /**
     * 스팀 리뷰 데이터 정기 업데이트 메서드
     * 이미 수집된 게임들 중, 리뷰 갱신이 오래된 순서대로 업데이트
     * */
    public void syncReviews(){

        //리뷰 갱신이 필요한 스팀 게임 ID 조회
        List<StoreDetail> targetGames = storeDetailRepository.findGamesNeedingReviewUpdate(StoreDetail.StoreName.STEAM, steamProps.review().maintenanceBatchSize());

        if (targetGames.isEmpty()) {
            log.debug("Review Sync: All games are up to date. Skipping batch.");
            return;
        }

        log.info("Scheduled Sync: Processing {} games in Parallel...", targetGames.size());

        // 대상 게임들에 대한 병렬 작업 수행
        processBatchAsync(targetGames);

        log.info("Scheduled Sync Finished. Processed {} games.", targetGames.size());
    }

    /**
     * 게임 리뷰 데이터 초기 대량 수집 메서드
     * 1. 리뷰 통계 데이터가 존재하지 않는 스팀 ID 조회
     * 2. 스레드 내에서 각각의 스팀 ID에 대해서 비동기 작업 수행
     * 3. 배치 작업이 전부 끝날때까지 Blocking
     * */
    public void initAllReviews(){
        log.info("Starting Bulk Initialization...");
        int totalProcessed = 0;

        while (true) {
            // ReviewStat이 존재하지 않는 스팀 게임 ID 조회
            List<StoreDetail> targetGames = storeDetailRepository.findGamesWithNoReviews(
                    StoreDetail.StoreName.STEAM,
                    steamProps.review().initBatchSize()
            );

            if (targetGames.isEmpty()) {
                log.info("Initialization Complete! Total: {}", totalProcessed);
                break;
            }

            processBatchAsync(targetGames);

            totalProcessed += targetGames.size();
            log.info("Bulk Progress: {} games processed...", totalProcessed);

        }
    }

    /**
     * 특정 게임에 대한 리뷰 수집 -> 내부의 독립적인 트랜잭션에서 저장
     * @param detail 리뷰 작업을 수행할 스팀 ID를 담은 스토어 상세 정보 객체
     * */
    private int processGameReviewSyncSafe(StoreDetail detail){
        String gameTitle = detail.getGame().getTitle();
        String appIdStr = detail.getStoreAppId();

        try {
            Long appId = Long.parseLong(appIdStr);

            // 게임의 리뷰 통계와 최소 유용함 수가 일정 수치 이상인 리뷰들 수집
            SteamReviewResponse response = collector.collectRefinedReviews(appId);

            if (response == null) {
                log.debug("No reviews found from Steam API for game: {}", gameTitle);
                return 0;
            }

            // TransactionTemplate로 내부 트랜잭션 생성 후 리뷰 데이터 저장
            Integer savedCount = transactionTemplate.execute(status -> {
                // 현재 트랜잭션의 영속성 컨텍스트에서 관리되는 Game 엔티티(Proxy) 확보
                Game managedGame = gameRepository.getReferenceById(detail.getGame().getId());
                return saveReviewData(managedGame, response);
            });

            // 트랜잭션 정상 종료 시 건수 로깅
            int finalCount = savedCount != null ? savedCount : 0;
            log.debug("Persisted {} new reviews for game: {}", finalCount, gameTitle);

            return finalCount;

        } catch (DataAccessException e) {
            // DB 오류: 제약조건 위반, 커넥션 문제 등 -> 에러 로그 남기고 스킵
            log.error("DB Failure for Game: {} ({}) - {}", gameTitle, appIdStr, e.getMessage());

        } catch (Exception e) {
            // 예상치 못한 오류
            log.error("Unexpected Error for Game: {} ({})", gameTitle, appIdStr, e);
        }

        return 0;
    }

    /**
     * 트랜잭션 내부에서 실행되는 실제 저장 로직
     * @param game 리뷰 데이터를 저장할 게임
     * @param response 해당 게임에 대한 리뷰 데이터
     */
    private int saveReviewData(Game game, SteamReviewResponse response) {
        // 리뷰 통계 저장
        saveReviewStat(game, response.stats());

        int savedReviewCount = 0;

        // 리뷰 상세 정보 저장
        if (!response.reviews().isEmpty()) {
            savedReviewCount = saveReviews(game, response.reviews());
        }

        // 현재 시각 기준으로 일주일 전 시각 계산
        int daysRange = curationProps.trending().daysRange();
        LocalDateTime startTime = LocalDateTime.now().minusDays(daysRange);

        // 주간 리뷰 수 갱신
        reviewStatRepository.updateWeeklyReviewCount(game.getId(), startTime);

        return savedReviewCount;
    }

    /** 스팀 리뷰 통계 데이터 저장 메서드
     *  1. 데이터가 존재하면 새로 조회한 데이터로 업데이트
     *  2. 존재하지 않으면 객체 생성 후 저장
     * @param game 리뷰 통계 데이터를 저장하거나 업데이트할 게임
     * @param stats 해당 게임에 대한 리뷰 통계 데이터
     * */
    private void saveReviewStat(Game game, SteamReviewStats stats){
        reviewStatRepository.findById(game.getId())
                .ifPresentOrElse(
                        existingStat -> existingStat.updateStats(
                        stats.reviewScore(),
                        stats.reviewScoreDesc(),
                        stats.totalReview(),
                        stats.totalPositive(),
                        stats.totalNegative()
                        ),
                        () -> {
                            ReviewStat newStat = ReviewStat.builder()
                                    .game(game)
                                    .reviewScore(stats.reviewScore())
                                    .reviewScoreDesc(stats.reviewScoreDesc())
                                    .totalReview(stats.totalReview())
                                    .totalPositive(stats.totalPositive())
                                    .totalNegative(stats.totalNegative())
                                    .build();
                            reviewStatRepository.save(newStat);
                        }
                );
    }

    /**
     *  스팀 리뷰 컬렉터에서 조건에 따라 필터링된 리뷰 저장 메서드
     *  이미 DB에 존재하는 리뷰면 패스, 새로운 ID면 저장
     * @param game 리뷰 상세 데이터를 저장할 게임
     * @param reviews 해당 게임에 대한 리뷰 상세 데이터
     * */
    private int saveReviews(Game game, List<SteamReviewDetail> reviews) {
        // 해당 게임의 기존 리뷰 ID 목록 로드
        Set<Long> existingIds = reviewRepository.findRecommendationIdsByGameId(game.getId());

        List<Review> reviewsToSave = new ArrayList<>();

        for (SteamReviewDetail reviewDetail : reviews) {
            // 중복 리뷰 필터링
            if (existingIds.contains(reviewDetail.recommendationId())) {
                continue;
            }

            // PostgreSQL Null Byte(\u0000) 에러 방지용 문자열 정제
            String safeReviewText = reviewDetail.reviewText() != null ?
                    reviewDetail.reviewText().replace("\u0000", "") : "";

            // 새로운 recommendationId를 가진 리뷰만 저장
            Review newReview = Review.builder()
                    .game(game)
                    .recommendationId(reviewDetail.recommendationId())
                    .reviewText(safeReviewText)
                    .weightedVoteScore(reviewDetail.weightedVoteScore())
                    .playtimeAtReview(reviewDetail.author().playtimeAtReview())
                    .votedUp(reviewDetail.votedUp())
                    .votesUp(reviewDetail.votesUp())
                    .writtenAt(TimestampUtils.toLocalDateTime(reviewDetail.writtenAt()))
                    .build();

            reviewsToSave.add(newReview);
        }

        // 일괄 삽입(Bulk Insert) 실행
        if (!reviewsToSave.isEmpty()) {
            reviewRepository.bulkInsertReviews(reviewsToSave);
        }

        return reviewsToSave.size();
    }

    /**
     * 공통 병렬 처리 로직
     */
    private void processBatchAsync(List<StoreDetail> targetGames) {
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger totalSavedReviews = new AtomicInteger(0);
        int totalTarget = targetGames.size();

        // futures : 비동기 작업의 결과물(작업 완료 상태)들을 모아둘 리스트
        // runAsync : 각각의 스팀 ID에 대해서 비동기 작업 수행
        // taskExecutor: 부모 스레드의 MDC(Trace ID) 상태를 캡처하여 신규 스레드로 전파
        List<CompletableFuture<Void>> futures = targetGames.stream()
                .map(detail -> CompletableFuture.runAsync(() -> {
                    // 리뷰 수집 및 저장 후, 새로 저장된 리뷰 개수 반환
                    int savedCount = processGameReviewSyncSafe(detail);

                    // 스레드 안전하게 카운트 증가
                    int currentCompleted = completedCount.incrementAndGet();
                    totalSavedReviews.addAndGet(savedCount);

                    // 실시간 진행 상황 로깅
                    log.debug("[Steam Review] Game {}/{} processed. (New reviews saved: {})",
                            currentCompleted, totalTarget, savedCount);
                }, taskExecutor))
                .toList();

        // 여러 메모리에 흩어져 있는 각각의 비동기 작업의 상태를 하나로 묶어서 통합 관리
        // toArray(new CompletableFuture[0]) 리스트를 배열로 바꿔서 allOf에 전달
        // allOf() : 모든 작업이 완료 상태가 될때까지 관리하는 통합 Future를 생성
        // join() : 해당 배치 작업이 전부 완료될 때까지 대기 (Blocking), 예외 발생시 RuntimeException 던짐.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("Batch Process Completed. Total new reviews saved across all threads: {}", totalSavedReviews.get());
    }
}
