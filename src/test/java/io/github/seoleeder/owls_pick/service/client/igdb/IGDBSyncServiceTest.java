package io.github.seoleeder.owls_pick.service.client.igdb;

import io.github.seoleeder.owls_pick.client.igdb.IgdbDataCollector;
import io.github.seoleeder.owls_pick.client.igdb.dto.IgdbGameDetailResponse;
import io.github.seoleeder.owls_pick.client.igdb.dto.IgdbGameSummaryResponse;
import io.github.seoleeder.owls_pick.global.util.TimestampUtils;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.entity.game.Tag;
import io.github.seoleeder.owls_pick.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IGDBSyncServiceTest {

    @InjectMocks
    private IgdbSyncService igdbSyncService;

    @Mock private IgdbDataCollector collector;
    @Mock private GameRepository gameRepository;
    @Mock private StoreDetailRepository storeDetailRepository;
    @Mock private TagRepository tagRepository;
    @Mock private ScreenshotRepository screenshotRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private GameCompanyRepository gameCompanyRepository;
    @Mock private LanguageSupportRepository languageSupportRepository;

    @Mock private TransactionTemplate transactionTemplate;
    @Test
    @DisplayName("[초기 수집] Steam App Id와 igdbId 매핑 -> igdbId 기반 게임 데이터 수집 저장")
    void backfillAllGames_Success() {
        // Given
        // 마지막으로 수집된 IGDB ID 조회 (최초는 0부터 시작)
        given(gameRepository.findTopByOrderByIgdbIdDesc()).willReturn(Optional.empty());

        // API 응답 (Summary) 데이터 생성
        // ExternalApp: {store app id, store id}
        var externalApp = new IgdbGameSummaryResponse.ExternalApp("100", 1);

        IgdbGameSummaryResponse summary = new IgdbGameSummaryResponse(
                55L,                // igdbId
                List.of(externalApp), // externalApps
                null,               // titleLocalization
                null,               // type
                null,               // gameStatus
                null,               // platforms
                "Test Desc",        // description
                null,               // storyline
                null,               // first_release
                1700000000L,        // updatedAt
                null,               // ageRatings
                null,               // modes
                null,               // perspectives
                null,               // cover
                0                   // hypes
        );

        // 1회차: 데이터 있음, 2회차: 빈 리스트
        given(collector.collectGameSummary(anyLong()))
                .willReturn(List.of(summary))
                .willReturn(Collections.emptyList());

        //TransactionTemplate (execute) 모킹
        doAnswer(inv -> {
            TransactionCallback<List<Game>> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        // TransactionTemplate (executeWithoutResult) 모킹
        doAnswer(inv -> {
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // DB 조회 시나리오
        // Steam AppID가 100인 게임이 DB에 존재해야 업데이트됨
        Game existingGame = Game.builder().id(1L).title("Old Title").build();
        StoreDetail detail = StoreDetail.builder().game(existingGame).storeName(StoreDetail.StoreName.STEAM).storeAppId("100").build();

        given(storeDetailRepository.findByStoreNameAndStoreAppIdIn(any(), anyList()))
                .willReturn(List.of(detail));

        // saveAll 결과
        given(gameRepository.saveAll(anyList())).willReturn(List.of(existingGame));

        // API 응답 (Detail) 생성
        var genre = new IgdbGameDetailResponse.Genre(1L, "RPG");
        IgdbGameDetailResponse detailRes = new IgdbGameDetailResponse(
                55L,                // igdbId (매칭용)
                null,               // externalApps
                List.of(genre),     // genres (검증 대상)
                null,               // themes
                null,               // keywords
                null,               // companies
                null,               // screenshots
                null                // languageSupports
        );
        given(collector.collectGameDetail(anyList())).willReturn(List.of(detailRes));

        // When
        igdbSyncService.backfillAllGames();

        // Then
        // Game 업데이트 검증 (IGDB ID 55번 매핑)
        verify(gameRepository).saveAll(argThat(list -> {
            List<Game> games = (List<Game>) list;
            return games.size() == 1 && games.get(0).getIgdbId() == 55L;
        }));

        // 상세 정보 저장 검증 (Tag 저장 - Genre 확인)
        verify(tagRepository).saveAll(argThat(list -> {
            List<Tag> tags = (List<Tag>) list;
            return !tags.isEmpty() && tags.get(0).getGenres().contains("RPG");
        }));
    }

    @Test
    @DisplayName("[정기 업데이트] '최종 갱신 시각' 이후에 추가 및 변경된 데이터만 수집")
    void syncUpdatedGames_Success() {
        // 1. Given
        LocalDateTime lastUpdate = LocalDateTime.of(2024, 1, 1, 0, 0);
        long newUpdateEpoch = 1704153600L;
        LocalDateTime expectedTime = TimestampUtils.toLocalDateTime(newUpdateEpoch);

        given(gameRepository.findMaxIgdbUpdatedAt()).willReturn(Optional.of(lastUpdate));

        var externalApp = new IgdbGameSummaryResponse.ExternalApp("200", 1);
        IgdbGameSummaryResponse updatedSummary = new IgdbGameSummaryResponse(
                77L, List.of(externalApp), Collections.emptyList(), null, null,
                Collections.emptyList(), "Updated Description",
                null, 1704153600L, newUpdateEpoch,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null, 0
        );

        given(collector.collectUpdatedGameSummary(anyLong()))
                .willReturn(List.of(updatedSummary))
                .willReturn(Collections.emptyList());

        Game existingGame = Game.builder().id(10L).igdbId(77L).description("Old Description").build();
        StoreDetail detail = StoreDetail.builder().game(existingGame).storeAppId("200").build();

        given(storeDetailRepository.findByStoreNameAndStoreAppIdIn(any(), anyList()))
                .willReturn(List.of(detail));

        // execute()가 실제 리스트를 반환하도록 설정 (리턴값이 있어야 if문을 통과함)
        given(transactionTemplate.execute(any())).willAnswer(inv -> {
            TransactionCallback<List<Game>> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        given(gameRepository.saveAll(anyList())).willReturn(List.of(existingGame));

        // 2. When
        igdbSyncService.syncUpdatedGames();

        // 3. Then
        assertThat(existingGame.getDescription()).isEqualTo("Updated Description");
        assertThat(existingGame.getIgdbUpdatedAt()).isEqualTo(expectedTime);
    }
}