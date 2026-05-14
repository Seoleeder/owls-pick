package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.game.Playtime;
import io.github.seoleeder.owls_pick.repository.custom.PlaytimeRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaytimeRepository extends JpaRepository<Playtime, Long>, PlaytimeRepositoryCustom {
}
