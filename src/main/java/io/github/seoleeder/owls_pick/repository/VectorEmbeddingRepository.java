package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.repository.custom.VectorEmbeddingRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VectorEmbeddingRepository extends JpaRepository<VectorEmbedding, Long>, VectorEmbeddingRepositoryCustom {
}
