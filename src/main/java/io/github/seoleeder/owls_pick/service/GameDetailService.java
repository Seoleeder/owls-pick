package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.response.GameDetailResponse;
import io.github.seoleeder.owls_pick.entity.game.*;
import io.github.seoleeder.owls_pick.entity.game.enums.GameModeType;
import io.github.seoleeder.owls_pick.entity.game.enums.PerspectiveType;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.*;
import io.github.seoleeder.owls_pick.repository.dto.GameDetailCoreDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;


/**
 * 게임 상세 정보 관리 서비스
 * (게임의 기본 정보와 스토어, 언어 지원, 개발사, 리뷰 통계 등 연관 데이터 취합)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameDetailService {

    private final GameRepository gameRepository;
    private final StoreDetailRepository storeDetailRepository;
    private final LanguageSupportRepository languageSupportRepository;
    private final GameCompanyRepository gameCompanyRepository;
    private final ScreenshotRepository screenshotRepository;
    private final WishlistRepository wishlistRepository;

    /**
     * 특정 게임의 모든 상세 정보를 묶어서 반환
     */
    public GameDetailResponse getGameDetail(Long gameId, Long currentUserId) {
        log.debug("[GameDetail] Start fetching details for Game ID: {}", gameId);

        // 게임 코어 데이터 조회 (게임, 태그, 리뷰 통계, 플레이타임)
        GameDetailCoreDto coreDto = gameRepository.findGameDetailCoreById(gameId)
                .orElseThrow(() -> {
                    log.warn("[GameDetail] Game not found with ID: {}", gameId);
                    return new CustomException(ErrorCode.NOT_FOUND_GAME);
                });

        // [1:N] 컬렉션 데이터 개별 조회
        log.debug("[GameDetail] Fetching sub-collections for Game ID: {}", gameId);
        List<StoreDetail> stores = storeDetailRepository.findByGameId(gameId);
        List<LanguageSupport> languageSupports = languageSupportRepository.findByGameId(gameId);
        List<Screenshot> screenshots = screenshotRepository.findByGameId(gameId);

        // [N:M] 중간 매핑 테이블(GameCompany)을 통해 조회
        List<GameCompany> gameCompanies = gameCompanyRepository.findByGameId(gameId);

        // 위시리스트 상태 및 통계 가져오기
        long totalWishCount = wishlistRepository.countByGameId(gameId);
        boolean isWished = (currentUserId != null) && wishlistRepository.existsByGameIdAndUserId(gameId, currentUserId);

        log.debug("[GameDetail] Successfully aggregated all data for Game: '{}'", coreDto.game().getTitle());

        // 4. 최종 조립 후 반환
        return buildResponse(coreDto, stores, languageSupports, gameCompanies, screenshots, totalWishCount, isWished);
    }

    /**
     * 엔티티들을 취합하여 GameDetailResponse DTO로 변환하는 헬퍼 메서드
     */
    private GameDetailResponse buildResponse(
            GameDetailCoreDto core,
            List<StoreDetail> stores,
            List<LanguageSupport> languageSupports,
            List<GameCompany> gameCompanies,
            List<Screenshot> screenshots,
            long totalWishCount,
            boolean isWished) {

        Game game = core.game();
        Tag tag = core.tag();
        Playtime playtime = core.playtime();
        ReviewStat review = core.reviewStat();

        return GameDetailResponse.builder()
                // --- 기본 게임 정보 ---
                .gameId(game.getId())
                .title(game.getTitle())
                .titleLocalization(game.getTitleLocalization())
                .description(game.getDescriptionKo())
                .storyline(game.getStorylineKo())
                .firstRelease(game.getFirstRelease())
                .coverId(game.getCoverId())
                .ratingKr(game.getRatingKr())
                .ratingEsrb(game.getRatingEsrb())
                .isAdult(game.getIsAdult())
                .mode(game.getMode() == null ? Collections.emptyList() :
                        game.getMode().stream().map(GameModeType::getKorName).toList())
                .perspective(game.getPerspective() == null ? Collections.emptyList() :
                        game.getPerspective().stream().map(PerspectiveType::getKorName).toList())
                .reviewSummary(review != null ? review.getReviewSummary() : null)
                .hypes(game.getHypes())

                // --- 1:1 관계 정보 조립 (Null Safe) ---
                .tags(tag != null ? GameDetailResponse.TagInfo.builder()
                        .genres(toListSafe(tag.getGenres()))
                        .themes(toListSafe(tag.getThemes()))
                        .keywords(toListSafe(tag.getKeywords()))
                        .build() : null)

                .playtime(playtime != null ? GameDetailResponse.PlaytimeInfo.builder()
                        .mainStory(playtime.getMainStory())
                        .mainExtras(playtime.getMainExtras())
                        .completionist(playtime.getCompletionist())
                        .build() : null)

                .reviewStats(review != null ? GameDetailResponse.ReviewStatsInfo.builder()
                        .reviewScore(review.getReviewScore())
                        .reviewScoreDesc(review.getReviewScoreDesc())
                        .totalPositive(review.getTotalPositive())
                        .totalNegative(review.getTotalNegative())
                        .totalReview(review.getTotalReview())
                        .reviewSummary(review.getReviewSummary())
                        .build() : null)

                // --- 사용자 위시리스트 정보 ---
                .wishlist(GameDetailResponse.WishlistInfo.builder()
                        .isWished(isWished)
                        .totalWishCount(totalWishCount)
                        .build())

                // --- 1:N / N:M 컬렉션 데이터 매핑 ---
                .stores(stores.stream().map(s -> GameDetailResponse.StorePriceInfo.builder()
                        .name(s.getStoreName())
                        .url(s.getUrl())
                        .originalPrice(s.getOriginalPrice())
                        .discountPrice(s.getDiscountPrice())
                        .discountRate(s.getDiscountRate())
                        .expiryDate(s.getExpiryDate())
                        .build()).toList())

                .languages(languageSupports.stream().map(l -> GameDetailResponse.LanguageSupportInfo.builder()
                        .language(l.getLanguage())
                        .voiceSupport(l.getVoiceSupport())
                        .subtitle(l.getSubtitle())
                        .interfaceSupport(l.getInterSupport())
                        .build()).toList())

                .companies(gameCompanies.stream().map(gc -> GameDetailResponse.CompanyInfo.builder()
                        .name(gc.getCompany().getName())
                        .logo(gc.getCompany().getLogo())
                        .isDeveloper(gc.isDeveloper())
                        .isPublisher(gc.isPublisher())
                        .build()).toList())

                .screenshots(screenshots.stream().map(s -> GameDetailResponse.ScreenshotInfo.builder()
                        .imageId(s.getImageId())
                        .width(s.getWidth())
                        .height(s.getHeight())
                        .build()).toList())

                .build();
    }

    /**
     * 리스트 데이터가 null일 경우 빈 리스트로 변환하여 NullPointerException 방지
     */
    private List<String> toListSafe(List<String> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
