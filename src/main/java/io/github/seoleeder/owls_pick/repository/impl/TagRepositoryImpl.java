package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringTemplate;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.Tag;
import io.github.seoleeder.owls_pick.repository.custom.TagRepositoryCustom;
import io.github.seoleeder.owls_pick.repository.support.LocalizationExpressions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static io.github.seoleeder.owls_pick.entity.game.QTag.tag;

@Repository
@RequiredArgsConstructor
public class TagRepositoryImpl implements TagRepositoryCustom {
    private final JPAQueryFactory queryFactory;
    private final LocalizationExpressions localizationExpressions;

    /**
     * KeywordDictionary 동기화를 위한 고유 영문 키워드 목록 추출
     */
    @Override
    public List<String> findAllDistinctKeywords() {

        // unnest를 사용하여 키워드의 모든 요소들을 중복 없이 추출
        StringTemplate unnestKeywords = Expressions.stringTemplate("function('unnest', {0})", tag.keywords);

        return queryFactory.select(unnestKeywords)
                .from(tag)
                .where(localizationExpressions.hasKeywords())
                .distinct()
                .fetch();
    }

    /**
     * 한글 키워드(keywords_ko) 업데이트가 필요한 태그 목록 조회
     */
    @Override
    public List<Tag> findTagsNeedingKeywordLocalization(int chunkSize) {
        return queryFactory.selectFrom(tag)
                .where(localizationExpressions.needsKeywordLocalization())
                .limit(chunkSize)
                .fetch();
    }
}
