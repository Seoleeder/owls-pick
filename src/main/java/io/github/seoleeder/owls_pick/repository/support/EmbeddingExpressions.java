package io.github.seoleeder.owls_pick.repository.support;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static io.github.seoleeder.owls_pick.entity.game.QGame.game;
import static io.github.seoleeder.owls_pick.entity.game.QReviewStat.reviewStat;
import static io.github.seoleeder.owls_pick.entity.game.QTag.tag;
import static io.github.seoleeder.owls_pick.entity.game.QVectorEmbedding.vectorEmbedding;

/**
 * 벡터 임베딩 파이프라인 전용 헬퍼 메서드 모음
 */
@Component
public class EmbeddingExpressions {

    /**
     * 벡터 임베딩 최소 자격 검증
     * (제목, 장르/테마 배열, 리뷰 요약 등 필수 데이터 존재 여부 검증 및 성인 게임 필터링)
     */
    public BooleanExpression isValidForEmbedding() {
        return game.title.isNotNull().and(game.title.trim().isNotEmpty())
                .and(isNotAdultGame())
                .and(hasArrayData(tag.genres).or(hasArrayData(tag.themes)))
                .and(reviewStat.reviewScoreDesc.isNotNull().and(reviewStat.reviewScoreDesc.trim().isNotEmpty()))
                .and(reviewStat.reviewSummary.isNotNull().and(reviewStat.reviewSummary.trim().isNotEmpty()));
    }

    /**
     * 성인 게임 제외 판별
     */
    private BooleanExpression isNotAdultGame() {
        return game.isAdult.isFalse();
    }

    /**
     * PostgreSQL 배열 컬럼에 데이터가 1개 이상 존재하는지 확인
     * @param path ListPath 등 모든 배열 형태의 QueryDSL 경로 수용
     */
    private BooleanExpression hasArrayData(Expression<?> path) {
        return Expressions.booleanTemplate("function('cardinality', {0}) > 0", path);
    }

    /**
     * pgvector 코사인 거리 연산 표현식 생성
     * @param queryVector 비교할 대상 벡터
     */
    public NumberTemplate<Double> calculateCosineDistance(float[] queryVector) {
//        String vectorString = Arrays.toString(queryVector);

        return Expressions.numberTemplate(Double.class,
                "function('cosine_distance', {0}, {1})",
                vectorEmbedding.embedding,
                queryVector);
    }
}
