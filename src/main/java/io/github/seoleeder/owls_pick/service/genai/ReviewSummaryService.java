package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.dto.request.ReviewSummaryRequest;
import io.github.seoleeder.owls_pick.dto.response.ReviewSummaryResponse;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.ReviewRepository;
import io.github.seoleeder.owls_pick.repository.ReviewStatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;


/**
 * 스팀 리뷰 요약 및 키워드 추출 파이프라인 서비스
 */
@Slf4j
@Service
public class ReviewSummaryService {

    private final ReviewStatRepository reviewStatRepository;
    private final ReviewRepository reviewRepository;
    private final RestClient restClient;
    private final AsyncTaskExecutor taskExecutor; // Trace ID 전파 및 가상 스레드 처리를 지원하는 스프링 메인 실행기
    private final GenaiProperties props;


    // 총 리뷰 수가 임계치 넘지만 실제 리뷰는 없는 게임 스킵 마킹 상수
    public static final String INSUFFICIENT_DATA_FLAG = "INSUFFICIENT_REVIEW_DATA";

    // 리뷰 요약에 실패한 게임 스킵 마킹 상수
    public static final String SUMMARY_FAILED_FLAG = "SUMMARY_GENERATION_FAILED";

    public ReviewSummaryService(
            ReviewStatRepository reviewStatRepository,
            ReviewRepository reviewRepository,
            @Qualifier("genaiRestClient") RestClient restClient,
            // 분산 추적(Trace ID) 설정이 내장된 실행기 지정 주입
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
            GenaiProperties props) {
        this.reviewStatRepository = reviewStatRepository;
        this.reviewRepository = reviewRepository;
        this.restClient = restClient;
        this.taskExecutor = taskExecutor;
        this.props = props;

    }

    /**
     * 환경 변수에 설정된 기본 배치 사이즈로 리뷰 요약 파이프라인 실행
     */
    public void runPipeline() {
        runPipeline(props.review().batchSize());
    }

    /**
     * 지정된 배치 사이즈 단위로 리뷰 요약 파이프라인 무한 루프 실행
     */
    public void runPipeline(int batchSize) {
        log.info("[GenAI] Starting Review Summary Pipeline with batch size {}...", batchSize);
        int totalProcessed = 0;

        while (true) {
            int processedCount = processSingleBatch(batchSize);

            if (processedCount == 0) {
                break; // 처리할 대상이 없으면 루프 탈출
            }
            totalProcessed += processedCount;

            try {
                // API 부하 방지를 위한 2초 딜레이
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[GenAI] Review Summary Pipeline sleep interrupted", e);
                break;
            }
        }

        log.info("[GenAI] Review Summary Pipeline Finished. Total summarized games: {}", totalProcessed);
    }

    /**
     * 단일 배치 리뷰 요약 파이프라인 실행
     */
    public int processSingleBatch(int batchSize) {
        int minThreshold = props.review().minThreshold();

        // 리뷰 수가 임계치 이상이면서 리뷰가 요약되지 않은 게임 조회
        List<ReviewStat> targets = reviewStatRepository.findTargetsWithoutSummary(minThreshold, batchSize);

        if (targets.isEmpty()) {
            return 0;
        }

        log.info("[GenAI] Processing a single batch. Target count: {}", targets.size());

        // 리뷰 요약 메서드 비동기 병렬 실행
        // taskExecutor: 부모 스레드의 MDC 상태(Trace ID)를 신규 스레드로 자동 복사
        List<CompletableFuture<Void>> futures = targets.stream()
                .map(stat -> CompletableFuture.runAsync(() -> {
                    try {
                        processSingleGameSummary(stat);
                    } catch (Exception e) {
                        // 단일 게임 처리 실패 시 고립 (전체 배치 영향 X)
                        log.error("[GenAI] Unexpected error for Game ID: {}. Skipping.", stat.getGame().getId(), e);
                    }
                }, taskExecutor))
                .toList();

        // 현재 배치의 모든 비동기 작업이 완료될 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return targets.size();
    }

    /**
     * 단일 게임의 리뷰 요약 실행 (조회 -> FastAPI 통신 -> DB 업데이트)
     */
    private void processSingleGameSummary(ReviewStat stat) {
        Long gameId = stat.getGame().getId();

        List<String> reviewTexts = extractReviewTexts(gameId);

        // 유효한 리뷰 텍스트가 없는 경우 스킵 마킹 처리 후 즉시 반환
        if (reviewTexts.isEmpty()) {
            markAsInsufficientData(stat);
            return;
        }

        ReviewSummaryResponse response = requestSummaryToFastApi(gameId, stat, reviewTexts);

        if (isValidResponse(response)) {
            // FastAPI가 반환한 텍스트가 실패 플래그인지 확인
            if (SUMMARY_FAILED_FLAG.equals(response.summaryText())) {
                markAsSummaryFailed(stat);
            } else {
                // 정상적인 요약 텍스트라면 기존대로 업데이트
                applySummaryResult(stat, response);
            }
        }
    }

    /**
     * 특정 게임의 리뷰 텍스트 리스트 조회
     */
    private List<String> extractReviewTexts(Long gameId) {
        List<String> reviewTexts = reviewRepository.findReviewTextsByGameId(gameId);
        if (reviewTexts == null || reviewTexts.isEmpty()) {
            log.warn("[GenAI] No reviews found for Game ID: {}. Skipping.", gameId);
            return List.of();
        }
        return reviewTexts;
    }

    /**
     * FastAPI 서버로 리뷰 데이터 전송 및 Gemini 리뷰 요약 결과 반환
     */
    private ReviewSummaryResponse requestSummaryToFastApi(Long gameId, ReviewStat stat, List<String> reviewTexts) {
        ReviewSummaryRequest requestDto = new ReviewSummaryRequest(
                gameId,
                stat.getReviewScore(),
                reviewTexts
        );
        URI targetUri = UriComponentsBuilder.fromUriString(props.fastapiUrl())
                .path("/api/genai/summarize/reviews")
                .build()
                .toUri();

        try {
            return restClient.post()
                    .uri(targetUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestDto)
                    .retrieve()
                    .body(ReviewSummaryResponse.class);
        } catch (RestClientException e) {
            log.error("[GenAI] Communication Error with GenAI Server for Game ID: {}", gameId);
            throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
        }
    }

    /**
     * FastAPI 응답 데이터의 유효성 검증
     */
    private boolean isValidResponse(ReviewSummaryResponse response) {
        if (response == null || response.summaryText() == null || response.summaryText().isBlank()) {
            log.warn("[GenAI] Received empty or invalid summary response.");
            return false;
        }
        return true;
    }

    /**
     * 스팀 리뷰 요약 결과 및 추출된 긍정/부정 키워드 업데이트
     */
    public void applySummaryResult(ReviewStat stat, ReviewSummaryResponse response) {
        stat.updateReviewSummary(
                response.summaryText(),
                response.positiveKeywords(),
                response.negativeKeywords()
        );

        reviewStatRepository.save(stat);
        log.info("[GenAI] Successfully updated summary and keywords for Game ID: {}", stat.getGame().getId());
    }

    /**
     * 임계치 필터링을 통과했으나, 리뷰 데이터가 없는 게임 스킵 마킹 (무한 재조회 방지)
     */
    public void markAsInsufficientData(ReviewStat stat) {
        // 텍스트 필드에만 플래그 넣고, 키워드 배열은 null 처리
        stat.updateReviewSummary(INSUFFICIENT_DATA_FLAG, null, null);
        reviewStatRepository.save(stat);
        log.info("[GenAI] Game ID: {} marked as INSUFFICIENT_DATA to prevent infinite requerying.", stat.getGame().getId());
    }

    /**
     * 구글 안전 필터 차단 등의 문제로 요약에 실패한 게임 스킵 마킹 (무한 재조회 방지)
     */
    public void markAsSummaryFailed(ReviewStat stat) {
        stat.updateReviewSummary(SUMMARY_FAILED_FLAG, null, null);
        reviewStatRepository.save(stat);
        log.info("[GenAI] Game ID: {} marked as SUMMARY_FAILED to prevent infinite requerying.", stat.getGame().getId());
    }
}
