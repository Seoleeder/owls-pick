package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.repository.custom.VectorEmbeddingRepositoryCustom;
import io.github.seoleeder.owls_pick.repository.support.EmbeddingExpressions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static io.github.seoleeder.owls_pick.entity.game.QVectorEmbedding.vectorEmbedding;

@Repository
@RequiredArgsConstructor
public class VectorEmbeddingRepositoryImpl implements VectorEmbeddingRepositoryCustom {
    private final JPAQueryFactory queryFactory;
    private final EmbeddingExpressions embeddingExpressions;

    /**
     * Game ID 목록에 해당하는 기존 임베딩 데이터 일괄 조회
     */
    @Override
    public List<VectorEmbedding> findExistingEmbeddingsByGameIds(List<Long> gameIds) {
        if (gameIds == null || gameIds.isEmpty()) {
            return List.of();
        }

        return queryFactory
                .selectFrom(vectorEmbedding)
                .where(vectorEmbedding.game.id.in(gameIds))
                .fetch();
    }

    /**
     * 대상 벡터를 기준으로 코사인 거리가 가장 가까운(유사도가 높은) 상위 게임 데이터 추출
     */
    @Override
    public List<VectorEmbedding> findTopSimilarGames(float[] queryVector, int limit) {
        return queryFactory.selectFrom(vectorEmbedding)
                // 코사인 거리를 기준으로 오름차순 정렬
                .orderBy(embeddingExpressions.calculateCosineDistance(queryVector).asc())
                .limit(limit)
                .fetch();
    }
}
