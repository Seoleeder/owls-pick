package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.response.section.PersonalizedSectionResponse;
import io.github.seoleeder.owls_pick.entity.game.enums.GenreType;
import io.github.seoleeder.owls_pick.entity.game.enums.ThemeType;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.config.properties.CurationProperties;
import io.github.seoleeder.owls_pick.global.util.GameResponseConverter;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MainPickServiceTest {

    @InjectMocks
    private MainPickService mainPickService;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GameResponseConverter responseConverter;

    @Mock
    private CurationProperties curationProps;

    @Mock
    private CurationProperties.Intersection intersectionProps;

    @Mock
    private CurationProperties.Trending trendingProps;

    // --- 1. 스케줄러 및 메모리 캐시 갱신 비즈니스 검증 ---

    @Test
    @DisplayName("유효 조합 갱신 시, 설정된 최소 게임 수를 충족하는 조합만 캐싱되는지 검증")
    void refreshValidCombinations_FilterByMinRequiredGames_Success() {
        // [Given] 유효 조합 등록을 위한 최소 게임 수 임계치 설정 (5개)
        when(curationProps.intersection()).thenReturn(intersectionProps);
        when(intersectionProps.minRequiredGames()).thenReturn(5);

        // [Given] 특정 조합(RPG + FANTASY)만 임계치를 충족하도록 모킹
        when(gameRepository.countGamesByGenreAndTheme(any(), any())).thenReturn(0L);
        when(gameRepository.countGamesByGenreAndTheme(GenreType.RPG, ThemeType.FANTASY)).thenReturn(10L);

        // [When] 캐시 갱신 로직 실행
        mainPickService.refreshValidCombinations();

        // [Then] IntersectionPicks 조회 시 에러 없이 반환되는지 확인하여 정상 캐시 적재 간접 검증
        when(gameRepository.findGamesByGenreAndThemeIntersection(eq(GenreType.RPG), eq(ThemeType.FANTASY), any()))
                .thenReturn(Page.empty());

        PersonalizedSectionResponse response = mainPickService.getIntersectionPicks(PageRequest.of(0, 10));

        // [Then] 반환된 응답의 제목이 설정한 조합명과 일치하는지 확인
        assertThat(response.titleKeyword()).isEqualTo(ThemeType.FANTASY.getKorName() + " " + GenreType.RPG.getKorName());
    }

    // --- 2. 우회(Fallback) 및 정책(Policy) 방어 로직 검증 ---

    @Test
    @DisplayName("메모리 캐시가 비어있을 때 INDIE 장르로 우회 조회하는지 검증")
    void getIntersectionPicks_EmptyCache_FallbackToIndie() {
        // [Given] 메모리 캐시가 비어있는 상태 모킹 (초기 상태)
        when(gameRepository.findGamesByGenre(eq(GenreType.INDIE), any(), any()))
                .thenReturn(Page.empty());

        // [When] 교집합 픽 조회 로직 실행
        PersonalizedSectionResponse response = mainPickService.getIntersectionPicks(PageRequest.of(0, 10));

        // [Then] IndexOutOfBounds 예외 발생 없이 INDIE 장르로 DB 조회 우회 확인
        assertThat(response.titleKeyword()).isEqualTo(GenreType.INDIE.getKorName());
        verify(gameRepository, times(1)).findGamesByGenre(eq(GenreType.INDIE), any(), any());
    }
    @Test
    @DisplayName("미성년자 유저의 랜덤 테마 조회 시 성인(EROTIC) 테마 배제 검증")
    void getRandomThemePicks_MinorUser_ExcludeEroticTheme() {
        // [Given] 미성년자 유저 객체 모킹
        User minorUser = mock(User.class);
        when(minorUser.isAdultUser()).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(minorUser));

        when(gameRepository.findGamesByTheme(any(), any(), any())).thenReturn(Page.empty());

        // [When] 성인 테마 추출 여부 확인을 위해 조회 로직 다중 반복 실행 (50회)
        for (int i = 0; i < 50; i++) {
            mainPickService.getRandomThemePicks(1L, PageRequest.of(0, 10));
        }

        // [Then] EROTIC 테마를 조건으로 한 DB 조회 메서드 미호출(0회) 확인
        verify(gameRepository, never()).findGamesByTheme(eq(ThemeType.EROTIC), any(), any());
    }

    @Test
    @DisplayName("필터링으로 인해 선호 태그가 비어있을 경우 INDIE 태그로 우회 조회하는지 검증")
    void getTrendingPicks_EmptyOrFilteredPreferredTags_FallbackToIndie() {
        // [Given] 성인 테마(EROTIC)만 선호 태그로 등록해둔 미성년자 유저 상태 세팅
        User edgeCaseUser = mock(User.class);
        when(edgeCaseUser.isAdultUser()).thenReturn(false);
        when(edgeCaseUser.getPreferredTags()).thenReturn(List.of(ThemeType.EROTIC.name()));

        when(userRepository.findById(2L)).thenReturn(Optional.of(edgeCaseUser));

        when(curationProps.trending()).thenReturn(trendingProps);
        when(trendingProps.minReviewScore()).thenReturn(80);
        when(gameRepository.findTrendingGamesByTag(anyString(), anyInt(), any())).thenReturn(Page.empty());

        // [When] 트렌딩 픽 조회 로직 실행
        PersonalizedSectionResponse response = mainPickService.getTrendingPicks(2L, PageRequest.of(0, 10));

        // [Then] 정책 필터링으로 선호 태그 목록이 비어있는 경우, 예외 없이 INDIE 태그로 조회 우회 확인
        assertThat(response.titleKeyword()).isEqualTo("INDIE");
        verify(gameRepository, times(1)).findTrendingGamesByTag(eq("INDIE"), anyInt(), any());
    }
}