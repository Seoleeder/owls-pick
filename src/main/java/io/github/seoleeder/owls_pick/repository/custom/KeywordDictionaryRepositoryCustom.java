package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.entity.game.KeywordDictionary;

import java.util.List;

public interface KeywordDictionaryRepositoryCustom {
    // 한글화되지 않은 영문 키워드 조회
    List<KeywordDictionary> findUnlocalizedKeywords();

    // 키워드 사전에 등록된 영문 키워드 조회
    List<String> findExistingEngNames(List<String> engNames);
}
