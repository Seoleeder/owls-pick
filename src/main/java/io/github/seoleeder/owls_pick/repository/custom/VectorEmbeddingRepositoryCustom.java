package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;

import java.util.List;

public interface VectorEmbeddingRepositoryCustom {

    // Game ID 목록에 해당하는 기존 임베딩 데이터 일괄 조회
    List<VectorEmbedding> findExistingEmbeddingsByGameIds(List<Long> gameIds);

    // 대상 벡터를 기준으로 코사인 유사도가 가장 높은 상위 게임 데이터 조회
    List<VectorEmbedding> findTopSimilarGames(float[] queryVector, int limit);
}
