package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.response.GameResponse;
import io.github.seoleeder.owls_pick.dto.response.WishlistResponse;
import io.github.seoleeder.owls_pick.dto.response.WishlistToggleResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.entity.user.Wishlist;
import io.github.seoleeder.owls_pick.entity.user.WishlistId;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.global.util.GameResponseConverter;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import io.github.seoleeder.owls_pick.repository.WishlistRepository;
import io.github.seoleeder.owls_pick.repository.dto.GameWithReviewStatDto;
import io.github.seoleeder.owls_pick.repository.dto.WishlistQueryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {
    private final WishlistRepository wishlistRepository;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final GamePriceService gamePriceService;
    private final GameResponseConverter gameResponseConverter;

    // ==========================================
    // 1. 공통 & 게임 상세 페이지용 기능
    // ==========================================

    /**
     * 위시리스트 토글 메서드
     * 사용자의 위시리스트를 확인하여 이미 등록된 상태면 해제하고, 아니면 추가
     * 처리 후 해당 게임이 위시리스트에 담긴 총 횟수를 반환
     */
    @Transactional
    public WishlistToggleResponse toggleWishlist(Long userId, Long gameId) {
        WishlistId wishlistId = new WishlistId(userId, gameId);

        return wishlistRepository.findById(wishlistId)
                .map(wishlist -> {
                    // 이미 담긴 상태 -> 위시리스트 해제
                    wishlistRepository.delete(wishlist);
                    wishlistRepository.flush(); // 카운트 조회를 위해 DB 즉시 동기화

                    long totalCount = wishlistRepository.countByGameId(gameId);
                    log.info("[Wishlist] Removed from wishlist - userId: {}, gameId: {}, current total: {}", userId, gameId, totalCount);

                    return WishlistToggleResponse.builder()
                            .isWished(false)
                            .totalWishCount(totalCount).build();
                })
                .orElseGet(() -> {
                    // 추가되지 않은 상태 -> 위시리스트 추가
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_USER));
                    Game game = gameRepository.findById(gameId)
                            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_GAME));

                    Wishlist newWishlist = Wishlist.builder()
                            .id(wishlistId)
                            .user(user)
                            .game(game)
                            .build();

                    wishlistRepository.save(newWishlist);
                    wishlistRepository.flush(); // 카운트 조회를 위해 DB 즉시 동기화

                    long totalCount = wishlistRepository.countByGameId(gameId);
                    log.info("[Wishlist] Added to wishlist - userId: {}, gameId: {}, current total: {}", userId, gameId, totalCount);

                    return WishlistToggleResponse.builder()
                            .isWished(true)
                            .totalWishCount(totalCount).build();
                });
    }


    // ==========================================
    // 2. 마이 페이지 전용 기능
    // ==========================================

    /**
     * 사용자의 위시리스트(찜 목록)를 페이징하여 조회
     * GameResponse에 해당 게임을 찜한 시각(wishedAt)을 결합하여 반환
     */
    @Transactional(readOnly = true)
    public Page<WishlistResponse> getMyWishlist(Long userId, Pageable pageable) {

        // 위시리스트 주요 데이터 조회
        Page<WishlistQueryDto> wishlistPage = wishlistRepository.findWishlistPageByUserId(userId, pageable);

        // 현재 페이지에 있는 게임 ID 리스트 추출
        List<Long> gameIds = wishlistPage.getContent().stream()
                .map(dto -> dto.game().getId())
                .toList();

        // 각 게임별 현재 최저가 데이터 매핑
        Map<Long, StoreDetail> lowestPriceMap = gamePriceService.getLowestPriceMap(gameIds);

        // 컨버터를 활용하여 최종 응답 DTO 조립
        return wishlistPage.map(dto -> {
            StoreDetail bestOffer = lowestPriceMap.get(dto.game().getId());

            // 컨버터 규격에 맞게 게임, 리뷰 통계 데이터 추출 후 변환
            GameWithReviewStatDto tempDto = new GameWithReviewStatDto(dto.game(), dto.reviewStat());

            // GameWithReviewStatDto -> GameResponse 변환
            GameResponse gameResponse = gameResponseConverter.convertToDto(tempDto, bestOffer);

            return WishlistResponse.builder()
                    .wishedAt(dto.wishedAt())
                    .gameResponse(gameResponse)
                    .build();
        });
    }

    /**
     * 위시리스트 해제 (찜 목록에서 삭제)
     */
    @Transactional
    public void removeFromWishlist(Long userId, Long gameId) {
        // 복합키(WishlistId)를 생성하여 삭제 쿼리 실행
        WishlistId wishlistId = new WishlistId(userId, gameId);

        wishlistRepository.deleteById(wishlistId);

        log.info("[Wishlist] Explicitly removed from wishlist in My Page - userId: {}, gameId: {}", userId, gameId);
    }

}
