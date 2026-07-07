package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.dto.response.ReviewSummaryResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.repository.GenaiFailedTaskRepository;
import io.github.seoleeder.owls_pick.repository.ReviewRepository;
import io.github.seoleeder.owls_pick.repository.ReviewStatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReviewSummaryServiceTest {

    @InjectMocks
    private ReviewSummaryService reviewSummaryService;

    @Mock
    private ReviewStatRepository reviewStatRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private GenaiFailedTaskRepository failedTaskRepository;

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

    @BeforeEach
    void setUp() {
        // 비동기 스레드 풀(AsyncTaskExecutor)을 동기적으로 실행하도록 모킹
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        // 트랜잭션 템플릿의 리턴 유무에 따른 콜백 실행 모킹
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        // 프로퍼티 인스턴스 초기화 및 주입
        lenient().when(genaiProperties.fastapiUrl()).thenReturn("http://localhost:8000");
        GenaiProperties.Review reviewProps = new GenaiProperties.Review(10, 100, 5);
        lenient().when(genaiProperties.review()).thenReturn(reviewProps);

        // RestClient 체이닝 명시적 조립 및 모킹
        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("리뷰 데이터 부재 시 외부 API 통신 생략 및 데이터 부족 실패 이력(FailedTask) 적재 확인")
    void processSingleBatch_EmptyReviews_RecordFailure() {
        // [Given] 리뷰 요약 대상 통계 데이터 및 리뷰 텍스트 부재 상황 세팅
        ReviewStat mockStat = mock(ReviewStat.class);
        Game mockGame = mock(Game.class);
        when(mockStat.getGame()).thenReturn(mockGame);
        when(mockGame.getId()).thenReturn(1L);

        when(reviewStatRepository.findTargetsWithoutSummary(anyInt(), anyInt())).thenReturn(List.of(mockStat));
        when(reviewRepository.findReviewTextsByGameId(1L)).thenReturn(Collections.emptyList());

        // [When] 리뷰 요약 단일 배치 로직 실행
        reviewSummaryService.processSingleBatch(10);

        // [Then] 외부 통신(API) 생략 및 데이터 무결성을 위해 매직 스트링 대신 실패 이력(INSUFFICIENT_DATA) 적재 검증
        verify(restClient, never()).post();
        verify(failedTaskRepository, times(1)).save(any(GenaiFailedTask.class));
    }

    @Test
    @DisplayName("AI 엔진 요약 실패(비정상 응답) 반환 시 응답 예외 실패 이력 적재 확인")
    void processSingleBatch_SummaryFailedResponse_RecordFailure() {
        // [Given] 정상 텍스트 조회 상황 세팅
        ReviewStat mockStat = mock(ReviewStat.class);
        Game mockGame = mock(Game.class);
        when(mockStat.getGame()).thenReturn(mockGame);
        when(mockGame.getId()).thenReturn(1L);

        when(reviewStatRepository.findTargetsWithoutSummary(anyInt(), anyInt())).thenReturn(List.of(mockStat));
        when(reviewRepository.findReviewTextsByGameId(1L)).thenReturn(List.of("리뷰 내용"));

        // [Given] 외부 통신은 성공했으나 AI 응답 검증(공백 응답 등) 실패 상황 모킹
        ReviewSummaryResponse failedResponse = new ReviewSummaryResponse(null, null, null);
        when(responseSpec.body(eq(ReviewSummaryResponse.class))).thenReturn(failedResponse);

        // [When] 리뷰 요약 단일 배치 로직 실행
        reviewSummaryService.processSingleBatch(10);

        // [Then] 비정상 응답 감지 시 매직 스트링 기록을 멈추고 실패 이력(INVALID_RESPONSE) 적재 로직 호출 검증
        verify(failedTaskRepository, times(1)).save(any(GenaiFailedTask.class));
    }

    @Test
    @DisplayName("비동기 병렬 처리 중 단일 작업 예외 발생 시 전체 배치 유지 확인 (예외 격리)")
    void processSingleBatch_ExceptionIsolation_ContinueProcessing() {
        // [Given] 2건의 요약 대상 데이터 세팅
        ReviewStat stat1 = mock(ReviewStat.class);
        ReviewStat stat2 = mock(ReviewStat.class);
        Game game1 = mock(Game.class);
        Game game2 = mock(Game.class);
        when(stat1.getGame()).thenReturn(game1);
        when(stat2.getGame()).thenReturn(game2);
        when(game1.getId()).thenReturn(1L);
        when(game2.getId()).thenReturn(2L);

        when(reviewStatRepository.findTargetsWithoutSummary(anyInt(), anyInt())).thenReturn(List.of(stat1, stat2));

        // [Given] 1번 데이터 처리 중 런타임 예외 발생 모킹 및 2번 데이터 정상 응답 처리 상황 세팅
        when(reviewRepository.findReviewTextsByGameId(1L)).thenThrow(new RuntimeException("DB Error"));
        when(reviewRepository.findReviewTextsByGameId(2L)).thenReturn(Collections.emptyList());

        // [When] 리뷰 요약 단일 배치 로직 실행
        int processedCount = reviewSummaryService.processSingleBatch(10);

        // [Then] 예외 발생 건수로 인해 스레드가 종료되지 않고 전체 대상 건수(2건)만큼 스레드 할당 완료됨을 검증
        assertThat(processedCount).isEqualTo(2);

        // [Then] 1번 에러 건과 2번 결측치 건 각각에 대해 독립적으로 실패 이력이 적재되는지 검증
        verify(failedTaskRepository, times(2)).save(any(GenaiFailedTask.class));
    }

    @Test
    @DisplayName("파이프라인 실행 중 처리할 데이터가 소진되면 무한 루프를 정상 탈출하는지 확인")
    void runPipeline_DataExhausted_BreakLoop() {
        // [Given] 1회차 데이터 존재, 2회차 데이터 소진(루프 조기 탈출 조건) 상황 세팅
        ReviewStat mockStat = mock(ReviewStat.class);
        Game mockGame = mock(Game.class);
        when(mockStat.getGame()).thenReturn(mockGame);
        when(mockGame.getId()).thenReturn(1L);

        when(reviewStatRepository.findTargetsWithoutSummary(anyInt(), anyInt()))
                .thenReturn(List.of(mockStat))
                .thenReturn(Collections.emptyList());

        // [When] 무한 루프 리뷰 요약 파이프라인 실행
        reviewSummaryService.runPipeline(10);

        // [Then] 대상 데이터 소진 감지 후 루프 탈출 여부(DB 조회 2회 동작) 검증
        verify(reviewStatRepository, times(2)).findTargetsWithoutSummary(anyInt(), anyInt());
    }

    @Test
    @DisplayName("미조치 실패 작업 재시도 시 정상 통신 및 요약 데이터 더티 체킹 갱신 확인")
    void retryFailedTasks_Success() {
        // [Given] 미조치 실패 이력 및 연결된 게임 데이터 세팅
        GenaiFailedTask mockTask = mock(GenaiFailedTask.class);
        when(mockTask.getId()).thenReturn(100L);
        when(mockTask.getTargetId()).thenReturn(1L); // Game ID

        when(failedTaskRepository.findUnhandledTasks(GenaiPipelineType.STEAM_REVIEW_SUMMARY))
                .thenReturn(List.of(mockTask));

        ReviewStat mockStat = mock(ReviewStat.class);
        when(mockStat.getId()).thenReturn(1L);
        when(reviewStatRepository.findById(1L)).thenReturn(Optional.of(mockStat));
        when(reviewRepository.findReviewTextsByGameId(1L)).thenReturn(List.of("갓겜입니다."));

        // [Given] AI 엔진의 재시도 정상 요약 응답 모킹
        ReviewSummaryResponse mockResponse = new ReviewSummaryResponse("요약", List.of("긍정"), List.of("부정"));
        when(responseSpec.body(eq(ReviewSummaryResponse.class))).thenReturn(mockResponse);

        // 데이터 무결성을 위한 원본 엔티티 재조회 모킹
        when(reviewStatRepository.findById(10L)).thenReturn(Optional.of(mockStat));
        when(failedTaskRepository.findById(100L)).thenReturn(Optional.of(mockTask));

        // [When] 실패 작업 복구 파이프라인 실행
        reviewSummaryService.retryFailedTasks();

        // [Then] 요약 데이터 더티 체킹 갱신 및 실패 이력 조치 완료 상태(markAsHandled) 변경 검증
        verify(mockStat, times(1)).updateReviewSummary("요약", List.of("긍정"), List.of("부정"));
        verify(mockTask, times(1)).markAsHandled();
    }
}