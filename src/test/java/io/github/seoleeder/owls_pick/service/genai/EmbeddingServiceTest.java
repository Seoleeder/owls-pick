package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.dto.embedding.EmbeddingSourceDto;
import io.github.seoleeder.owls_pick.dto.response.EmbeddingBatchResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.entity.game.enums.status.EmbeddingStatus;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.VectorEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class EmbeddingServiceTest {

    @InjectMocks
    private EmbeddingService embeddingService;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private VectorEmbeddingRepository vectorEmbeddingRepository;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private GenaiProperties genaiProperties;

    @Captor
    private ArgumentCaptor<List<VectorEmbedding>> embeddingListCaptor;

    @BeforeEach
    void setUp() {
        // 트랜잭션 템플릿 콜백 실행 모킹
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        // 비동기 스레드 풀 작업을 메인 스레드에서 즉시 실행하도록 모킹
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        // 프로퍼티 인스턴스 초기화 및 주입 (API 청크 크기: 2)
        lenient().when(genaiProperties.fastapiUrl()).thenReturn("http://localhost:8000");
        GenaiProperties.Embedding embedProps = new GenaiProperties.Embedding(100, 2, 5); // apiBatchSize = 2
        lenient().when(genaiProperties.embedding()).thenReturn(embedProps);

        // RestClient 체이닝 명시적 조립 및 모킹
        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("처리 대상 데이터가 설정된 API 배치 크기 초과 시 다중 호출로 분할되는지 확인")
    void processDataChunk_Partitioning_MultipleApiBatches() {
        // [Given] 임베딩 소스 데이터 3건 세팅 (배치 크기 2 설정에 따른 2분할 조건 충족)
        EmbeddingSourceDto dto1 = new EmbeddingSourceDto(1L, "G1", "Desc1", List.of("RPG"), List.of("Fantasy"), List.of("Magic"), 40, "Positive", "Summary1");
        EmbeddingSourceDto dto2 = new EmbeddingSourceDto(2L, "G2", "Desc2", List.of("FPS"), List.of("Sci-Fi"), List.of("Gun"), 20, "Mixed", "Summary2");
        EmbeddingSourceDto dto3 = new EmbeddingSourceDto(3L, "G3", "Desc3", List.of("Puzzle"), List.of("Logic"), List.of("Hard"), 10, "Negative", "Summary3");

        when(gameRepository.findGamesForEmbedding(100)).thenReturn(List.of(dto1, dto2, dto3));

        // [Given] API 통신 성공 빈 응답 객체 모킹
        EmbeddingBatchResponse mockResponse = new EmbeddingBatchResponse(List.of());
        when(responseSpec.body(eq(EmbeddingBatchResponse.class))).thenReturn(mockResponse);

        // [When] 청크 단위 임베딩 데이터 분할 및 병렬 처리 실행
        int processed = embeddingService.processDataChunk(100);

        // [Then] 총 3건 처리 반환 및 설정된 배치 크기 기준 API 2회 분할 호출 검증
        assertThat(processed).isEqualTo(3);
        verify(restClient, times(2)).post();
    }

    @Test
    @DisplayName("임베딩 생성 결과 기반 Upsert(기존 데이터 갱신 및 신규 생성) 분기 로직 정상 동작 확인")
    void applyEmbeddingResult_Upsert_InsertAndUpdate() {
        // [Given] 기존 데이터(1L) 및 신규 데이터(2L) 소스 세팅
        EmbeddingSourceDto dto1 = new EmbeddingSourceDto(1L, "Title1", "Desc1", List.of("Genre"), List.of("Theme"), List.of("Keyword"), 15, "Positive", "Summary");
        EmbeddingSourceDto dto2 = new EmbeddingSourceDto(2L, "Title2", "Desc2", List.of("Genre"), List.of("Theme"), List.of("Keyword"), 30, "Positive", "Summary");
        List<EmbeddingSourceDto> batch = List.of(dto1, dto2);

        // [Given] API 응답 벡터 및 RAG 프롬프트(sourceText) 반환 객체 모킹
        float[] mockVector = {0.1f, 0.2f};
        EmbeddingBatchResponse.EmbeddedGame result1 = new EmbeddingBatchResponse.EmbeddedGame(1L, mockVector, "Source Text 1", EmbeddingStatus.SUCCESS);
        EmbeddingBatchResponse.EmbeddedGame result2 = new EmbeddingBatchResponse.EmbeddedGame(2L, mockVector, "Source Text 2", EmbeddingStatus.SUCCESS);
        EmbeddingBatchResponse response = new EmbeddingBatchResponse(List.of(result1, result2));

        // 1번 게임: 기존 임베딩 엔티티 존재 상황 세팅 (Update 대상)
        VectorEmbedding existingEmbedding = mock(VectorEmbedding.class);
        Game mockGame1 = mock(Game.class);
        when(existingEmbedding.getGame()).thenReturn(mockGame1);
        when(mockGame1.getId()).thenReturn(1L);
        when(vectorEmbeddingRepository.findExistingEmbeddingsByGameIds(List.of(1L, 2L)))
                .thenReturn(List.of(existingEmbedding));

        // 2번 게임: 임베딩 엔티티 미존재 상황 세팅 (Insert 대상)
        Game mockGame2 = mock(Game.class);
        when(mockGame2.getId()).thenReturn(2L);
        when(gameRepository.findAllById(List.of(2L))).thenReturn(List.of(mockGame2));

        // [When] 임베딩 결과 기반 Upsert 로직 실행
        embeddingService.applyEmbeddingResult(response, batch);

        // [Then] 1번 게임: 기존 엔티티 상태 갱신(updateEmbeddingData) 정상 호출 검증
        verify(existingEmbedding, times(1)).updateEmbeddingData(eq(mockVector), eq(EmbeddingStatus.SUCCESS), anyString());

        // [Then] 2번 게임 포함 총 2건의 엔티티 DB 저장(saveAll) 캡처 및 상태 검증
        verify(vectorEmbeddingRepository, times(1)).saveAll(embeddingListCaptor.capture());
        List<VectorEmbedding> savedList = embeddingListCaptor.getValue();
        assertThat(savedList).hasSize(2);
    }

    @Test
    @DisplayName("처리 대상 데이터 소진 시 임베딩 파이프라인 무한 루프 정상 탈출 확인")
    void runPipeline_DataExhausted_BreakLoop() {
        // [Given] 1회차 대상 데이터 존재, 2회차 데이터 소진(루프 탈출 조건) 세팅
        EmbeddingSourceDto mockDto = new EmbeddingSourceDto(1L, "Title", "Desc", List.of("RPG"), List.of("Fantasy"), List.of("Magic"), 40, "Positive", "Summary");
        when(gameRepository.findGamesForEmbedding(anyInt()))
                .thenReturn(List.of(mockDto))
                .thenReturn(Collections.emptyList());

        EmbeddingBatchResponse mockResponse = new EmbeddingBatchResponse(List.of());
        when(responseSpec.body(eq(EmbeddingBatchResponse.class))).thenReturn(mockResponse);

        // [When] 임베딩 파이프라인(무한 루프) 실행
        embeddingService.runPipeline(100);

        // [Then] 데이터 소진 감지 후 파이프라인 정상 종료 여부(DB 2회 조회) 검증
        verify(gameRepository, times(2)).findGamesForEmbedding(100);
    }

    @Test
    @DisplayName("FastAPI 통신 장애 발생 시 시스템 중단 없이 예외가 격리되는지 확인")
    void processDataChunk_CommunicationError_ThrowException() {
        // [Given] 임베딩 처리 대상 데이터 세팅
        EmbeddingSourceDto mockDto = new EmbeddingSourceDto(1L, "Title", "Desc", List.of("RPG"), List.of("Fantasy"), List.of("Magic"), 40, "Positive", "Summary");
        when(gameRepository.findGamesForEmbedding(100)).thenReturn(List.of(mockDto));

        // [Given] 외부 API 통신 실패(타임아웃 등 RestClientException) 상황 모킹
        when(responseSpec.body(eq(EmbeddingBatchResponse.class)))
                .thenThrow(new RestClientException("Connection Timeout"));

        // [When] 청크 단위 임베딩 데이터 분할 및 병렬 처리 실행
        int processedCount = embeddingService.processDataChunk(100);

        // [Then] 내부 예외 발생에도 전체 프로세스 중단 없이 에러 격리 후 로직 완주(1건 반환) 검증
        assertThat(processedCount).isEqualTo(1);

        // [Then] 통신 실패 시 DB 반영 로직(Transaction) 실행 차단 검증
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    @DisplayName("AI 엔진으로부터 빈 응답(Invalid) 수신 시 DB 저장 로직 차단 확인")
    void processDataChunk_InvalidResponse_SkipUpdate() {
        // [Given] 임베딩 처리 대상 데이터 세팅
        EmbeddingSourceDto mockDto = new EmbeddingSourceDto(1L, "Title", "Desc", List.of("RPG"), List.of("Fantasy"), List.of("Magic"), 40, "Positive", "Summary");
        when(gameRepository.findGamesForEmbedding(100)).thenReturn(List.of(mockDto));

        // [Given] 통신 성공 후 비정상(Empty Results) 응답 수신 상황 모킹
        EmbeddingBatchResponse invalidResponse = new EmbeddingBatchResponse(Collections.emptyList());
        when(responseSpec.body(eq(EmbeddingBatchResponse.class))).thenReturn(invalidResponse);

        // [When] 청크 단위 임베딩 처리 로직 실행
        embeddingService.processDataChunk(100);

        // [Then] 응답 데이터 유효성 검증 실패에 따른 DB 반영 로직(Transaction) 실행 차단 검증
        verify(transactionTemplate, never()).execute(any());
    }
}