package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GamePriceService {

    private final StoreDetailRepository storeDetailRepository;

    /**
     * 여러 게임 ID를 받아, 각 게임별 현재 최저가를 맵핑하여 반환
     */
    public Map<Long, StoreDetail> getLowestPriceMap(List<Long> gameIds) {
        if (gameIds == null || gameIds.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            List<StoreDetail> allStoreDetails = storeDetailRepository.findAllByGameIdIn(gameIds);

            return allStoreDetails.stream()
                    .collect(Collectors.toMap(
                            detail -> detail.getGame().getId(),
                            detail -> detail,
                            (existing, replacement) -> {
                                // 현재 할인가가 더 낮은 데이터를 우선 선택
                                int existingPrice = (existing.getDiscountPrice() != null) ? existing.getDiscountPrice() : Integer.MAX_VALUE;
                                int replacementPrice = (replacement.getDiscountPrice() != null) ? replacement.getDiscountPrice() : Integer.MAX_VALUE;

                                return existingPrice <= replacementPrice ? existing : replacement;
                            }
                    ));
        } catch (Exception e) {
            // 가격 조회 실패 시 로그만 남기고 빈 데이터 반환
            log.warn("[GamePriceService] Exception occurred while fetching lowest prices. Falling back to empty data. Reason: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

}
