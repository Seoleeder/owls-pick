package io.github.seoleeder.owls_pick.global.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.BasicType;
import org.hibernate.type.StandardBasicTypes;

/**
 * PostgreSQL 전용 연산자를 Hibernate 에서 사용하기 위해 커스텀 함수를 등록하는 클래스
 * QueryDSL에서 function()로 호출 가능
 * */
public class CustomPostgreSQLFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        SqmFunctionRegistry registry = functionContributions.getFunctionRegistry();

        // Hibernate 단일 문자열(String) 타입 매핑 객체 조회
        BasicType<String> stringType = functionContributions.getTypeConfiguration().getBasicTypeRegistry()
                .resolve(StandardBasicTypes.STRING);

        /*
         * array_contains: 배열 컬럼에 특정 단일 값이 포함되어 있는지 확인
         * 컬럼 @> CAST(ARRAY['값'] AS text[])
         */
        registry.registerPattern("array_contains", "?1 @> CAST(ARRAY[?2] AS text[])");

        /*
         * array_overlap: 두 배열 간에 겹치는 요소가 하나라도 있는지 확인 (교집합 존재 여부)
         * 컬럼 && 배열
         */
        registry.registerPattern("array_overlap", "(?1 && CAST(?2 AS text[]))");
        /*
         * similarity: 두 문자열 사이의 유사도를 0 ~ 1 사이로 반환
         * similarity(title, 'Elden')
         */
        registry.registerPattern("similarity", "similarity(cast(?1 as text), cast(?2 as text))");

        /*
         * unnest: 배열을 행(row)으로 평탄화
         */
        registry.registerPattern("unnest", "unnest(?1)", stringType);

        /*
         * array_intersect_count: 두 문자열 배열의 교집합 요소 개수 반환
         * cardinality(array(select unnest(text[]) intersect select unnest(text[])))
         */
        registry.registerPattern(
                "array_intersect_count",
                "cardinality(array(select unnest(cast(?1 as text[])) intersect select unnest(cast(?2 as text[]))))"
        );
//
//        /*
//         * cosine_distance: 코사인 거리 연산자 (<=>)를 이용한 거리 계산
//         * cosine_distance(embedding, '[0.1, 0.2, 0.3, ...]')
//         */
//        registry.registerPattern("cosine_distance", "(?1 <=> CAST(?2 AS vector))");

    }
}
