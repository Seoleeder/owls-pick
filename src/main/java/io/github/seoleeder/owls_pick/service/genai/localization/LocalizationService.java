package io.github.seoleeder.owls_pick.service.genai.localization;

import io.github.seoleeder.owls_pick.dto.request.BulkLocalizationRequest;
import io.github.seoleeder.owls_pick.dto.response.LocalizationBulkResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiFailReason;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.GenaiFailedTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LocalizationService {

    private final GameRepository gameRepository;
    private final RestClient localizationRestClient;
    private final GenaiProperties props;
    private final TransactionTemplate transactionTemplate;
    private final GenaiFailedTaskRepository failedTaskRepository;

    public LocalizationService(
            GameRepository gameRepository,
            @Qualifier("genaiRestClient") RestClient localizationRestClient,
            GenaiProperties props,
            TransactionTemplate transactionTemplate,
            GenaiFailedTaskRepository failedTaskRepository) {

        this.gameRepository = gameRepository;
        this.localizationRestClient = localizationRestClient;
        this.props = props;
        this.transactionTemplate = transactionTemplate;
        this.failedTaskRepository = failedTaskRepository;
    }

    /**
     * 환경 변수에 설정된 기본 청크 사이즈로 한글화 파이프라인 실행
     */
    public void runPipeline() {
        runPipeline(props.localization().chunkSize().game());
    }

    /**
     * 지정된 청크 단위로 한글화 파이프라인 연속 실행
     */
    public void runPipeline(int chunkSize) {
        log.info("Starting Game Description Localization Pipeline with chunk size {}...", chunkSize);
        int totalProcessed = 0;

        while (true) {
            try {

                int processedCount = processLocalizationChunk(chunkSize);

                if (processedCount == 0) {
                    break; // 한글화되지 않은 데이터 소진 시 루프 탈출
                }
                totalProcessed += processedCount;

            } catch (Exception e) {
                // 단일 청크 처리 실패 시 로그 기록 후 다음 주기로 이동
                log.error("Failed to process localization chunk. Skipping to next cycle.", e);
            }

            try {
                // API 부하 방지를 위한 대기 시간 적용
                Thread.sleep(props.localization().delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Game Description Localization Pipeline sleep interrupted", e);
                break;
            }
        }
        log.info("Game Description Localization Pipeline Finished. Total localized games: {}", totalProcessed);
    }

    /**
     * 지정된 단위(Chunk)로 미번역 게임 데이터를 조회하여 한글화 파이프라인 실행
     */
    public int processLocalizationChunk(int chunkSize) {
        List<Game> targetGames = gameRepository.findUnlocalizedGames(chunkSize);
        if (targetGames.isEmpty()) {
            log.debug("No unlocalized games found. Task skipped.");
            return 0;
        }

        try {
            // 요청 DTO 조립
            BulkLocalizationRequest request = buildRequestDto(targetGames);

            // 한글화 엔진 통신
            LocalizationBulkResponse response = sendToAiEngine(request);

            // 결과 DB 반영
            Integer result = transactionTemplate.execute(status -> {

                // 영속 상태의 엔티티 일괄 재조회
                List<Long> gameIds = targetGames.stream().map(Game::getId).toList();
                List<Game> managedGames = gameRepository.findAllById(gameIds);

                // 한글화 결과 매핑 및 상태 업데이트
                return applyLocalizationResults(managedGames, response);
            });

            return result != null ? result : 0;
        } catch (Exception e) {
            // 통신 장애 시 대상 청크를 실패 작업으로 기록
            log.error("Failed to process localization chunk. Recording {} games to DLQ.", targetGames.size());
            recordFailedTasks(targetGames, GenaiFailReason.NETWORK_ERROR);

            // 루프 유지를 위해 현재 청크 사이즈 반환
            return targetGames.size();
        }
    }

    /**
     * 게임 데이터 한글화 실패 작업 재시도
     */
    public void retryFailedTasks() {
        // 아직 조치되지 않은 한글화 실패 내역 조회
        List<GenaiFailedTask> failedTasks = failedTaskRepository.findUnhandledTasks(GenaiPipelineType.GAME_LOCALIZATION);
        if (failedTasks.isEmpty()) {
            return;
        }

        log.info("[GenAI] Retrying {} failed Game Localization tasks...", failedTasks.size());

        // 사전에 정의된 청크 사이즈 할당
        int chunkSize = props.localization().chunkSize().game();

        // 실패 작업 API 한도 방어를 위한 청크 단위 분할 처리
        for (int i = 0; i < failedTasks.size(); i += chunkSize) {
            List<GenaiFailedTask> taskChunk = failedTasks.subList(i, Math.min(failedTasks.size(), i + chunkSize));
            List<Long> gameIds = taskChunk.stream().map(GenaiFailedTask::getTargetId).toList();

            try {
                // 실패 대상 게임 엔티티 조회
                List<Game> targetGames = gameRepository.findAllById(gameIds);

                // 통신 DTO 생성 및 한글화 재요청
                BulkLocalizationRequest request = buildRequestDto(targetGames);
                LocalizationBulkResponse response = sendToAiEngine(request);

                // 재시도 결과 적용 및 실패 작업 조치 완료 처리
                transactionTemplate.executeWithoutResult(status -> {
                    // 원본 게임 엔티티 영속화 및 한글화 텍스트 반영
                    List<Game> managedGames = gameRepository.findAllById(gameIds);
                    applyLocalizationResults(managedGames, response);

                    // 실패 이력 영속화 및 처리 상태(isHandled) 갱신
                    List<Long> taskIds = taskChunk.stream().map(GenaiFailedTask::getId).toList();
                    List<GenaiFailedTask> managedTasks = failedTaskRepository.findAllById(taskIds);
                    managedTasks.forEach(GenaiFailedTask::markAsHandled);
                });
            } catch (Exception e) {
                // 재시도 단일 청크 실패 시 로그 기록 후 흐름 유지
                log.error("[GenAI] Failed to retry Game Localization chunk. Skipping to next chunk.", e);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Helper Methods
    // ---------------------------------------------------------------------------------

    /**
     * Game 엔티티 리스트를 외부 한글화 엔진 통신용 Request DTO로 변환
     */
    private BulkLocalizationRequest buildRequestDto(List<Game> games) {
        List<BulkLocalizationRequest.GameItem> items = games.stream()
                .map(game -> new BulkLocalizationRequest.GameItem(
                        game.getId(),
                        game.getDescription(),
                        game.getStoryline()
                )).toList();

        return new BulkLocalizationRequest(items);
    }

    /**
     * 한글화 엔진으로 실제 HTTP 요청을 보내고 한글화 결과 반환
     */
    private LocalizationBulkResponse sendToAiEngine(BulkLocalizationRequest request) {
        log.info("Sending bulk localization request for {} games to AI Engine...", request.games().size());

        URI targetUri = UriComponentsBuilder.fromUriString(props.fastapiUrl())
                .path("/api/localization/games/bulk")
                .build()
                .toUri();

        LocalizationBulkResponse response;

        try {
            response = localizationRestClient.post()
                    .uri(targetUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(LocalizationBulkResponse.class);
        } catch (Exception e) {
            log.error("Failed to communicate with Localization Engine. Error: {}", e.getMessage());
            throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
        }

        if (response == null || !response.success() || response.results() == null) {
            log.error("AI Engine returned invalid response or task failed.");
            throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
        }
        return response;

    }

    /**
     * 한글화 엔진으로부터 반환된 결과를 원본 게임 엔티티에 매핑 후 업데이트
     */
    private int applyLocalizationResults(List<Game> targetGames, LocalizationBulkResponse response) {
        // 빠른 조회를 위해 List를 Map으로 변환
        Map<Long, Game> gameMap = targetGames.stream()
                .collect(Collectors.toMap(Game::getId, g -> g));

        int successCount = 0;
        for (LocalizationBulkResponse.ResultItem result : response.results()) {
            Game game = gameMap.get(result.gameId());
            if (game != null) {
                game.updateLocalization(result.descriptionKo(), result.storylineKo());
                successCount++;
            }
        }

        log.info("Successfully updated {} localized games in Database.", successCount);
        return successCount;
    }

    /**
     * 추후 재시도 및 통계를 위한 실패 대상 식별자 및 실패 사유 적재
     */
    private void recordFailedTasks(List<Game> targetGames, GenaiFailReason reason) {
        transactionTemplate.executeWithoutResult(status -> {
            List<GenaiFailedTask> failedTasks = targetGames.stream()
                    .map(game -> GenaiFailedTask.builder()
                            .pipelineType(GenaiPipelineType.GAME_LOCALIZATION)
                            .targetId(game.getId())
                            .failReason(reason)
                            .build())
                    .toList();
            failedTaskRepository.saveAll(failedTasks);
        });
    }
}
