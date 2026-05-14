package io.github.seoleeder.owls_pick.repository.custom;


import io.github.seoleeder.owls_pick.repository.dto.WishlistQueryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface WishlistRepositoryCustom {

    // 특정 유저의 위시리스트와 게임 데이터를 묶어서 페이징 조회
    Page<WishlistQueryDto> findWishlistPageByUserId(Long userId, Pageable pageable);

    // 특정 게임을 위시리스트에 담았으며, 할인 알림 수신에 동의한 유저 ID 목록 조회
    List<Long> findTargetUserIdsForDiscountPush(Long gameId);
}
