package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.game.KeywordDictionary;
import io.github.seoleeder.owls_pick.repository.custom.KeywordDictionaryRepositoryCustom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static io.github.seoleeder.owls_pick.entity.game.QKeywordDictionary.keywordDictionary;

@Repository
@RequiredArgsConstructor
public class KeywordDictionaryRepositoryImpl implements KeywordDictionaryRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    /**
     * 키워드 사전에서 한글화가 필요한 영문 키워드 목록 조회
     * (korName이 null인 데이터만 추출)
     */
    @Override
    public List<KeywordDictionary> findUnlocalizedKeywords() {
        return queryFactory.selectFrom(keywordDictionary)
                .where(keywordDictionary.korName.isNull())
                .fetch();
    }

    /**
     * 사전에 이미 등록되어 있는 영문 키워드만 필터링하여 조회
     */
    @Override
    public List<String> findExistingEngNames(List<String> engNames) {
        if (engNames == null || engNames.isEmpty()) {
            return List.of();
        }
        return queryFactory.select(keywordDictionary.engName)
                .from(keywordDictionary)
                .where(keywordDictionary.engName.in(engNames))
                .fetch();
    }
}
