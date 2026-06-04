package io.github.seoleeder.owls_pick.service.genai.localization;

import io.github.seoleeder.owls_pick.dto.response.LocalizationBulkResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.GameRepository;
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
        // TransactionTemplate 콜백 실행 모킹
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        // GenaiProperties 초기화 및 주입
        lenient().when(genaiProperties.fastapiUrl()).thenReturn("http://localhost:8000");
        GenaiProperties.Localization.ChunkSize chunkSize = new GenaiProperties.Localization.ChunkSize(10, 100);
        GenaiProperties.Localization localization = new GenaiProperties.Localization(chunkSize);
        lenient().when(genaiProperties.localization()).thenReturn(localization);

        // RestClient 체이닝 명시적 조립 및 모킹
        lenient().when(localizationRestClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("FastAPI 통신 장애 시 커스텀 예외 발생 확인")
    void processLocalizationChunk_CommunicationError_ThrowException() {
        // [Given] 미번역 데이터 존재 상황 세팅
        Game mockGame = mock(Game.class);
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(List.of(mockGame));

        // [Given] API 통신 실패(Timeout) 상황 모킹
        when(responseSpec.body(LocalizationBulkResponse.class))
                .thenThrow(new RestClientException("Connection Timeout"));

        // [When & Then] 통신 실패를 감지하여 커스텀 예외 발생 유무 확인
        assertThatThrownBy(() -> localizationService.processLocalizationChunk(10))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.FASTAPI_COMMUNICATION_FAILED.getMessage());
    }

    @Test
    @DisplayName("정상 응답 시 게임 엔티티의 한글화 상태 업데이트 로직 호출 확인")
    void processLocalizationChunk_Success_UpdateEntity() {
        // [Given] 미번역 게임 엔티티 세팅
        Game mockGame = mock(Game.class);
        when(mockGame.getId()).thenReturn(1L);
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(List.of(mockGame));

        // [Given] API 통신 성공 및 정상 번역 결과 응답 모킹
        LocalizationBulkResponse mockResponse = new LocalizationBulkResponse(true,
                List.of(new LocalizationBulkResponse.ResultItem(1L, "설명_한글", "스토리_한글")));
        when(responseSpec.body(LocalizationBulkResponse.class)).thenReturn(mockResponse);

        // [When] 청크 단위 한글화 처리 로직 실행
        localizationService.processLocalizationChunk(10);

        // [Then] 응답 데이터 기반 엔티티 갱신 및 DB 저장 로직 정상 호출 확인
        verify(mockGame, times(1)).updateLocalization("설명_한글", "스토리_한글");
        verify(gameRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("미번역 대상이 없을 경우 외부 API 통신 없이 로직 조기 종료 확인")
    void processLocalizationChunk_NoData_SkipApiCall() {
        // [Given] 미번역 데이터 0건 조회 상황 세팅
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(Collections.emptyList());

        // [When] 청크 단위 한글화 처리 로직 실행
        int result = localizationService.processLocalizationChunk(10);

        // [Then] 반환 건수 0건 확인 및 외부 API(RestClient) 미호출 확인
        assertThat(result).isEqualTo(0);
        verify(localizationRestClient, never()).post();
    }

    @Test
    @DisplayName("통신은 성공했으나 AI 논리적 실패(success=false) 반환 시 예외 발생 확인")
    void processLocalizationChunk_LogicalFailure_ThrowException() {
        // [Given] 미번역 게임 엔티티 세팅
        Game mockGame = mock(Game.class);
        when(gameRepository.findUnlocalizedGames(10)).thenReturn(List.of(mockGame));

        // [Given] HTTP 통신은 성공이나 논리적 실패(success=false) 반환 상황 모킹
        LocalizationBulkResponse mockResponse = new LocalizationBulkResponse(false, null);
        when(responseSpec.body(LocalizationBulkResponse.class)).thenReturn(mockResponse);

        // [When & Then] 논리적 실패를 통신 예외로 변환하여 발생시키는지 확인
        assertThatThrownBy(() -> localizationService.processLocalizationChunk(10))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.FASTAPI_COMMUNICATION_FAILED.getMessage());
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