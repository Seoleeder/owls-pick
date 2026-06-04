package io.github.seoleeder.owls_pick.service.client.hltb;

import io.github.seoleeder.owls_pick.dto.response.HltbSyncResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.Playtime;
import io.github.seoleeder.owls_pick.entity.game.enums.status.SyncStatus;
import io.github.seoleeder.owls_pick.global.config.properties.HltbProperties;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.PlaytimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HltbSyncServiceTest {

    private HltbSyncService hltbSyncService;

    @Mock
    private PlaytimeRepository playtimeRepository;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    // RestClient 체이닝 분리를 위한 중간 객체들
    @Mock
    private RestClient hltbRestClient;

    @Mock
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private RestClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Captor private ArgumentCaptor<Playtime> playtimeCaptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        HltbProperties properties = new HltbProperties("http://localhost:8000", 100);

        // 비동기 처리(CompletableFuture) 제어를 위한 SyncTaskExecutor 주입
        hltbSyncService = new HltbSyncService(
                playtimeRepository, gameRepository, hltbRestClient, properties,
                transactionTemplate, new TaskExecutorAdapter(new SyncTaskExecutor())
        );

        // 반환형이 없는 TransactionTemplate 콜백 내부 로직 강제 실행 모킹
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // RestClient 메서드 체이닝 명시적 조립 및 모킹
        lenient().when(hltbRestClient.get()).thenReturn(requestHeadersUriSpec);
        lenient().when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("[HLTB 동기화] 정상 데이터 수집 시 Playtime 엔티티 병합 및 SUCCESS 상태 저장 확인")
    void runSingleBatchSync_Success() {
        // [Given] 동기화 대상 게임 1건 조회 결과 세팅
        Game game = Game.builder().id(1L).title("Test Game").build();
        given(playtimeRepository.findGamesWithUnsyncedPlaytime(anyInt())).willReturn(List.of(game));

        // [Given] DB 내 기존 Playtime 엔티티가 없는 상태 모킹
        given(playtimeRepository.findById(1L)).willReturn(Optional.empty());
        given(gameRepository.getReferenceById(1L)).willReturn(game);

        // [Given] HLTB 응답 객체 모킹 (DTO 생성자 내에서 시간 -> 분 단위 변환 처리됨)
        HltbSyncResponse mockResponse = new HltbSyncResponse(SyncStatus.SUCCESS, 10.0, 20.0, 30.0);
        given(responseSpec.body(HltbSyncResponse.class)).willReturn(mockResponse);

        // [When] 단건 청크 단위 수집 로직 실행
        int processedCount = hltbSyncService.runSingleBatchSync(100);

        // [Then] 대상 반환 카운트 확인 및 수집된 데이터(분 단위)와 SUCCESS 상태 매핑 검증
        assertThat(processedCount).isEqualTo(1);
        verify(playtimeRepository, times(1)).save(playtimeCaptor.capture());

        Playtime savedPlaytime = playtimeCaptor.getValue();
        assertThat(savedPlaytime.getMainStory()).isEqualTo(600);
        assertThat(savedPlaytime.getCompletionist()).isEqualTo(1800);
        assertThat(savedPlaytime.getSyncStatus()).isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    @DisplayName("[데이터 보정] API 응답이 SUCCESS라도 시간 데이터 부재 시 NO_DATA 상태 보정 검증")
    void runSingleBatchSync_Status_Correction_To_NoData() {
        // [Given] 동기화 대상 게임 세팅
        Game game = Game.builder().id(2L).title("Empty Game").build();
        given(playtimeRepository.findGamesWithUnsyncedPlaytime(anyInt())).willReturn(List.of(game));
        given(playtimeRepository.findById(2L)).willReturn(Optional.empty());
        given(gameRepository.getReferenceById(2L)).willReturn(game);

        // [Given] FastAPI가 SUCCESS를 응답했으나, 실제 시간 데이터는 모두 Null인 예외 상황 모킹
        HltbSyncResponse emptyResponse = new HltbSyncResponse(SyncStatus.SUCCESS, null, null, null);
        given(responseSpec.body(HltbSyncResponse.class)).willReturn(emptyResponse);

        // [When] 단건 청크 단위 동기화 로직 실행
        hltbSyncService.runSingleBatchSync(100);

        // [Then] DB 저장 시 데이터 무결성을 위해 상태값이 SUCCESS에서 NO_DATA로 강제 보정되었는지 검증
        verify(playtimeRepository, times(1)).save(playtimeCaptor.capture());
        assertThat(playtimeCaptor.getValue().getSyncStatus()).isEqualTo(SyncStatus.NO_DATA);
    }

    @Test
    @DisplayName("[예외 처리] 통신 장애 발생 시 스레드 종료 방어 및 FAILED 상태 마킹 검증")
    void runSingleBatchSync_Fault_Tolerance_Failed_Status() {
        // [Given] 동기화 대상 게임 세팅
        Game game = Game.builder().id(3L).title("Error Game").build();
        given(playtimeRepository.findGamesWithUnsyncedPlaytime(anyInt())).willReturn(List.of(game));
        given(playtimeRepository.findById(3L)).willReturn(Optional.empty());
        given(gameRepository.getReferenceById(3L)).willReturn(game);

        // [Given] FastAPI 타임아웃 예외 상황(RestClientException) 모킹
        given(responseSpec.body(HltbSyncResponse.class)).willThrow(new RestClientException("FastAPI Timeout"));

        // [When] 단건 청크 단위 동기화 로직 실행
        hltbSyncService.runSingleBatchSync(100);

        // [Then] 예외 발생 시 비동기 스레드가 종료되지 않고, FAILED 상태로 DB 업데이트가 이루어지는지 검증
        verify(playtimeRepository, times(1)).save(playtimeCaptor.capture());
        assertThat(playtimeCaptor.getValue().getSyncStatus()).isEqualTo(SyncStatus.FAILED);
    }

    @Test
    @DisplayName("[파이프라인] 전체 배치 동작 시 빈 리스트 반환에 따른 루프 정상 종료 검증")
    void runSyncPipeline_Complete_Execution() {
        // [Given] 1회차 정상 리스트 반환, 2회차 조회 시 빈 리스트를 반환하여 무한루프 종료 조건 모킹
        Game game = Game.builder().id(4L).title("Pipeline Game").build();
        given(playtimeRepository.findGamesWithUnsyncedPlaytime(anyInt()))
                .willReturn(List.of(game))
                .willReturn(Collections.emptyList());

        given(playtimeRepository.findById(4L)).willReturn(Optional.empty());
        given(gameRepository.getReferenceById(4L)).willReturn(game);

        HltbSyncResponse mockResponse = new HltbSyncResponse(SyncStatus.SUCCESS, 10.0, 20.0, 30.0);
        given(responseSpec.body(HltbSyncResponse.class)).willReturn(mockResponse);

        // [When] 무한 루프(while) 기반 전체 파이프라인 수집 로직 실행
        hltbSyncService.runSyncPipeline(100);

        // [Then] 대상 조회 로직이 총 2회 호출된 후 무한 루프를 탈출하여 메서드가 종료되었는지 검증
        verify(playtimeRepository, times(2)).findGamesWithUnsyncedPlaytime(100);
        verify(playtimeRepository, times(1)).save(any(Playtime.class));
    }
}