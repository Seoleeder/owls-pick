package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.dto.embedding.EmbeddingSourceDto;
import io.github.seoleeder.owls_pick.dto.request.GameSearchConditionRequest;
import io.github.seoleeder.owls_pick.dto.response.SearchFilterMetadataResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.enums.GameSortType;
import io.github.seoleeder.owls_pick.repository.dto.GameDetailCoreDto;
import io.github.seoleeder.owls_pick.repository.dto.GameWithReviewStatDto;
import io.github.seoleeder.owls_pick.entity.game.enums.GenreType;
import io.github.seoleeder.owls_pick.entity.game.enums.ThemeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GameRepositoryCustom {


    // --- 곹통 및 메타데이터 ---

    // 최종 수정 시간 조회
    Optional<LocalDateTime> findMaxIgdbUpdatedAt();

    // 특정 장르 + 테마 조합을 가진 게임 수 집계
    long countGamesByGenreAndTheme(GenreType genre, ThemeType theme);

    // 필터 슬라이더를 위한 메타데이터 조회
    SearchFilterMetadataResponse.PriceRange getPriceRange();

    SearchFilterMetadataResponse.PlaytimeRange getPlaytimeRange();

    // --- 외부 API 게임 데이터 동기화 ---

    // 커서 기반 유효 ITAD ID 보유 게임 목록 조회
    List<Game> findValidGamesWithItadId(Long lastId, int limit);

    // --- 태그 기반 탐색 ---

    // 특정 장르의 게임 조회 (정렬 기준 선택)
    Page<GameWithReviewStatDto> findGamesByGenre(GenreType genre, GameSortType sort, Pageable pageable);

    // 특정 테마의 게임 조회 (정렬 기준 선택)
    Page<GameWithReviewStatDto> findGamesByTheme(ThemeType theme, GameSortType sort, Pageable pageable);


    // --- 맞춤형 Pick 섹션 ---

    // 출시 예정 기대작 게임 조회
    Page<Game> findUpcomingGames(LocalDate today, LocalDate maxDate, int minHypes, Pageable pageable);

    // 선호 태그 기반 사용자 맞춤형 최적의 게임 조회
    Page<GameWithReviewStatDto> findPersonalizedGamesByPreferredTags(List<String> userTags, Pageable pageable);

    // 특정 장르와 특정 테마의 조합을 가진 게임 조회
    Page<GameWithReviewStatDto> findGamesByGenreAndThemeIntersection(GenreType genre, ThemeType theme, Pageable pageable);

    // 리뷰 스코어는 높은데 리뷰 수가 적은 숨겨진 명작 게임 조회
    Page<GameWithReviewStatDto> findHiddenMasterpieces(int minScore, int minReviews, int maxReviews, Pageable pageable);

    // 최근에 리뷰가 많이 달린 특정 태그의 게임 조회
    Page<GameWithReviewStatDto> findTrendingGamesByTag(String tagName, int minScore, Pageable pageable);

    // 플레이 타입이 짧으면서 스코어가 높은 게임 조회
    Page<GameWithReviewStatDto> findShortPlaytimeGamesByTag(String tagName, int maxPlaytime, int minScore, Pageable pageable);


    // --- 통합 검색 ---

    // 통합 검색 쿼리
    Page<GameWithReviewStatDto> searchGames(GameSearchConditionRequest condition, Pageable pageable);


    // --- 게임 상세 조회 ---

    //  게임의 핵심 정보와 1:1 연관 데이터 조회
    Optional<GameDetailCoreDto> findGameDetailCoreById(Long gameId);


    // --- 게임 데이터 한글화 ---

    // 한글화가 필요한 게임을 지정된 limit만큼 조회
    List<Game> findUnlocalizedGames(int limit);

    // --- 벡터 임베딩 ---

    // 벡터 임베딩이 필요한 게임 조회
    List<EmbeddingSourceDto> findGamesForEmbedding(int dbFetchSize);
}
