package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.dto.request.ReviewSummaryRequest;
import io.github.seoleeder.owls_pick.dto.response.ReviewSummaryResponse;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiFailReason;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.GenaiFailedTaskRepository;
import io.github.seoleeder.owls_pick.repository.ReviewRepository;
import io.github.seoleeder.owls_pick.repository.ReviewStatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactionTemplate;
    private final GenaiFailedTaskRepository failedTaskRepository;
    private final GenaiProperties props;

    public ReviewSummaryService(
            ReviewStatRepository reviewStatRepository,
            ReviewRepository reviewRepository,
            @Qualifier("genaiRestClient") RestClient restClient,
            // 분산 추적(Trace ID) 설정이 내장된 실행기 지정 주입
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
            GenaiProperties props,
            TransactionTemplate transactionTemplate,
            GenaiFailedTaskRepository failedTaskRepository) {
        this.reviewStatRepository = reviewStatRepository;
        this.reviewRepository = reviewRepository;
        this.restClient = restClient;
        this.taskExecutor = taskExecutor;
        this.props = props;
        this.transactionTemplate = transactionTemplate;
        this.failedTaskRepository = failedTaskRepository;
    }

    /**
     * 환경 변수에 설정된 기본 배치 사이즈로 파이프라인 실행
     */
    public void runPipeline() {
        runPipeline(props.review().batchSize());
    }

    /**
     * 지정된 배치 사이즈 단위로 파이프라인 연속 실행
     */
    public void runPipeline(int batchSize) {
        log.info("[GenAI] Starting Review Summary Pipeline with batch size {}...", batchSize);
        int totalProcessed = 0;

        while (true) {
            try {
                int processedCount = processSingleBatch(batchSize);

                if (processedCount == 0) {
                    break; // 처리할 대상이 없으면 루프 탈출
                }
                totalProcessed += processedCount;
            } catch (Exception e) {
                // 특정 배치 처리 중 예상치 못한 예외 발생 시 전체 루프 중단 방지
                log.error("[GenAI] Failed to process review summary batch. Skipping to next cycle.", e);
            }

            try {
                // API 부하 방지를 위한 대기 시간 적용
                Thread.sleep(props.review().delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[GenAI] Review Summary Pipeline sleep interrupted", e);
                break;
            }
        }

        log.info("[GenAI] Review Summary Pipeline Finished. Total summarized games: {}", totalProcessed);
    }

    /**
     * 단일 배치에 포함된 리뷰 요약 파이프라인 실행
     */
    public int processSingleBatch(int batchSize) {
        int minThreshold = props.review().minThreshold();

        // 리뷰 수가 임계치 이상이면서 아직 요약되지 않은 게임 조회
        List<ReviewStat> targets = reviewStatRepository.findTargetsWithoutSummary(minThreshold, batchSize);

        if (targets.isEmpty()) {
            return 0;
        }

        log.info("[GenAI] Processing a single batch. Target count: {}", targets.size());

        // 리뷰 요약 작업을 비동기 스레드 풀에 할당
        // taskExecutor: 부모 스레드의 MDC 상태(Trace ID)를 신규 스레드로 자동 복사
        List<CompletableFuture<Void>> futures = targets.stream()
                .map(stat -> CompletableFuture.runAsync(() -> {
                    processSingleGameSummary(stat);
                }, taskExecutor))
                .toList();

        // 현재 배치의 모든 비동기 작업 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return targets.size();
    }

    /**
     * 단일 게임의 리뷰 요약 실행 (조회 -> FastAPI 통신 -> DB 업데이트)
     * 예외 발생 시 사유별 실패 작업 적재
     */
    private void processSingleGameSummary(ReviewStat stat) {
        Long gameId = stat.getGame().getId();
        Long reviewStatId = stat.getId();

        try {
            // 단일 게임 리뷰 텍스트 목록 추출
            List<String> reviewTexts = extractReviewTexts(gameId);

            // 유효한 리뷰 텍스트가 없는 경우 실패 작업 기록 후 종료
            if (reviewTexts.isEmpty()) {
                log.warn("[GenAI] Insufficient valid reviews for Game ID: {}. Recording to FailedTask.", gameId);
                recordFailedTask(gameId, GenaiFailReason.INSUFFICIENT_DATA);
                return;
            }

            // 리뷰 요약 엔진 통신
            ReviewSummaryResponse response = requestSummaryToFastApi(gameId, stat, reviewTexts);

            // 수신된 리뷰 요약 응답 데이터 유효성 검증 및 분기 처리
            if (isValidResponse(response)) {
                applySummaryResult(reviewStatId, response);
            } else {
                log.warn("[GenAI] AI returned invalid summary for Game ID: {}. Recording to FailedTask.", gameId);
                recordFailedTask(gameId, GenaiFailReason.INVALID_RESPONSE);
            }
        } catch (Exception e) {
            // 통신 장애 등 예외 발생 시 실패 작업 기록
            // 추후 FastAPI에서 안전 필터 거부 시 HTTP Error 코드를 반환하도록 수정되면 여기서 분기 처리 가능
            log.error("[GenAI] Unexpected error processing Game ID: {}. Recording to FailedTask.", gameId, e);
            recordFailedTask(gameId, GenaiFailReason.NETWORK_ERROR);
        }
    }

    /**
     * 스팀 리뷰 요약 미조치 실패 작업 재시도
     */
    public void retryFailedTasks() {
        // 미조치 리뷰 요약 실패 내역 일괄 조회
        List<GenaiFailedTask> failedTasks = failedTaskRepository.findUnhandledTasks(GenaiPipelineType.STEAM_REVIEW_SUMMARY);

        if (failedTasks.isEmpty()) {
            return;
        }

        log.info("[GenAI] Retrying {} failed Review Summary tasks...", failedTasks.size());

        // 대상 실패 작업을 순회하며 단건 재시도 수행
        for (GenaiFailedTask task : failedTasks) {
            Long gameId = task.getTargetId();
            try {
                // 실패 작업의 식별자를 기반으로 리뷰 통계 엔티티 조회
                ReviewStat stat = reviewStatRepository.findById(gameId)
                        .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_REVIEW_STAT));

                // 단일 게임에 대한 리뷰 텍스트 목록 추출
                List<String> reviewTexts = extractReviewTexts(gameId);

                if (reviewTexts.isEmpty()) {
                    log.warn("[GenAI] Insufficient valid reviews for Game ID: {}. Skipping.", gameId);
                    continue;
                }

                // 리뷰 요약 엔진으로 재요청 통신
                ReviewSummaryResponse response = requestSummaryToFastApi(gameId, stat, reviewTexts);

                // 응답 유효성 검증 후 DB 반영 및 상태 갱신
                if (isValidResponse(response)) {
                    // 리뷰 요약 결과 DB 반영
                    applySummaryResult(stat.getId(), response);

                    // 실패 작업 조치 완료(isHandled) 상태 갱신
                    transactionTemplate.executeWithoutResult(status -> {
                        GenaiFailedTask managedTask = failedTaskRepository.findById(task.getId()).orElseThrow();
                        managedTask.markAsHandled();
                    });
                }
            } catch (Exception e) {
                // 단건 재시도 실패 시 예외 격리 및 다음 루프 진행
                log.error("[GenAI] Failed to retry Review Summary for Game ID: {}. Skipping.", gameId, e);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Helper Methods
    // ---------------------------------------------------------------------------------

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
    public void applySummaryResult(Long reviewStatId, ReviewSummaryResponse response) {
        transactionTemplate.executeWithoutResult(status -> {
            // 병렬 처리 환경에서 최신 상태를 유지하기 위해 원본 데이터를 다시 읽어온 후 업데이트
            ReviewStat managedStat = reviewStatRepository.findById(reviewStatId)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_REVIEW_STAT));

            // 요약 텍스트 및 긍정/부정 키워드 매핑
            managedStat.updateReviewSummary(
                    response.summaryText(),
                    response.positiveKeywords(),
                    response.negativeKeywords()
            );
        });
        log.info("[GenAI] Successfully updated summary and keywords for ReviewStat ID: {}", reviewStatId);
    }

    /**
     * 추후 재시도 및 통계를 위한 실패 대상 식별자 및 실패 사유 적재
     */
    private void recordFailedTask(Long targetId, GenaiFailReason reason) {
        transactionTemplate.executeWithoutResult(status -> {
            GenaiFailedTask failedTask = GenaiFailedTask.builder()
                    .pipelineType(GenaiPipelineType.STEAM_REVIEW_SUMMARY)
                    .targetId(targetId)
                    .failReason(reason)
                    .build();

            failedTaskRepository.save(failedTask);
        });
    }
}
