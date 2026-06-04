package io.github.seoleeder.owls_pick.service.client.steam;

import io.github.seoleeder.owls_pick.client.steam.SteamDataCollector;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewDetailResponse.SteamReviewDetail;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewStatsResponse.SteamReviewStats;
import io.github.seoleeder.owls_pick.global.config.properties.CurationProperties;
import io.github.seoleeder.owls_pick.global.config.properties.SteamProperties;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.Review;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Captor
    private ArgumentCaptor<List<Review>> reviewListCaptor;

    @BeforeEach
    void setUp() {
        // 테스트용 Properties 환경 변수 구성
        SteamProperties props = new SteamProperties(
                null, null, new SteamProperties.Sync(20),
                new SteamProperties.Review(5, 200, 200, 3, 50), null
        );

        CurationProperties curationProps = new CurationProperties(
                new CurationProperties.Upcoming(10,2), new CurationProperties.Intersection(10),
                new CurationProperties.HiddenMasterpiece(50, 3000, 8),
                new CurationProperties.Trending(7, 8), new CurationProperties.ShortPlaytime(600, 8)
        );

        // 리뷰 동기화 서비스 의존성 주입 (동기식 테스트를 위한 SyncTaskExecutor 주입)
        steamReviewSyncService = new SteamReviewSyncService(
                collector, storeDetailRepository, reviewStatRepository, reviewRepository,
                gameRepository, new TaskExecutorAdapter(new SyncTaskExecutor()),
                transactionTemplate, props, curationProps
        );

        // TransactionTemplate execute 콜백 내부 람다 실행 모킹
        lenient().doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("리뷰가 없는 게임 수집 시 신규 스탯 생성 및 필터링 리뷰 일괄 삽입 검증")
    void initAllReviews_Success() {
        // [Given] 리뷰 수집 대상 게임 세팅 및 배치 루프 종료 조건 추가
        Game game = Game.builder().id(1L).title("Elden Ring").build();
        StoreDetail detail = StoreDetail.builder().game(game).storeAppId("12345").build();

        given(storeDetailRepository.findGamesWithNoReviews(any(), anyInt()))
                .willReturn(List.of(detail))
                .willReturn(Collections.emptyList());

        // [Given] 스팀 API 신규 리뷰 응답 세팅
        SteamReviewStats stats = new SteamReviewStats(9, "Overwhelmingly Positive", 1100, 1050, 50);
        SteamReviewDetail reviewDetail = new SteamReviewDetail(100L, new SteamReviewDetail.Author(7), new BigDecimal(6.7), "Nice Game!", 10, true, Instant.now().getEpochSecond());
        SteamReviewResponse response = new SteamReviewResponse(stats, List.of(reviewDetail));
        given(collector.collectRefinedReviews(12345L)).willReturn(response);

        // [Given] DB 내 기존 스탯 및 리뷰가 없는 초기 상태 모킹
        given(gameRepository.getReferenceById(1L)).willReturn(game);
        given(reviewStatRepository.findById(1L)).willReturn(Optional.empty());
        given(reviewRepository.findRecommendationIdsByGameId(1L)).willReturn(Collections.emptySet());

        // [When] 초기 대량 리뷰 수집 배치 실행
        steamReviewSyncService.initAllReviews();

        // [Then] 리뷰 스탯 신규 생성(save) 호출 검증
        verify(reviewStatRepository, times(1)).save(any(ReviewStat.class));

        // [Then] 신규 리뷰 1건이 벌크 인서트로 전달되었는지 검증
        verify(reviewRepository, times(1)).bulkInsertReviews(reviewListCaptor.capture());
        List<Review> savedReviews = reviewListCaptor.getValue();
        assertThat(savedReviews).hasSize(1);
        assertThat(savedReviews.get(0).getRecommendationId()).isEqualTo(100L);

        // [Then] 주간 리뷰 수 갱신 쿼리 호출 검증
        verify(reviewStatRepository, times(1)).updateWeeklyReviewCount(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("리뷰 스탯 존재 시 기존 엔티티 갱신 및 신규 식별자 리뷰 필터링 저장 검증")
    void initReviews_UpdateStatsAndSaveNew() {
        // [Given] 대상 게임 세팅 및 루프 종료 조건 추가
        Game game = Game.builder().id(1L).title("Dark Souls").build();
        StoreDetail detail = StoreDetail.builder().game(game).storeAppId("999").build();

        given(storeDetailRepository.findGamesWithNoReviews(any(), anyInt()))
                .willReturn(List.of(detail))
                .willReturn(Collections.emptyList());

        // [Given] API 응답 세팅 (170번: 중복, 180번: 신규)
        SteamReviewStats stats = new SteamReviewStats(90, "Very Positive", 550, 500, 50);
        SteamReviewDetail reviewA = new SteamReviewDetail(170L, new SteamReviewDetail.Author(7), new BigDecimal(1.2), "Good!", 5, true, Instant.now().getEpochSecond());
        SteamReviewDetail reviewB = new SteamReviewDetail(180L, new SteamReviewDetail.Author(8), new BigDecimal(3.4), "Best Driver!", 8, true, Instant.now().getEpochSecond());
        given(collector.collectRefinedReviews(999L))
                .willReturn(new SteamReviewResponse(stats, List.of(reviewA, reviewB)));

        // [Given] DB에 170번 리뷰와 기존 스탯이 존재하는 상태로 모킹
        given(gameRepository.getReferenceById(1L)).willReturn(game);
        ReviewStat mockStat = mock(ReviewStat.class);
        given(reviewStatRepository.findById(1L)).willReturn(Optional.of(mockStat));
        given(reviewRepository.findRecommendationIdsByGameId(1L)).willReturn(Set.of(170L));

        // [When] 리뷰 초기 수집 배치 실행
        steamReviewSyncService.initAllReviews();

        // [Then] 기존 리뷰 스탯의 updateStats 호출 검증
        verify(mockStat).updateStats(90, "Very Positive", 550, 500, 50);

        // [Then] 중복 필터링 후 신규 리뷰(180번) 1건만 벌크 인서트로 전달되었는지 검증
        verify(reviewRepository, times(1)).bulkInsertReviews(reviewListCaptor.capture());
        assertThat(reviewListCaptor.getValue()).hasSize(1);
        assertThat(reviewListCaptor.getValue().get(0).getRecommendationId()).isEqualTo(180L);
    }

    @Test
    @DisplayName("[정기 업데이트] 스케줄링 배치 동작 시 통계 갱신 및 신규 리뷰 일괄 삽입 정상 호출 검증")
    void syncReviews_UpdateStats_And_SaveNewReviews() {
        // [Given] 기존 게임 및 리뷰 스탯 엔티티 세팅
        Game mockGame = Game.builder().id(1L).title("Elden Ring").build();
        StoreDetail detail = StoreDetail.builder().game(mockGame).storeAppId("12345").build();

        ReviewStat existingStat = ReviewStat.builder()
                .game(mockGame).reviewScore(50).totalReview(100).totalPositive(50).totalNegative(50).build();

        // [Given] 최신 API 응답 세팅 (999번 신규 리뷰)
        SteamReviewStats newApiStats = new SteamReviewStats(90, "Very Positive", 500, 450, 50);
        SteamReviewDetail newReview = new SteamReviewDetail(999L, new SteamReviewDetail.Author(1), new BigDecimal(10), "Great!", 160, true, 1600000000L);
        SteamReviewResponse mockResponse = new SteamReviewResponse(newApiStats, List.of(newReview));

        // [Given] 정기 업데이트 대상 조회 및 모킹
        given(storeDetailRepository.findGamesNeedingReviewUpdate(any(), anyInt())).willReturn(List.of(detail));
        given(collector.collectRefinedReviews(12345L)).willReturn(mockResponse);
        given(gameRepository.getReferenceById(1L)).willReturn(mockGame);
        given(reviewStatRepository.findById(1L)).willReturn(Optional.of(existingStat));
        given(reviewRepository.findRecommendationIdsByGameId(1L)).willReturn(Collections.emptySet());

        // [When] 리뷰 정기 업데이트 배치 실행
        steamReviewSyncService.syncReviews();

        // [Then] JPA 더티 체킹을 통한 기존 스탯 값 갱신 검증
        assertThat(existingStat.getReviewScore()).isEqualTo(90);
        assertThat(existingStat.getTotalReview()).isEqualTo(500);

        // [Then] 신규 리뷰 벌크 인서트 전달 검증
        verify(reviewRepository, times(1)).bulkInsertReviews(reviewListCaptor.capture());
        assertThat(reviewListCaptor.getValue()).hasSize(1);

        // [Then] 주간 리뷰 수 갱신 쿼리 호출 확인
        verify(reviewStatRepository, times(1)).updateWeeklyReviewCount(eq(1L), any(LocalDateTime.class));
    }
}