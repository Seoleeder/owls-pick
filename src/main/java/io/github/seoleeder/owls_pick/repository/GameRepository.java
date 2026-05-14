package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.repository.custom.GameRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameRepository extends JpaRepository<Game, Long>, GameRepositoryCustom
{

    //ITAD ID가 존재하지 않는 게임 조회
    //null이거나 NONE이거나
    //ITAD ID 값을 엔티티에 업데이트하기 위함
    List<Game> findByItadIdIsNotNullAndItadIdNot(String excludedValue);

    //ITAD ID가 있는 게임 조회
    //가격 댕신 대상 조회하기 위함
    List<Game> findByItadIdIsNotNull();

    // 가장 마지막에 수집된 게임의 IGDB ID 조회 (다음 수집 시작점)
    Optional<Game> findTopByOrderByIgdbIdDesc();

}
