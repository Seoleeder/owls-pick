package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.request.GameSearchConditionRequest;
import io.github.seoleeder.owls_pick.dto.response.GameResponse;
import io.github.seoleeder.owls_pick.dto.response.SearchFilterMetadataResponse;
import io.github.seoleeder.owls_pick.entity.game.enums.GenreType;
import io.github.seoleeder.owls_pick.entity.game.enums.ThemeType;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.global.util.GameResponseConverter;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.dto.GameWithReviewStatDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameSearchService {

    private final GameRepository gameRepository;
    private final GameResponseConverter gameResponseConverter;

    /**
     * 검색 필터 메타데이터 조회
     * 장르/테마 목록, DB에 저장된 게임 가격 범위, 플레이타입 범위
     */
    public SearchFilterMetadataResponse getSearchMetadata() {
        log.debug("[GameSearch] Starting to fetch search filter metadata.");

        // Enum 기반 장르/테마 목록 추출
        List<SearchFilterMetadataResponse.GenreInfo> genres = Arrays.stream(GenreType.values())
                .map(g -> new SearchFilterMetadataResponse.GenreInfo(g.name(), g.getKorName()))
                .toList();

        List<SearchFilterMetadataResponse.ThemeInfo> themes = Arrays.stream(ThemeType.values())
                .map(t -> new SearchFilterMetadataResponse.ThemeInfo(t.name(), t.getKorName()))
                .toList();

        // DB 기반 동적 범위(가격, 플레이타임) 조회
        SearchFilterMetadataResponse.PriceRange priceRange = gameRepository.getPriceRange();
        SearchFilterMetadataResponse.PlaytimeRange playtimeRange = gameRepository.getPlaytimeRange();

        log.debug("[GameSearch] Successfully fetched search metadata - Price: {}~{}, Playtime: {}~{}",
                priceRange.min(), priceRange.max(), playtimeRange.min(), playtimeRange.max());

        return SearchFilterMetadataResponse.builder()
                .genres(genres)
                .themes(themes)
                .priceRange(priceRange)
                .playtimeRange(playtimeRange)
                .build();
    }

    /**
     * 통합 검색 메서드 (다중 필터 조건 및 검색어 기반)
     * @param condition 검색어, 장르, 테마, 가격, 플레이타임 등의 필터 조건
     * @param pageable 페이징 및 정렬 정보
     */
    public Page<GameResponse> searchGames(GameSearchConditionRequest condition, Pageable pageable) {
        log.debug("[GameSearch] Incoming game search request - keyword: [{}], condition: {}", condition.keyword(), condition);

        // 검색 필터 조건 유효성 검증
        validateSearchCondition(condition);

        // 필터 조건에 맞는 게임 목록 페이징하여 반환
        Page<GameWithReviewStatDto> searchResult = gameRepository.searchGames(condition, pageable);

        // 조회 결과에 따른 로깅
        if (searchResult.isEmpty()) {
            log.debug("[GameSearch] No games found matching the search condition. keyword: [{}]", condition.keyword());
        } else {
            log.debug("[GameSearch] Game search completed successfully - found {} games.", searchResult.getTotalElements());
        }

        // DTO 변환 및 반환 (최저가 조인 로직 포함)
        return gameResponseConverter.convertPage(searchResult);
    }

    /**
     * 검색 조건 비즈니스 유효성 검증
     */
    private void validateSearchCondition(GameSearchConditionRequest condition) {
        // 최소 가격이 최대 가격보다 논리적으로 큰 경우
        if (condition.minPrice() != null && condition.maxPrice() != null && condition.minPrice() > condition.maxPrice()) {
            log.warn("[GameSearch] Validation failed: Invalid price range - min: {}, max: {}", condition.minPrice(), condition.maxPrice());
            throw new CustomException(ErrorCode.INVALID_PRICE_RANGE);
        }

        // 최소 플레이타임이 최대 플레이타임보다 큰 경우
        if (condition.minPlaytime() != null && condition.maxPlaytime() != null && condition.minPlaytime() > condition.maxPlaytime()) {
            log.warn("[GameSearch] Validation failed: Invalid playtime range - min: {}, max: {}", condition.minPlaytime(), condition.maxPlaytime());
            throw new CustomException(ErrorCode.INVALID_PLAYTIME_RANGE);
        }

        // 검색어 길이 제한 (악의적인 검색 요청 방어)
        if (StringUtils.hasText(condition.keyword()) && condition.keyword().length() > 50) {
            log.warn("[GameSearch] Validation failed: Keyword length exceeded - length: {}", condition.keyword().length());
            throw new CustomException(ErrorCode.SEARCH_KEYWORD_TOO_LONG);
        }
    }
}
