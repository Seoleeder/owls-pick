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

    private final AsyncTaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;

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
     * 비동기 스레드 처리 후 적재 결과 데이터를 반환하는 내부 DTO.
     */
    private record ReviewBatchResult(int validGames, int savedReviews) {}

    /**
     * 스팀 리뷰 데이터 정기 업데이트 메서드
     * 갱신일이 오래된 게임들을 조회하고 최신 내역으로 업데이트.
     * */
    public void syncReviews(){

        // 리뷰 갱신이 필요한 스팀 게임 ID 조회
        List<StoreDetail> targetGames = storeDetailRepository.findGamesNeedingReviewUpdate(StoreDetail.StoreName.STEAM, steamProps.review().maintenanceBatchSize());

        if (targetGames.isEmpty()) {
            log.debug("Review Sync: All games are up to date. Skipping batch.");
            return;
        }

        log.info("Scheduled Sync: Processing {} games in Parallel...", targetGames.size());

        // 대상 게임 목록 비동기 병렬 처리
        processBatchAsync(targetGames);

        log.info("Scheduled Sync Finished. Processed {} games.", targetGames.size());
    }

    /**
     * 스팀 리뷰 데이터 초기 대량 수집 메서드
     * */
    public void initAllReviews(){
        log.info("Starting Bulk Initialization...");
        int totalProcessedGames = 0;
        int totalValidGames = 0;
        int totalSavedReviews = 0;

        while (true) {
            // ReviewStat 미보유 게임의 스팀 앱 ID 조회
            List<StoreDetail> targetGames = storeDetailRepository.findGamesWithNoReviews(
                    StoreDetail.StoreName.STEAM,
                    steamProps.review().initBatchSize()
            );

            if (targetGames.isEmpty()) {
                log.info("Initialization Complete! Processed Games: {}, Valid Games: {}, Total Reviews: {}",
                        totalProcessedGames, totalValidGames, totalSavedReviews);
                break;
            }

            // 배치 병렬 처리 수행 후 유효 게임 수와 저장된 리뷰 수 반환
            ReviewBatchResult batchResult = processBatchAsync(targetGames);

            totalProcessedGames += targetGames.size();
            totalValidGames += batchResult.validGames();
            totalSavedReviews += batchResult.savedReviews();

            // 1,000개 게임 조회 단위로 누적 처리량과 평균 적재량 로깅
            if (totalProcessedGames % 1000 == 0) {
                int avgReviews = totalValidGames > 0 ? (totalSavedReviews / totalValidGames) : 0;
                log.info("[Steam Review Bulk Progress] Processed: {} games. Valid: {}, Reviews: {} (Avg: {}/valid game)",
                        totalProcessedGames, totalValidGames, totalSavedReviews, avgReviews);
            }
        }
    }

    /**
     * 대상 게임 리스트의 리뷰 수집 및 영속화 작업을 비동기 병렬 처리하는 메서드.
     */
    private ReviewBatchResult processBatchAsync(List<StoreDetail> targetGames) {
        AtomicInteger validGamesCount = new AtomicInteger(0);
        AtomicInteger totalSavedReviews = new AtomicInteger(0);

        // 리뷰 수집 대상 게임의 개별 비동기 스레드 생성
        List<CompletableFuture<Void>> futures = targetGames.stream()
                .map(detail -> CompletableFuture.runAsync(() -> {
                    // 리뷰 수집 및 저장 후, 새로 저장된 리뷰 수 반환
                    int savedCount = processGameReviewSyncSafe(detail);

                    // 유효 저장 발생 시 집계 데이터 반영
                    if (savedCount > 0) {
                        validGamesCount.incrementAndGet();
                        totalSavedReviews.addAndGet(savedCount);
                    }

                    // 실시간 진행 상황 로깅
                    log.debug("[Steam Review] Game {} processed. (New reviews saved: {})",
                            detail.getGame().getTitle(), savedCount);
                }, taskExecutor))
                .toList();

        // 파생된 전체 비동기 스레드 작업 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 수집 결과 DTO 반환
        return new ReviewBatchResult(validGamesCount.get(), totalSavedReviews.get());
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

            // 내부 트랜잭션 생성 후 스팀 리뷰 데이터 저장
            Integer savedCount = transactionTemplate.execute(status -> {
                // Game 엔티티 프록시 참조 생성
                Game managedGame = gameRepository.getReferenceById(detail.getGame().getId());
                return saveReviewData(managedGame, response);
            });

            // 트랜잭션 정상 종료 시 건수 로깅
            int finalCount = savedCount != null ? savedCount : 0;
            log.debug("Persisted {} new reviews for game: {}", finalCount, gameTitle);

            return finalCount;

        } catch (DataAccessException e) {
            // 트랜잭션 롤백 및 예외 로깅 처리
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
        // 리뷰 통계 엔티티 상태 갱신
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

    /** 스팀 리뷰 통계 데이터 Upsert 메서드
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
        // 해당 게임의 기존 리뷰 ID 목록 조회
        Set<Long> existingIds = reviewRepository.findRecommendationIdsByGameId(game.getId());

        List<Review> reviewsToSave = new ArrayList<>();

        for (SteamReviewDetail reviewDetail : reviews) {
            // 중복 데이터 제외 처리
            if (existingIds.contains(reviewDetail.recommendationId())) {
                continue;
            }

            // 문자열 제약 위반 방지용 Null Byte 치환
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
}
