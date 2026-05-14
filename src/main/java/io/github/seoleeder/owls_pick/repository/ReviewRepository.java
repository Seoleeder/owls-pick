package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.game.Review;
import io.github.seoleeder.owls_pick.repository.custom.ReviewRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long>, ReviewRepositoryCustom {
    //특정 게임 내에서 해당 리뷰 ID가 존재하는지 확인
    boolean existsByGameIdAndRecommendationId(Long gameId, Long recommendationId);
}
