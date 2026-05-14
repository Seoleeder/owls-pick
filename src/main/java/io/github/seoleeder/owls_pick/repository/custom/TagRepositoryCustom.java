package io.github.seoleeder.owls_pick.repository.custom;

import java.util.List;
import io.github.seoleeder.owls_pick.entity.game.Tag;

public interface TagRepositoryCustom {
    // 전체 키워드 태그에서 중복을 제거한 고유 영문 키워드 목록 조회
    List<String> findAllDistinctKeywords();

    // 한글 키워드(keywordsKo) 업데이트가 필요한 태그 목록 조회
    List<Tag> findTagsNeedingKeywordLocalization(int chunkSize);
}
