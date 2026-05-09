package io.github.seoleeder.owls_pick.service.client.steam;

import io.github.seoleeder.owls_pick.client.steam.SteamDataCollector;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewDetailResponse.SteamReviewDetail;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewStatsResponse.SteamReviewStats;
import io.github.seoleeder.owls_pick.global.config.properties.CurationProperties;
import io.github.seoleeder.owls_pick.global.config.properties.SteamProperties;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.ReviewRepository;
import io.github.seoleeder.owls_pick.repository.ReviewStatRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SteamReviewSyncServiceTest {

    private SteamReviewSyncService steamReviewSyncService;

    @Mock private SteamDataCollector collector;
    @Mock private StoreDetailRepository storeDetailRepository;
    @Mock private ReviewStatRepository reviewStatRepository;
    @Mock private GameRepository gameRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private TransactionTemplate transactionTemplate;

    /**
     * 테스트 메서드 실행 전에 실행
     * Mock 객체 + properties 생성자 주입 (수동)
     * */
    @BeforeEach
    void setUp() {
        SteamProperties props = new SteamProperties(
                null,
                null,
                new SteamProperties.Sync(20), // threadPoolSize
                new SteamProperties.Review(5, 200, 200, 3, 50), // minVotesUp, init, maintenance
                null
        );

        CurationProperties curationProps = new CurationProperties(
                new CurationProperties.Upcoming(10,2),
                new CurationProperties.Intersection(10),
                new CurationProperties.HiddenMasterpiece(50, 3000, 8),
                new CurationProperties.Trending(7, 8),
                new CurationProperties.ShortPlaytime(600, 8)
        );

        steamReviewSyncService = new SteamReviewSyncService(
                collector,
                storeDetailRepository,
                reviewStatRepository,
                reviewRepository,
                gameRepository,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                transactionTemplate,
                props,
                curationProps
        );

        // 트랜잭션 템플릿 내부 로직 실행 보장 (lenient: 미호출 시 Stubbing 에러 방지)
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0); //람다식 로직을 인자에서 꺼냄
            callback.accept(null); // 로직을 바로 실행
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("리뷰가 없는 게임을 조회하여 API 호출 후 저장")
    void initAllReviews_Success() {
        // Given
        // 1. 대상 게임 데이터 준비
        Game game = Game.builder().id(1L).title("Elden Ring").build();
        StoreDetail detail = StoreDetail.builder().game(game).storeAppId("12345").build();

        // 2. 루프 제어 모킹 (첫 번째 호출엔 데이터 있음 -> 두 번째 호출엔 빈 리스트로 루프 종료)
        given(storeDetailRepository.findGamesWithNoReviews(any(), anyInt()))
                .willReturn(List.of(detail))
                .willReturn(Collections.emptyList());

        // 3. API 응답 데이터 준비
        SteamReviewStats stats = new SteamReviewStats(95, "Overwhelmingly Positive", 1100, 1050, 50);
        SteamReviewDetail reviewDetail = new SteamReviewDetail(100L, new SteamReviewDetail.Author(7), new BigDecimal(6.7), "Nice Game!", 10, true, Instant.now().getEpochSecond());
        SteamReviewResponse response = new SteamReviewResponse(stats, List.of(reviewDetail));

        given(collector.collectRefinedReviews(12345L)).willReturn(response);

        // 5. 기존 통계/리뷰 존재 여부 (신규 저장 시나리오)
        given(reviewStatRepository.findById(1L)).willReturn(Optional.empty());
        given(reviewRepository.existsByGameIdAndRecommendationId(1L, 100L)).willReturn(false);

        // When
        steamReviewSyncService.initAllReviews();

        // Then
        // 1. 리뷰 통계(ReviewStat) 저장 호출 검증
        verify(reviewStatRepository, times(1)).save(any(ReviewStat.class));

        // 2. 리뷰 상세(Review) 저장 호출 검증
        verify(reviewRepository, times(1)).saveAll(anyList());

        // 3. 주간 리뷰 수 갱신 호출 검증
        verify(reviewStatRepository, times(1)).updateWeeklyReviewCount(eq(1L), any());
    }

    @Test
    @DisplayName("리뷰 통계가 이미 존재 -> updateStats를 호출, DB에 없는 리뷰만 저장")
    void initReviews_UpdateStatsAndSaveNew() {
        // Given
        Game game = Game.builder().id(1L).title("Dark Souls").build();
        StoreDetail detail = StoreDetail.builder().game(game).storeAppId("999").build();

        given(storeDetailRepository.findGamesWithNoReviews(any(), anyInt()))
                .willReturn(List.of(detail))
                .willReturn(Collections.emptyList());

        // API 응답 (리뷰 2개: ID "A"(중복), ID "B"(신규))
        SteamReviewStats stats = new SteamReviewStats(90, "Very Positive", 550, 500, 50);
        SteamReviewDetail reviewA = new SteamReviewDetail(170L, new SteamReviewDetail.Author(7), new BigDecimal(1.2), "Good!", 5, true, Instant.now().getEpochSecond());
        SteamReviewDetail reviewB = new SteamReviewDetail(180L, new SteamReviewDetail.Author(8), new BigDecimal(3.4), "Best Driver!", 8, true, Instant.now().getEpochSecond());

        given(collector.collectRefinedReviews(999L))
                .willReturn(new SteamReviewResponse(stats, List.of(reviewA, reviewB)));

        // Mock 객체(ReviewStat) 준비 -> updateStats 호출 여부 확인용
        ReviewStat mockStat = mock(ReviewStat.class);
        given(reviewStatRepository.findById(1L)).willReturn(Optional.of(mockStat));

        // 리뷰 중복 체크 ("A"는 있고, "B"는 없음)
        given(reviewRepository.existsByGameIdAndRecommendationId(1L, 170L)).willReturn(true);
        given(reviewRepository.existsByGameIdAndRecommendationId(1L, 180L)).willReturn(false);

        // When
        steamReviewSyncService.initAllReviews();

        // Then
        // 1. 기존 통계 객체의 updateStats 메서드가 호출되었는지 확인
        verify(mockStat).updateStats(90, "Very Positive", 550, 500, 50);

        // 2. 중복이 아닌 리뷰("B")만 담아서 saveAll이 호출되었는지 확인
        verify(reviewRepository).saveAll(argThat(list -> ((Collection<?>) list).size() == 1));

        // 3. 주간 리뷰 수 갱신 호출 검증
        verify(reviewStatRepository, times(1)).updateWeeklyReviewCount(eq(1L), any());
    }

    @Test
    @DisplayName("[정기 업데이트] 기존 통계는 갱신하고, 신규 리뷰는 저장")
    void syncReviews_UpdateStats_And_SaveNewReviews() {
        // 1. Given: 기존 데이터 세팅
        Long gameId = 1L;
        Game mockGame = Game.builder().id(gameId).title("Elden Ring").build();
        StoreDetail detail = StoreDetail.builder().game(mockGame).storeAppId("12345").build();

        // 갱신 이전의 리뷰 통계 데이터
        ReviewStat existingStat = ReviewStat.builder()
                .game(mockGame)
                .reviewScore(50)
                .totalReview(100)
                .totalPositive(50)
                .totalNegative(50)
                .build();

        // 최신 통계 데이터 수집
        SteamReviewStats newApiStats = new SteamReviewStats(90, "Very Positive", 500, 450, 50);

        // 내부에서 필터링된 새로운 리뷰 수집
        SteamReviewDetail newReview = new SteamReviewDetail(
                999L, new SteamReviewDetail.Author(1), new BigDecimal(10), "Great!",
                160, true, 1600000000L
        );

        SteamReviewResponse mockResponse = new SteamReviewResponse(newApiStats, List.of(newReview));

        // Mocking
        when(storeDetailRepository.findGamesNeedingReviewUpdate(any(), anyInt()))
                .thenReturn(List.of(detail));

        when(collector.collectRefinedReviews(anyLong()))
                .thenReturn(mockResponse); // 리뷰가 포함된 응답 리턴

        when(reviewStatRepository.findById(gameId))
                .thenReturn(Optional.of(existingStat)); // 기존 통계 발견

        // 중복 체크: 999번 리뷰는 DB에 없다(false)고 가정 -> 저장 대상
        lenient().when(reviewRepository.existsByGameIdAndRecommendationId(eq(gameId), eq(999L)))
                .thenReturn(false);

        // 2. When
        steamReviewSyncService.syncReviews();

        // 3. Then
        // 통계 업데이트 (Dirty Checking) 확인
        // save() 호출 없이 객체의 값 자체가 50 -> 90으로 변했는지 확인
        assertThat(existingStat.getReviewScore()).isEqualTo(90);
        assertThat(existingStat.getTotalReview()).isEqualTo(500);

        // 신규 리뷰 저장 확인
        // 통계는 update지만, 리뷰는 insert가 일어나야 함
        verify(reviewRepository).saveAll(argThat(list -> {
            List<?> reviews = (List<?>) list;
            return reviews.size() == 1;
        }));

        // 주간 리뷰 수 갱신이 트랜잭션 내부에서 잘 일어났는지 검증
        verify(reviewStatRepository, times(1)).updateWeeklyReviewCount(eq(gameId), any());
    }
}