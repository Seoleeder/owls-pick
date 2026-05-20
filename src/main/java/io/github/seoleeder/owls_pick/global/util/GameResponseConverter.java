package io.github.seoleeder.owls_pick.global.util; // 혹은 component, mapper 등 적절한 패키지

import io.github.seoleeder.owls_pick.dto.response.GameResponse;
import io.github.seoleeder.owls_pick.dto.response.UpcomingGameResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.dto.GameWithReviewStatDto; // 명칭 맞춤
import io.github.seoleeder.owls_pick.service.GamePriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.data.web.PagedModel.PageMetadata;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GameResponseConverter {

    private final GamePriceService gamePriceService;
    private final IgdbImageUrlProvider imageUrlProvider;

    /**
     * Page<GameWithReviewStatDto> -> Page<GameResponse> 변환 래퍼
     */
    public Page<GameResponse> convertPage(Page<GameWithReviewStatDto> rawPage) {
        // 내부 데이터(List) 최저가 조인 및 응답 DTO 변환
        List<GameResponse> gameResponses = convertToGameResponseList(rawPage.getContent());

        // 변환된 데이터와 원본 페이징 메타데이터를 결합하여 새로운 Page 반환
        return new PageImpl<>(gameResponses, rawPage.getPageable(), rawPage.getTotalElements());
    }

    /**
     * 최저가 벌크 조인 및 DTO 리스트 매핑
     */
    private List<GameResponse> convertToGameResponseList(List<GameWithReviewStatDto> content) {
        if (content.isEmpty()) {
            return Collections.emptyList();
        }

        // 대상 게임 ID 목록 추출
        List<Long> gameIds = content.stream()
                .map(result -> result.game().getId())
                .toList();

        // 게임별 최저가 벌크 조회 및 매핑
        Map<Long, StoreDetail> lowestPriceMap = gamePriceService.getLowestPriceMap(gameIds);

        // 최저가 정보를 포함한 응답 DTO 리스트 생성 및 반환
        return content.stream()
                .map(result -> convertToDto(result, lowestPriceMap.get(result.game().getId())))
                .toList();
    }

    /**
     * GameWithReviewStatDto + 스토어 현재 최저가 데이터 -> GameResponse
     */
    public GameResponse convertToDto(GameWithReviewStatDto result, StoreDetail bestPrice) {
        Game game = result.game();
        ReviewStat reviewStat = result.reviewStat();

        return GameResponse.builder()
                .gameId(game.getId())
                .title(game.getTitle())
                .coverUrl(imageUrlProvider.generateImageUrl(game.getCoverId()))
                .firstRelease(game.getFirstRelease())
                // 리뷰가 수집되지 않은 경우(Null) 0으로 기본값 방어
                .totalReview(reviewStat != null ? reviewStat.getTotalReview() : 0)
                .reviewScore(reviewStat != null ? reviewStat.getReviewScore() : 0)
                // 가격 정보가 없는 경우 기본값 0으로 설정
                .originalPrice((bestPrice != null && bestPrice.getOriginalPrice() != null) ? bestPrice.getOriginalPrice() : 0)
                .discountPrice((bestPrice != null && bestPrice.getDiscountPrice() != null) ? bestPrice.getDiscountPrice() : 0)
                .discountRate((bestPrice != null && bestPrice.getDiscountRate() != null) ? bestPrice.getDiscountRate() : 0)
                .build();
    }

    /**
     * Entity(Game) -> UpcomingGameResponseDto (출시 예정작 전용 Dto)
     */
    public UpcomingGameResponse convertToUpcomingDto(Game game) {
        return UpcomingGameResponse.builder()
                .gameId(game.getId())
                .title(game.getTitle())
                .coverUrl(imageUrlProvider.generateImageUrl(game.getCoverId()))
                .firstRelease(game.getFirstRelease())
                .hypes(game.getHypes())
                .platforms(game.getPlatform() != null ? game.getPlatform() : Collections.emptyList())
                .build();
    }
}