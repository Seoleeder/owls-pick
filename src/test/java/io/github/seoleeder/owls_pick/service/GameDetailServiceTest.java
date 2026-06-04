package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.response.GameDetailResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.*;
import io.github.seoleeder.owls_pick.repository.dto.GameDetailCoreDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class GameDetailServiceTest {

    @InjectMocks
    private GameDetailService gameDetailService;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private StoreDetailRepository storeDetailRepository;

    @Mock
    private LanguageSupportRepository languageSupportRepository;

    @Mock
    private GameCompanyRepository gameCompanyRepository;

    @Mock
    private ScreenshotRepository screenshotRepository;

    @Mock
    private WishlistRepository wishlistRepository;

    @Test
    @DisplayName("게임 상세 정보 취합 및 빈 데이터 방어 로직 검증")
    void getGameDetail_Success() {
        // [Given] 게임 기본 정보는 존재하나 하위 컬렉션 데이터가 없는 상황 세팅
        Game game = Game.builder().title("Test Game").build();
        GameDetailCoreDto coreDto = new GameDetailCoreDto(game, null, null, null);

        when(gameRepository.findGameDetailCoreById(anyLong())).thenReturn(Optional.of(coreDto));

        // 1:N 하위 컬렉션(1:N) 빈 리스트 반환 모킹
        when(storeDetailRepository.findByGameId(anyLong())).thenReturn(Collections.emptyList());
        when(languageSupportRepository.findByGameId(anyLong())).thenReturn(Collections.emptyList());
        when(screenshotRepository.findByGameId(anyLong())).thenReturn(Collections.emptyList());
        when(gameCompanyRepository.findByGameId(anyLong())).thenReturn(Collections.emptyList());

        // 위시리스트 데이터 모킹
        when(wishlistRepository.countByGameId(anyLong())).thenReturn(10L);
        when(wishlistRepository.existsByGameIdAndUserId(anyLong(), anyLong())).thenReturn(true);

        // [When] 게임 상세 정보 조회 로직 실행
        GameDetailResponse response = gameDetailService.getGameDetail(1L, 100L);

        // [Then] 에러 발생 없이 기본 정보 및 빈 리스트 데이터 매핑 확인
        assertThat(response.title()).isEqualTo("Test Game");
        assertThat(response.tags()).isNull();
        assertThat(response.stores()).isEmpty();

        // [Then] 위시리스트 데이터 정상 반영 확인
        assertThat(response.wishlist().isWished()).isTrue();
        assertThat(response.wishlist().totalWishCount()).isEqualTo(10L);
    }

    @Test
    @DisplayName("존재하지 않는 게임 조회 시 예외 발생 검증")
    void getGameDetail_NotFound_ThrowException() {
        // [Given] DB에 존재하지 않는 게임 조회 상황 세팅
        when(gameRepository.findGameDetailCoreById(anyLong())).thenReturn(Optional.empty());

        // [When & Then] 상세 조회 실행 및 NOT_FOUND_GAME 예외 발생 확인
        assertThatThrownBy(() -> gameDetailService.getGameDetail(999L, 100L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.NOT_FOUND_GAME.getMessage());
    }
}