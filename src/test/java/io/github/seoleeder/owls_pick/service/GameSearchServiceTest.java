package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.request.GameSearchConditionRequest;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.global.util.GameResponseConverter;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
public class GameSearchServiceTest {

    @InjectMocks
    private GameSearchService gameSearchService;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameResponseConverter gameResponseConverter;

    @Test
    @DisplayName("최소 가격이 최대 가격보다 클 경우 CustomException 발생 검증")
    void searchGames_InvalidPriceRange_ThrowException() {
        // [Given] 최소 가격이 최대 가격보다 큰 비정상 검색 조건 파라미터 세팅
        GameSearchConditionRequest condition = new GameSearchConditionRequest(
                null, null, null,
                50000, 10000, // minPrice, maxPrice
                null, null, null, null
        );

        // [When & Then] 검색 비즈니스 로직 실행 및 INVALID_PRICE_RANGE 커스텀 예외 발생 확인
        assertThatThrownBy(() -> gameSearchService.searchGames(condition, PageRequest.of(0, 10)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.INVALID_PRICE_RANGE.getMessage());
    }

    @Test
    @DisplayName("검색어 길이가 50자를 초과할 경우 CustomException 발생 검증")
    void searchGames_KeywordTooLong_ThrowException() {
        // [Given] 50자 초과 검색어 파라미터 세팅
        String longKeyword = "a".repeat(51);
        GameSearchConditionRequest condition = new GameSearchConditionRequest(
                longKeyword, null, null, null, null, null, null, null, null
        );

        // [When & Then] 검색 비즈니스 로직 실행 및 SEARCH_KEYWORD_TOO_LONG 커스텀 예외 발생 확인
        assertThatThrownBy(() -> gameSearchService.searchGames(condition, PageRequest.of(0, 10)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.SEARCH_KEYWORD_TOO_LONG.getMessage());
    }
}
