package io.github.seoleeder.owls_pick.service.genai.localization;

import io.github.seoleeder.owls_pick.dto.response.LocalizationBulkResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.GenaiFailedTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LocalizationServiceTest {

    @InjectMocks
    private LocalizationService localizationService;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GenaiFailedTaskRepository failedTaskRepository;

    @Mock
    private RestClient localizationRestClient;

    // RestClient 체이닝 분리를 위한 중간 객체들
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Mock
    private GenaiProperties genaiProperties;

    @Mock
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // TransactionTemplate의 반환값 유무에 따른 콜백 실행 모킹
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // GenaiProperties 초기화 및 주입
        lenient().when(genaiProperties.fastapiUrl()).thenReturn("http://localhost:8000");
        GenaiProperties.Localization.ChunkSize chunkSize = new GenaiProperties.Localization.ChunkSize(10, 100);
        GenaiProperties.Localization localization = new GenaiProperties.Localization(chunkSize, 1000);
        lenient().when(genaiProperties.localization()).thenReturn(localization);

        // RestClient 체이닝 명시적 조립 및 모킹
        lenient().when(localizationRestClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("FastAPI 통신 장애 시 파이프라인 중단 없이 예외 격리 및 실패 작업 기록 확인")
    void processLocalizationChunk_CommunicationError_ThrowException() {
        // [Given] 한글화 대상 미번역 게임 데이터 세팅
        Game mockGame = mock(Game.class);
        when(mockGame.getId()).thenReturn(1L);
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(List.of(mockGame));

        // [Given] 외부 API 통신 타임아웃 예외 발생 상황 모킹
        when(responseSpec.body(LocalizationBulkResponse.class))
                .thenThrow(new RestClientException("Connection Timeout"));

        // [When] 청크 단위 한글화 처리 로직 실행
        int result = localizationService.processLocalizationChunk(10);

        // [Then] 처리 흐름 유지를 위한 처리 건수 반환 및 실패 이력 테이블 적재 검증
        assertThat(result).isEqualTo(1);
        verify(failedTaskRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("정상 응답 시 영속 상태 게임 엔티티의 한글화 텍스트 더티 체킹 유도 확인")
    void processLocalizationChunk_Success_UpdateEntity() {
        // [Given] 미번역 게임 엔티티 조회 및 필드 갱신을 위한 영속 상태 재조회 모킹
        Game mockGame = mock(Game.class);
        when(mockGame.getId()).thenReturn(1L);
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(List.of(mockGame));
        when(gameRepository.findAllById(anyList())).thenReturn(List.of(mockGame));

        // [Given] 한글화 엔진의 정상 번역 결과 응답 모킹
        LocalizationBulkResponse mockResponse = new LocalizationBulkResponse(true,
                List.of(new LocalizationBulkResponse.ResultItem(1L, "설명_한글", "스토리_한글")));
        when(responseSpec.body(LocalizationBulkResponse.class)).thenReturn(mockResponse);

        // [When] 청크 단위 한글화 처리 로직 실행
        localizationService.processLocalizationChunk(10);

        // [Then] 응답 데이터 기반 엔티티 더티 체킹 갱신 로직 정상 호출 확인
        verify(mockGame, times(1)).updateLocalization("설명_한글", "스토리_한글");
    }

    @Test
    @DisplayName("미번역 대상이 없을 경우 외부 API 통신 없이 로직 조기 종료 확인")
    void processLocalizationChunk_NoData_SkipApiCall() {
        // [Given] 미번역 데이터 0건 조회 상황 세팅
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(Collections.emptyList());

        // [When] 청크 단위 한글화 처리 로직 실행
        int result = localizationService.processLocalizationChunk(10);

        // [Then] 반환 건수 0건 확인 및 외부 API 통신 미발생 검증
        assertThat(result).isEqualTo(0);
        verify(localizationRestClient, never()).post();
    }

    @Test
    @DisplayName("통신은 성공했으나 AI 논리적 에러(success=false) 반환 시 실패 작업 기록 확인")
    void processLocalizationChunk_LogicalFailure_RecordFailure() {
        // [Given] 한글화 대상 미번역 게임 데이터 세팅
        Game mockGame = mock(Game.class);
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(List.of(mockGame));

        // [Given] HTTP 통신은 성공이나 논리적 실패(success=false) 응답 상황 모킹
        LocalizationBulkResponse mockResponse = new LocalizationBulkResponse(false, null);
        when(responseSpec.body(LocalizationBulkResponse.class)).thenReturn(mockResponse);

        // [When] 청크 단위 한글화 처리 로직 실행
        int result = localizationService.processLocalizationChunk(10);

        // [Then] 논리적 에러를 통신 장애와 동일하게 취급하여 실패 이력 테이블 적재 검증
        assertThat(result).isEqualTo(1);
        verify(failedTaskRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("파이프라인 실행 중 처리할 데이터가 소진되면 무한 루프를 정상 탈출하는지 확인")
    void runPipeline_DataExhausted_BreakLoop() {
        // [Given] 1회차 데이터 존재, 2회차 데이터 소진(루프 탈출 조건) 상황 세팅
        Game mockGame = mock(Game.class);
        when(gameRepository.findUnlocalizedGames(10))
                .thenReturn(List.of(mockGame))
                .thenReturn(Collections.emptyList());

        // [Given] API 통신 성공 응답 모킹
        LocalizationBulkResponse mockResponse = new LocalizationBulkResponse(true, List.of());
        when(responseSpec.body(LocalizationBulkResponse.class)).thenReturn(mockResponse);

        // [When] 한글화 파이프라인 무한 루프 실행
        localizationService.runPipeline(10);

        // [Then] 데이터 소진 감지 후 루프 탈출 여부(DB 조회 2회, API 호출 1회) 검증
        verify(gameRepository, times(2)).findUnlocalizedGames(10);
        verify(localizationRestClient, times(1)).post();
    }
}