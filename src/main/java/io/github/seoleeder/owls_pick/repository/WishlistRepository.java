package io.github.seoleeder.owls_pick.repository;


import io.github.seoleeder.owls_pick.entity.user.Wishlist;
import io.github.seoleeder.owls_pick.entity.user.WishlistId;
import io.github.seoleeder.owls_pick.repository.custom.WishlistRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WishlistRepository extends JpaRepository<Wishlist, WishlistId>, WishlistRepositoryCustom {

    // 특정 게임이 위시리스트에 담긴 총 횟수(유저 수) 조회
    long countByGameId(Long gameId);

    // 특정 사용자가 특정 게임을 위시리스트에 담았는지 여부
    boolean existsByGameIdAndUserId(Long gameId, Long userId);
}
