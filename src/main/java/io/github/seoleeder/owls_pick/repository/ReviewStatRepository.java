package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.repository.custom.ReviewStatRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewStatRepository extends JpaRepository<ReviewStat, Long>, ReviewStatRepositoryCustom {
}
