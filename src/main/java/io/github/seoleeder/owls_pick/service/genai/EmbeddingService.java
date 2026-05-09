package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.dto.embedding.EmbeddingBatchRequest;
import io.github.seoleeder.owls_pick.dto.embedding.EmbeddingSourceDto;
import io.github.seoleeder.owls_pick.dto.response.EmbeddingBatchResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.entity.game.enums.status.EmbeddingStatus;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.VectorEmbeddingRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 게임 메타데이터 RAG 벡터 임베딩 파이프라인 서비스
 */
@Slf4j
@Service
public class EmbeddingService {

    private final GameRepository gameRepository;
    private final VectorEmbeddingRepository vectorEmbeddingRepository;
    private final RestClient restClient;
    private final GenaiProperties props;

    private final AsyncTaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;

    public EmbeddingService(
            GameRepository gameRepository,
            VectorEmbeddingRepository vectorEmbeddingRepository,
            @Qualifier("genaiRestClient") RestClient restClient,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
            TransactionTemplate transactionTemplate,
            GenaiProperties props) {
        this.gameRepository = gameRepository;
        this.vectorEmbeddingRepository = vectorEmbeddingRepository;
        this.restClient = restClient;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.props = props;
    }

    /**
     * 환경 변수에 설정된 DB 조회 크기(dbFetchSize)를 기준으로 임베딩 파이프라인 시작
     */
    public void runPipeline() {
        runPipeline(props.embedding().dbFetchSize());
    }

    /**
     * 전체 게임 데이터를 순회하며 벡터 임베딩 파이프라인 무한 루프 실행
     */
    public void runPipeline(int dbFetchSize) {
        log.info("[GenAI] Starting Vector Embedding Pipeline with chunk size {}...", dbFetchSize);

        int totalProcessed = 0;

        while (true) {
            // 설정된 개수만큼 조회 후 처리
            int processedCount = processDataChunk(dbFetchSize);

            // 더이상 처리할 게임이 없으면 루프 종료
            if (processedCount == 0) {
                break;
            }

            // 처리된 게임 수 업데이트
            totalProcessed += dbFetchSize;

            try {
                // API 서버 과부하 방지
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[GenAI] Vector Embedding Pipeline sleep interrupted", e);
                break;
            }
        }

        log.info("[GenAI] Vector Embedding Pipeline Finished. Estimated total processed: {}", totalProcessed);
    }

    /**
     * 지정된 조회 단위만큼 데이터를 읽어와 분할 병렬 처리(API 요청 및 DB 저장) 수행
     */
    public int processDataChunk(int dbFetchSize) {

        // 임베딩 벡터가 없는 게임들의 원본 데이터 조회
        List<EmbeddingSourceDto> rawDataList = gameRepository.findGamesForEmbedding(dbFetchSize);
        if (rawDataList.isEmpty()) {
            return 0;
        }

        log.info("[GenAI] Fetched unprocessed chunk. Target count: {}", rawDataList.size());

        // 설정된 배치 사이즈에 맞춰 소그룹으로 분할
        int apiBatchSize = props.embedding().apiBatchSize();
        List<List<EmbeddingSourceDto>> partitions = partitionList(rawDataList, apiBatchSize);

        // 분할된 그룹별로 비동기 병렬 처리
        List<CompletableFuture<Void>> futures = partitions.stream()
                .map(batch -> CompletableFuture.runAsync(() -> {
                    try {
                        processSingleBatch(batch);
                    } catch (Exception e) {
                        // 단일 배치 실패가 전체 청크/파이프라인 진행을 멈추지 않음
                        log.error("[GenAI] Unexpected error during embedding batch. Skipping batch.", e);
                    }
                }, taskExecutor))
                .toList();

        // 현재 배치의 모든 작업이 끝날 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return rawDataList.size();
    }

    /**
     * 분할된 단일 배치 데이터의 FastAPI 전송 및 응답 DB 저장 수행
     */
    private void processSingleBatch(List<EmbeddingSourceDto> batch) {
        // 임베딩 추출에 불필요한 리뷰 데이터를 제외하고 통신용 DTO로 변환
        List<EmbeddingBatchRequest.GameEmbeddingData> requestData = batch.stream()
                .map(EmbeddingBatchRequest.GameEmbeddingData::from)
                .toList();

        EmbeddingBatchResponse response = requestEmbeddingToFastApi(requestData);

        if (isValidResponse(response)) {
            // FastAPI 응답과 원본 데이터를 함께 전달
            applyEmbeddingResult(response, batch);
        }
    }

    /**
     * FastAPI 서버에 벡터 임베딩 변환 요청 및 응답 반환
     */
    private EmbeddingBatchResponse requestEmbeddingToFastApi(List<EmbeddingBatchRequest.GameEmbeddingData> batchData) {
        EmbeddingBatchRequest requestDto = new EmbeddingBatchRequest(batchData);
        URI targetUri = UriComponentsBuilder.fromUriString(props.fastapiUrl())
                .path("/api/genai/embeddings/batch")
                .build()
                .toUri();

        try {
            return restClient.post()
                    .uri(targetUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestDto)
                    .retrieve()
                    .body(EmbeddingBatchResponse.class);
        } catch (RestClientException e) {
            log.error("[GenAI] Communication Error with GenAI Server for Vector Embedding Batch.");
            throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
        }
    }

    /**
     * FastAPI 응답 객체의 데이터 존재 유효성 검증
     */
    private boolean isValidResponse(EmbeddingBatchResponse response) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            log.warn("[GenAI] Received empty or invalid embedding batch response.");
            return false;
        }
        return true;
    }

    /**
     * 변환 완료된 임베딩 벡터 데이터 일괄 저장 (Upsert 처리)
     */
    public void applyEmbeddingResult(EmbeddingBatchResponse response, List<EmbeddingSourceDto> batch) {

        // Bulk 조회를 위한 식별자 목록 추출
        List<Long> gameIds = response.results().stream()
                .map(EmbeddingBatchResponse.EmbeddedGame::gameId)
                .toList();

        // 응답받은 GameId와 원본 데이터를 1:1 매핑
        Map<Long, EmbeddingSourceDto> sourceDataMap = batch.stream()
                .collect(Collectors.toMap(EmbeddingSourceDto::gameId, data -> data));

        // 내부 트랜잭션 생성 및 데이터 저장 처리
        Integer savedCount = transactionTemplate.execute(status -> {

            // 기존 임베딩 데이터 일괄 조회 (Update 대상 식별)
            Map<Long, VectorEmbedding> existingEmbeddings = vectorEmbeddingRepository.findExistingEmbeddingsByGameIds(gameIds)
                    .stream()
                    .collect(Collectors.toMap(e -> e.getGame().getId(), e -> e));

            // 신규 생성이 필요한 식별자 필터링 (Insert 대상)
            List<Long> newGameIds = gameIds.stream()
                    .filter(id -> !existingEmbeddings.containsKey(id))
                    .toList();

            // 신규 생성을 위한 Game 엔티티 부분 조회
            Map<Long, Game> newGamesMap = newGameIds.isEmpty() ? Collections.emptyMap() :
                    gameRepository.findAllById(newGameIds).stream()
                            .collect(Collectors.toMap(Game::getId, game -> game));

            List<VectorEmbedding> embeddingsToSave = new ArrayList<>();

            // Upsert 분기 처리
            for (EmbeddingBatchResponse.EmbeddedGame result : response.results()) {
                float[] vector = result.status() == EmbeddingStatus.SUCCESS ? result.vector() : null;

                // 응답받은 벡터와 내부 데이터를 활용해 최종 텍스트 생성
                EmbeddingSourceDto source = sourceDataMap.get(result.gameId());
                String sourceText = source.toFinalSourceText();

                if (existingEmbeddings.containsKey(result.gameId())) {
                    // 기존 데이터 갱신
                    VectorEmbedding existing = existingEmbeddings.get(result.gameId());
                    existing.updateEmbeddingData(vector, result.status(), sourceText);
                    embeddingsToSave.add(existing);
                } else if (newGamesMap.containsKey(result.gameId())) {
                    // 신규 엔티티 생성 및 매핑
                    embeddingsToSave.add(VectorEmbedding.builder()
                            .game(newGamesMap.get(result.gameId()))
                            .embedding(vector)
                            .embeddingStatus(result.status())
                            .sourceText(sourceText)
                            .build());
                }
            }

            if (!embeddingsToSave.isEmpty()) {
                vectorEmbeddingRepository.saveAll(embeddingsToSave);
            }
            // 임베딩 처리된 게임 수 반환
            return embeddingsToSave.size();
        });

        // 성공 결과 로깅
        if (savedCount != null && savedCount > 0) {
            log.info("[GenAI] Successfully saved/updated {} vector embedding results.", savedCount);
        }

        // 변환 실패 데이터 로깅
        long failedCount = response.results().stream()
                .filter(result -> result.status() == EmbeddingStatus.FAILED)
                .count();
        if (failedCount > 0) {
            log.warn("[GenAI] {} games failed during vector embedding processing.", failedCount);
        }
    }

    /**
     * 리스트 분할 헬퍼 메서드
     */
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(new ArrayList<>(list.subList(i, Math.min(list.size(), i + size))));
        }
        return partitions;
    }

//    /**
//     * 애플리케이션 종료 시 잔여 스레드 작업 완료 대기 및 안전 종료
//     */
//    @PreDestroy
//    public void shutdownExecutor() {
//        log.info("[GenAI] Shutting down Vector Embedding ExecutorService...");
//        executorService.shutdown();
//        try {
//            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
//                executorService.shutdownNow();
//            }
//        } catch (InterruptedException e) {
//            executorService.shutdownNow();
//            Thread.currentThread().interrupt();
//        }
//    }
}
