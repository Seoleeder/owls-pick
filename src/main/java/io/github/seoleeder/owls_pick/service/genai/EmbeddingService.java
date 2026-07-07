package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.dto.embedding.EmbeddingBatchRequest;
import io.github.seoleeder.owls_pick.dto.embedding.EmbeddingSourceDto;
import io.github.seoleeder.owls_pick.dto.response.EmbeddingBatchResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiFailReason;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.entity.game.enums.status.EmbeddingStatus;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.GenaiFailedTaskRepository;
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
    private final GenaiFailedTaskRepository failedTaskRepository;

    public EmbeddingService(
            GameRepository gameRepository,
            VectorEmbeddingRepository vectorEmbeddingRepository,
            @Qualifier("genaiRestClient") RestClient restClient,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
            TransactionTemplate transactionTemplate,
            GenaiProperties props,
            GenaiFailedTaskRepository failedTaskRepository) {
        this.gameRepository = gameRepository;
        this.vectorEmbeddingRepository = vectorEmbeddingRepository;
        this.restClient = restClient;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.props = props;
        this.failedTaskRepository = failedTaskRepository;
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
            try{
                // 설정된 단위만큼 조회 후 처리
                int processedCount = processDataChunk(dbFetchSize);

                // 처리할 대상 데이터가 없으면 파이프라인 종료
                if (processedCount == 0) {
                    break;
                }

                // 누적 처리 건수 갱신
                totalProcessed += processedCount;

            } catch (Exception e) {
                // DB 조회 등 메인 루프에서 발생하는 예외 격리
                log.error("[GenAI] Failed to process vector embedding chunk. Skipping to next cycle.", e);
            }

            try {
                // API 서버 과부하 방지 및 TPM 한도 대응을 위한 대기 시간 적용
                Thread.sleep(props.embedding().delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[GenAI] Vector Embedding Pipeline sleep interrupted", e);
                break;
            }
        }

        log.info("[GenAI] Vector Embedding Pipeline Finished. Estimated total processed: {}", totalProcessed);
    }

    /**
     * 지정된 단위로 원본 데이터 조회 후 서브 배치로 분할하여 병렬 처리 (API 요청 및 DB 저장)
     */
    public int processDataChunk(int dbFetchSize) {

        // 임베딩이 필요한 게임들의 메타데이터 조회 (임베딩 실패한 게임 제외)
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
                .map(batch -> CompletableFuture.runAsync(() -> processSingleBatch(batch), taskExecutor))
                .toList();

        // 현재 배치의 모든 작업이 끝날 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return rawDataList.size();
    }

    /**
     * 단일 배치 데이터의 FastAPI 전송 및 응답 결과 DB 반영
     */
    private void processSingleBatch(List<EmbeddingSourceDto> batch) {
        try {
            // 임베딩 생성에 필요한 데이터만 추출하여 통신용 DTO로 변환 (리뷰 데이터 제외)
            List<EmbeddingBatchRequest.GameEmbeddingData> requestData = batch.stream()
                    .map(EmbeddingBatchRequest.GameEmbeddingData::from)
                    .toList();

            // 외부 모델 엔진으로 벡터 변환 요청
            EmbeddingBatchResponse response = requestEmbeddingToFastApi(requestData);

            // 정상 응답 시 원본 데이터와 결합하여 DB 반영 로직 호출
            if (isValidResponse(response)) {
                applyEmbeddingResult(response, batch);
            } else {
                log.warn("[GenAI] AI returned invalid embedding batch response. Recording FAILED status.");
                handleFailedEmbeddings(batch, GenaiFailReason.INVALID_RESPONSE);
            }
        } catch (Exception e) {
            log.error("[GenAI] Unexpected error processing embedding batch. Recording FAILED status.", e);
            handleFailedEmbeddings(batch, GenaiFailReason.NETWORK_ERROR);
        }
    }

    /**
     * FastAPI 서버에 벡터 임베딩 생성 요청 및 응답 반환
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

        // 응답 데이터에서 식별자 목록 추출
        List<Long> gameIds = response.results().stream()
                .map(EmbeddingBatchResponse.EmbeddedGame::gameId)
                .toList();

        // 응답받은 GameId와 원본 데이터를 1:1 매핑
        Map<Long, EmbeddingSourceDto> sourceDataMap = batch.stream()
                .collect(Collectors.toMap(EmbeddingSourceDto::gameId, data -> data));

        // 내부 트랜잭션 생성 및 데이터 저장 처리
        Integer savedCount = transactionTemplate.execute(status -> {

            // 기존 임베딩 데이터 일괄 조회 (Update 대상)
            Map<Long, VectorEmbedding> existingEmbeddings = vectorEmbeddingRepository.findExistingEmbeddingsByGameIds(gameIds)
                    .stream()
                    .collect(Collectors.toMap(e -> e.getGame().getId(), e -> e));

            // 신규 생성이 필요한 게임 식별자 필터링 (Insert 대상)
            List<Long> newGameIds = gameIds.stream()
                    .filter(id -> !existingEmbeddings.containsKey(id))
                    .toList();

            // 신규 생성을 위한 Game 엔티티 조회
            Map<Long, Game> newGamesMap = newGameIds.isEmpty() ? Collections.emptyMap() :
                    gameRepository.findAllById(newGameIds).stream()
                            .collect(Collectors.toMap(Game::getId, game -> game));

            List<VectorEmbedding> embeddingsToSave = new ArrayList<>();

            // 응답 결과에 따른 Upsert 분기 처리
            for (EmbeddingBatchResponse.EmbeddedGame result : response.results()) {
                float[] vector = result.status() == EmbeddingStatus.SUCCESS ? result.vector() : null;

                // 응답받은 벡터와 내부 데이터를 활용해 최종 텍스트 생성
                EmbeddingSourceDto source = sourceDataMap.get(result.gameId());
                String sourceText = source.toFinalSourceText();

                if (existingEmbeddings.containsKey(result.gameId())) {
                    // 기존 데이터 갱신 (객체 상태를 갱신하여 트랜잭션 종료 시 자동 반영되도록 처리)
                    VectorEmbedding existing = existingEmbeddings.get(result.gameId());
                    existing.updateEmbeddingData(vector, result.status(), sourceText);
                } else if (newGamesMap.containsKey(result.gameId())) {
                    // 신규 엔티티 생성 및 매핑 (엔티티로 생성하여 명시적 저장 대기열에 추가)
                    embeddingsToSave.add(VectorEmbedding.builder()
                            .game(newGamesMap.get(result.gameId()))
                            .embedding(vector)
                            .embeddingStatus(result.status())
                            .sourceText(sourceText)
                            .build());
                }
            }

            // 신규 생성된 데이터 일괄 저장
            if (!embeddingsToSave.isEmpty()) {
                vectorEmbeddingRepository.saveAll(embeddingsToSave);
            }
            // 임베딩 처리된 게임 수 반환
            return response.results().size();
        });

        // 처리 완료 건수 로깅
        if (savedCount != null && savedCount > 0) {
            log.info("[GenAI] Successfully saved/updated {} vector embedding results.", savedCount);
        }

        // 변환 실패 건수 로깅
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

    /**
     * 임베딩 작업 실패 시 해당 배치의 데이터를 실패 상태로 기록
     * VectorEmbedding : 임베딩 상태를 FAILED로 갱신 (재시도 대상에 포함)
     * GenaiFailedTask : 실패 사유 적재
     */
    private void handleFailedEmbeddings(List<EmbeddingSourceDto> batch, GenaiFailReason reason) {

        List<Long> gameIds = batch.stream()
                .map(EmbeddingSourceDto::gameId)
                .toList();

        transactionTemplate.executeWithoutResult(status -> {

            // 기존 임베딩 이력이 있는 데이터 일괄 조회
            Map<Long, VectorEmbedding> existingEmbeddings = vectorEmbeddingRepository.findExistingEmbeddingsByGameIds(gameIds)
                    .stream()
                    .collect(Collectors.toMap(e -> e.getGame().getId(), e -> e));

            // 임베딩 이력이 없는 신규 게임 식별자 필터링
            List<Long> newGameIds = gameIds.stream()
                    .filter(id -> !existingEmbeddings.containsKey(id))
                    .toList();

            // 신규 데이터 생성을 위해 연관 Game 엔티티 조회
            Map<Long, Game> newGamesMap = newGameIds.isEmpty() ? Collections.emptyMap() :
                    gameRepository.findAllById(newGameIds).stream()
                            .collect(Collectors.toMap(Game::getId, game -> game));

            List<VectorEmbedding> embeddingsToSave = new ArrayList<>();

            // 각 데이터의 존재 여부에 따라 Update 또는 Insert용 객체 생성
            for (EmbeddingSourceDto source : batch) {
                Long gameId = source.gameId();
                String sourceText = source.toFinalSourceText();

                if (existingEmbeddings.containsKey(gameId)) {
                    // 기존 데이터는 벡터값을 비우고 FAILED 업데이트
                    VectorEmbedding existing = existingEmbeddings.get(gameId);
                    existing.updateEmbeddingData(null, EmbeddingStatus.FAILED, sourceText);
                } else if (newGamesMap.containsKey(gameId)) {
                    // 신규 데이터는 FAILED 상태의 새로운 인스턴스로 생성하여 대기열에 추가
                    embeddingsToSave.add(VectorEmbedding.builder()
                            .game(newGamesMap.get(gameId))
                            .embedding(null)
                            .embeddingStatus(EmbeddingStatus.FAILED)
                            .sourceText(sourceText)
                            .build());
                }
            }

            // 신규 생성된 엔티티 일괄 저장
            if (!embeddingsToSave.isEmpty()) {
                vectorEmbeddingRepository.saveAll(embeddingsToSave);
            }

            // 재시도 로직에서 사용할 실패 작업 객체 생성
            List<GenaiFailedTask> failedTasks = batch.stream()
                    .map(source -> GenaiFailedTask.builder()
                            .pipelineType(GenaiPipelineType.VECTOR_EMBEDDING)
                            .targetId(source.gameId())
                            .failReason(reason)
                            .build())
                    .toList();

            // 실패 작업 테이블에 일괄 저장
            failedTaskRepository.saveAll(failedTasks);
        });
    }
}
