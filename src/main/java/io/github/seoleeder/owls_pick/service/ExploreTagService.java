package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.response.GameResponse;
import io.github.seoleeder.owls_pick.dto.response.TagResponse;
import io.github.seoleeder.owls_pick.entity.game.enums.GameSortType;
import io.github.seoleeder.owls_pick.entity.game.enums.GenreType;
import io.github.seoleeder.owls_pick.entity.game.enums.ThemeType;
import io.github.seoleeder.owls_pick.global.util.GameResponseConverter;
import io.github.seoleeder.owls_pick.global.util.IgdbImageUrlProvider;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.dto.GameWithReviewStatDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExploreTagService {

    private final GameRepository gameRepository;
    private final GameResponseConverter responseConverter;

    // 메인 페이지 노출용 메서드

    /**
     * 인기 장르 태그 조회 (isPopular 기반)
     */
    public List<TagResponse> getPopularGenres() {
        return GenreType.getPopular().stream()
                .map(TagResponse::from)
                .toList();
    }

    /**
     * 전체 장르 태그 조회
     */
    public List<TagResponse> getAllGenres() {
        return Arrays.stream(GenreType.values())
                .map(TagResponse::from)
                .toList();
    }

    /**
     * 인기 테마 태그 조회 (isPopular 기반)
     */
    public List<TagResponse> getPopularThemes() {
        return ThemeType.getPopular().stream()
                .map(TagResponse::from)
                .toList();
    }

    /**
     * 전체 테마 태그 조회
     */
    public List<TagResponse> getAllThemes() {
        return Arrays.stream(ThemeType.values())
                .map(TagResponse::from)
                .toList();
    }


    // 게임 리스트 조회 및 DTO 변환

    /**
     * 특정 장르의 게임 조회 (Pagination, 정렬 조건 적용) -> 게임 응답 DTO 변환
     * */
    public Page<GameResponse> getGamesByGenre(GenreType genre, GameSortType sort, Pageable pageable) {
        log.debug("[Explore] Fetching games by genre: {}, sort: {}, page: {}", genre, sort, pageable.getPageNumber());
        Page<GameWithReviewStatDto> genrePage = gameRepository.findGamesByGenre(genre, sort, pageable);
        return responseConverter.convertPage(genrePage);
    }

    /**
     * 특정 장르의 게임 조회 (Pagination, 정렬 조건 적용) -> 게임 응답 DTO 변환
     * */
    public Page<GameResponse> getGamesByTheme(ThemeType theme, GameSortType sort, Pageable pageable) {
        log.debug("[Explore] Fetching games by theme: {}, sort: {}, page: {}", theme, sort, pageable.getPageNumber());
        Page<GameWithReviewStatDto> themePage = gameRepository.findGamesByTheme(theme, sort, pageable);
        return responseConverter.convertPage(themePage);
    }

}