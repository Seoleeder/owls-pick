package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.dto.response.ReviewSummaryResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
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
public class ReviewSummaryServiceTest {

    @InjectMocks
    private ReviewSummaryService reviewSummaryService;

    @Mock
    private ReviewStatRepository reviewStatRepository;

    @Mock
    private ReviewRepository reviewRepository;

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
    private GenaiProperties genaiProperties;

    @BeforeEach
    void setUp() {
        // 비동기 스레드 풀(AsyncTaskExecutor)을 동기적으로 실행하도록 모킹
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

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
    @DisplayName("리뷰 데이터 부재 시 외부 API 통신 생략 및 스킵 플래그 업데이트 확인")
    void processSingleBatch_EmptyReviews_MarkInsufficientData() {
        // [Given] 리뷰 요약 대상 통계 데이터 및 리뷰 텍스트 부재 상황 세팅
        ReviewStat mockStat = mock(ReviewStat.class);
        Game mockGame = mock(Game.class);
        when(mockStat.getGame()).thenReturn(mockGame);
        when(mockGame.getId()).thenReturn(1L);

        when(reviewStatRepository.findTargetsWithoutSummary(10, 100)).thenReturn(List.of(mockStat));
        when(reviewRepository.findReviewTextsByGameId(1L)).thenReturn(Collections.emptyList());

        // [When] 리뷰 요약 배치 로직 실행
        reviewSummaryService.processSingleBatch(100);

        // [Then] 외부 통신(API) 생략 및 무한 재조회 방지용 INSUFFICIENT_DATA 플래그 갱신 호출 검증
        verify(restClient, never()).post();
        verify(mockStat, times(1)).updateReviewSummary(ReviewSummaryService.INSUFFICIENT_DATA_FLAG, null, null);
        verify(reviewStatRepository, times(1)).save(mockStat);
    }

    @Test
    @DisplayName("AI 엔진 요약 실패 반환 시 스킵 플래그 업데이트 확인")
    void processSingleBatch_SummaryFailedResponse_MarkSummaryFailed() {
        // [Given] 정상 텍스트 조회 상황 세팅
        ReviewStat mockStat = mock(ReviewStat.class);
        Game mockGame = mock(Game.class);
        when(mockStat.getGame()).thenReturn(mockGame);
        when(mockGame.getId()).thenReturn(1L);

        when(reviewStatRepository.findTargetsWithoutSummary(10, 100)).thenReturn(List.of(mockStat));
        when(reviewRepository.findReviewTextsByGameId(1L)).thenReturn(List.of("리뷰 내용"));

        // [Given] 외부 통신은 성공했으나 AI 내부 생성 실패 플래그 수신 모킹
        ReviewSummaryResponse failedResponse = new ReviewSummaryResponse(ReviewSummaryService.SUMMARY_FAILED_FLAG, null, null);
        when(responseSpec.body(eq(ReviewSummaryResponse.class))).thenReturn(failedResponse);

        // [When] 리뷰 요약 배치 로직 실행
        reviewSummaryService.processSingleBatch(100);

        // [Then] 무한 재조회 방지용 SUMMARY_FAILED 플래그 갱신 호출 검증
        verify(mockStat, times(1)).updateReviewSummary(ReviewSummaryService.SUMMARY_FAILED_FLAG, null, null);
        verify(reviewStatRepository, times(1)).save(mockStat);
    }

    @Test
    @DisplayName("비동기 병렬 처리 중 단일 작업 예외 발생 시 전체 배치 유지 확인 (예외 격리)")
    void processSingleBatch_ExceptionIsolation_ContinueProcessing() {
        // [Given] 2건의 대상 데이터 세팅
        ReviewStat stat1 = mock(ReviewStat.class);
        ReviewStat stat2 = mock(ReviewStat.class);
        Game game1 = mock(Game.class);
        Game game2 = mock(Game.class);
        when(stat1.getGame()).thenReturn(game1);
        when(stat2.getGame()).thenReturn(game2);
        when(game1.getId()).thenReturn(1L);
        when(game2.getId()).thenReturn(2L);

        when(reviewStatRepository.findTargetsWithoutSummary(10, 100)).thenReturn(List.of(stat1, stat2));

        // [Given] 1번 데이터 처리 중 런타임 예외 발생 모킹 및 2번 데이터 정상 스킵 상황 세팅
        when(reviewRepository.findReviewTextsByGameId(1L)).thenThrow(new RuntimeException("DB Error"));
        when(reviewRepository.findReviewTextsByGameId(2L)).thenReturn(Collections.emptyList());

        // [When] 리뷰 요약 배치 로직 실행
        int processedCount = reviewSummaryService.processSingleBatch(100);

        // [Then] 예외 발생 건수로 인해 스레드가 종료되지 않고 정상 처리 건수(2번)가 누적됨을 검증
        assertThat(processedCount).isEqualTo(2);
        verify(stat2, times(1)).updateReviewSummary(ReviewSummaryService.INSUFFICIENT_DATA_FLAG, null, null);
    }

    @Test
    @DisplayName("파이프라인 실행 중 처리할 데이터가 소진되면 무한 루프를 정상 탈출하는지 확인")
    void runPipeline_DataExhausted_BreakLoop() {
        // [Given] 1회차 데이터 존재, 2회차 데이터 소진(루프 탈출 조건) 상황 세팅
        ReviewStat mockStat = mock(ReviewStat.class);
        Game mockGame = mock(Game.class);
        when(mockStat.getGame()).thenReturn(mockGame);
        when(mockGame.getId()).thenReturn(1L);

        when(reviewStatRepository.findTargetsWithoutSummary(anyInt(), anyInt()))
                .thenReturn(List.of(mockStat))
                .thenReturn(Collections.emptyList());

        // [When] 무한 루프 파이프라인 실행
        reviewSummaryService.runPipeline(100);

        // [Then] 데이터 소진 감지 후 루프 탈출 여부(DB 조회 2회) 검증
        verify(reviewStatRepository, times(2)).findTargetsWithoutSummary(anyInt(), anyInt());
    }
}