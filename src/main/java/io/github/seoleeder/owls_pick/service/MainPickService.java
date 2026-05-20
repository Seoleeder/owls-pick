package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.response.UpcomingGameResponse;
import io.github.seoleeder.owls_pick.dto.response.section.PersonalizedSectionResponse;
import io.github.seoleeder.owls_pick.dto.response.section.UpcomingSectionResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.entity.game.enums.GameSortType;
import io.github.seoleeder.owls_pick.entity.game.enums.GenreType;
import io.github.seoleeder.owls_pick.entity.game.enums.ThemeType;
import io.github.seoleeder.owls_pick.global.config.properties.CurationProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.global.util.GameResponseConverter;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import io.github.seoleeder.owls_pick.repository.dto.GameWithReviewStatDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MainPickService {

    private final GameRepository gameRepository;
    private final UserRepository userRepository;
    private final CurationProperties curationProps;
    private final GameResponseConverter responseConverter;

    // 유효한 태그 조합을 담을 리스트 (서버 메모리에 캐싱)
    private List<GenreThemePair> validCombinations = new ArrayList<>();

    public record GenreThemePair(GenreType genre, ThemeType theme) {}

    /**
     * 서버 시작 시 유효 태그 조합 초기화 메서드 실행
     */
    @PostConstruct
    public void initValidCombinations() {
        refreshValidCombinations();
    }

    /**
     * 장르, 테마 태그 조합을 카운트하여 유효한 조합들로 캐시 갱신
     * - 주기 : 매일 새벽 4시
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void refreshValidCombinations() {
        log.info("[MainPick] Updating valid genre-theme combinations in memory cache...");
        List<GenreThemePair> newCombinations = new ArrayList<>();

        // 유효 조합의 기준이 되는 해당 조합의 최소 게임 수
        int minRequired = curationProps.intersection().minRequiredGames();

        for (GenreType genre : GenreType.values()) {
            for (ThemeType theme : ThemeType.values()) {
                if (theme == ThemeType.EROTIC) continue; // 성인 테마는 교집합에서 배제

                long count = gameRepository.countGamesByGenreAndTheme(genre, theme);
                if (count >= minRequired) {
                    newCombinations.add(new GenreThemePair(genre, theme));
                }
            }
        }

        // 갱신이 완료되면 한 번에 덮어쓰기 (동시성 이슈 최소화)
        this.validCombinations = newCombinations;
        log.info("[MainPick] Memory cache updated successfully. Total valid combinations loaded: {}", validCombinations.size());
    }

    /**
     * Upcoming Games: 출시 예정일이 N개월 이내인 출시 예정 기대작 조회
     * */
    @Transactional(readOnly = true)
    public UpcomingSectionResponse getUpcomingGames(Pageable pageable) {
        log.debug("[MainPick] Fetching upcoming games.");

        CurationProperties.Upcoming props = curationProps.upcoming();

        LocalDate today = LocalDate.now();
        LocalDate maxDate = today.plusMonths(props.periodMonths()); // 출시 예정일 필터링을 위한 N개월 설정 값

        Page<Game> upcomingGames = gameRepository.findUpcomingGames(today, maxDate, props.minHypes(), pageable);

        // Game 엔티티 -> Upcoming 전용 DTO
        Page<UpcomingGameResponse> dtoPage = upcomingGames.map(responseConverter::convertToUpcomingDto);

        return new UpcomingSectionResponse("출시 예정 최고 기대작", dtoPage);
    }

    /**
     * [Section 1] Most Personalized Picks: 선호 태그 기반 맞춤 추천
     * 선호 태그를 가장 많이 포함하는 사용자 맞춤형 최적 게임 리스트 조회
     */
    public PersonalizedSectionResponse getMostPersonalizedPicks(Long userId, Pageable pageable) {
        log.debug("[MainPick] Fetching most personalized picks for userId: {}", userId);

        // 유저 ID로 사용자 조회
        User user = getUser(userId);

        // 사용자의 선호 태그 목록
        List<String> preferredTags = user.getPreferredTags() != null
                ? new ArrayList<>(user.getPreferredTags())
                : new ArrayList<>();

        // 동일 태그 조합의 캐시 적중률 극대화를 위한 리스트 오름차순 정렬
        Collections.sort(preferredTags);

        Page<GameWithReviewStatDto> games = gameRepository.findPersonalizedGamesByPreferredTags(preferredTags, pageable);

        return new PersonalizedSectionResponse("맞춤 픽", responseConverter.convertPage(games));
    }

    /**
     * [Section 2] Random Genre Picks: 단일 장르 랜덤 탐색
     */
    public PersonalizedSectionResponse getRandomGenrePicks(Pageable pageable) {
        GenreType[] genres = GenreType.values();

        // 장르 목록 중 장르 태그 하나 선택
        GenreType selectedGenre = genres[ThreadLocalRandom.current().nextInt(genres.length)];

        log.debug("[MainPick] Fetching random genre picks. Selected genre: {}", selectedGenre.name());

        Page<GameWithReviewStatDto> games = gameRepository.findGamesByGenre(selectedGenre, GameSortType.POPULAR, pageable);
        return new PersonalizedSectionResponse(selectedGenre.getKorName(), responseConverter.convertPage(games));
    }

    /**
     * [Section 3] Random Theme Picks: 단일 테마 랜덤 탐색 (성인 태그 EROTIC 필터링)
     */
    public PersonalizedSectionResponse getRandomThemePicks(Long userId, Pageable pageable) {

        // 로그인한 사용자가 성인일 경우 true 반환
        final boolean isAdult = (userId != null) && getUser(userId).isAdultUser();

        // 성인 태그를 제외한 테마 태그 목록
        List<ThemeType> safeThemes = Arrays.stream(ThemeType.values())
                .filter(theme -> isAdult || theme != ThemeType.EROTIC)
                .toList();

        // 테마 목록 중 테마 태그 하나 선택
        ThemeType selectedTheme = safeThemes.get(ThreadLocalRandom.current().nextInt(safeThemes.size()));

        log.debug("[MainPick] Fetching random theme picks for userId: {}. Selected theme: {}", userId, selectedTheme.name());

        Page<GameWithReviewStatDto> games = gameRepository.findGamesByTheme(selectedTheme, GameSortType.POPULAR, pageable);
        return new PersonalizedSectionResponse(selectedTheme.getKorName(), responseConverter.convertPage(games));
    }

    /**
     * [Section 4] Intersection Picks: 유효한 장르 X 테마 조합을 가진 게임 조회
     */
    public PersonalizedSectionResponse getIntersectionPicks(Pageable pageable) {
        // 장르 X 테마 조합 데이터가 존재하지 않으면 INDIE 태그로 조회 후 반환
        if (validCombinations.isEmpty()) {
            log.warn("[MainPick] No valid combinations found in memory cache. Falling back to INDIE genre.");

            Page<GameWithReviewStatDto> fallbackGames = gameRepository.findGamesByGenre(GenreType.INDIE, GameSortType.POPULAR, pageable);

            return new PersonalizedSectionResponse(GenreType.INDIE.getKorName(), responseConverter.convertPage(fallbackGames));
        }

        GenreThemePair selectedPair = validCombinations.get(ThreadLocalRandom.current().nextInt(validCombinations.size()));

        log.debug("[MainPick] Fetching random intersection picks. Selected combination: [Genre: {}, Theme: {}]",
                selectedPair.genre().name(), selectedPair.theme().name());

        Page<GameWithReviewStatDto> games = gameRepository.findGamesByGenreAndThemeIntersection(
                selectedPair.genre(), selectedPair.theme(), pageable
        );

        String comboTitle = selectedPair.theme().getKorName() + " " + selectedPair.genre().getKorName();
        return new PersonalizedSectionResponse(comboTitle, responseConverter.convertPage(games));
    }

    /**
     * [Section 5] Hidden Masterpieces: 스코어는 높은데 리뷰 수는 상대적으로 적은 '숨겨진 명작' 게임 조회
     */
    public PersonalizedSectionResponse getHiddenMasterpieces(Pageable pageable) {
        log.debug("[MainPick] Fetching hidden masterpieces.");
        CurationProperties.HiddenMasterpiece props = curationProps.hiddenMasterpiece();
        Page<GameWithReviewStatDto> games = gameRepository.findHiddenMasterpieces(
                props.minReviewScore(),
                props.minReviews(),
                props.maxReviews(),
                pageable
        );

        return new PersonalizedSectionResponse("숨겨진 명작", responseConverter.convertPage(games));
    }

    /**
     * [Section 6] Trending Picks: 요즘 뜨는 특정 태그 게임 조회 (유저 선호 태그 중 택 1, 주간 리뷰 수가 많은 게임 순)
     */
    public PersonalizedSectionResponse getTrendingPicks(Long userId, Pageable pageable) {
        User user = getUser(userId);
        String safeTag = getSafeRandomPreferredTag(user);

        log.debug("[MainPick] Fetching trending picks for userId: {}. Selected tag: {}", userId, safeTag);

        Page<GameWithReviewStatDto> games = gameRepository.findTrendingGamesByTag(
                safeTag, curationProps.trending().minReviewScore(), pageable
        );
        return new PersonalizedSectionResponse(safeTag, responseConverter.convertPage(games));
    }

    /**
     * [Section 7] Quick Plays: 플레이타임이 짧고 강렬한 게임 (유저 선호 태그 중 택 1. 리뷰 스코어가 높은 순)
     */
    public PersonalizedSectionResponse getQuickPlays(Long userId, Pageable pageable) {
        User user = getUser(userId);
        String safeTag = getSafeRandomPreferredTag(user);

        log.debug("[MainPick] Fetching quick plays for userId: {}. Selected tag: {}", userId, safeTag);

        Page<GameWithReviewStatDto> games = gameRepository.findShortPlaytimeGamesByTag(
                safeTag, curationProps.shortPlaytime().maxPlaytime(), curationProps.shortPlaytime().minScore(), pageable
        );
        return new PersonalizedSectionResponse(safeTag, responseConverter.convertPage(games));
    }

    // 헬퍼 메서드
    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_USER));
    }

    /**
     * 미성년자의 경우 텍스트상 성인 게임을 유추할 수 있는 태그 배제
     */
    private String getSafeRandomPreferredTag(User user) {
        List<String> tags = user.getPreferredTags();
        if (tags == null || tags.isEmpty()) return "INDIE";

        List<String> safeTags = tags;
        if (!user.isAdultUser()) {
            safeTags = tags.stream()
                    .filter(tag -> !tag.equalsIgnoreCase(ThemeType.EROTIC.name()))
                    .toList();
        }

        if (safeTags.isEmpty()) return "INDIE";
        return safeTags.get(ThreadLocalRandom.current().nextInt(safeTags.size()));
    }
}
