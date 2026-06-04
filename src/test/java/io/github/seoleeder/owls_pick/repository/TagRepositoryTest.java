package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.config.TestQueryDSLConfig;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.Tag;
import io.github.seoleeder.owls_pick.repository.support.EmbeddingExpressions;
import io.github.seoleeder.owls_pick.repository.support.GameExpressions;
import io.github.seoleeder.owls_pick.repository.support.LocalizationExpressions;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestQueryDSLConfig.class, GameExpressions.class, LocalizationExpressions.class, EmbeddingExpressions.class})
public class TagRepositoryTest extends AbstractContainerBaseTest {

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private GameRepository gameRepository;

    @Test
    @DisplayName("PostgreSQL unnest 함수를 활용한 배열 내 고유 키워드 추출 검증")
    void findAllDistinctKeywords_Success() {
        // [Given] 중복 키워드를 포함하는 태그 배열 데이터 세팅
        Game game1 = gameRepository.save(Game.builder().title("Game 1").build());
        Game game2 = gameRepository.save(Game.builder().title("Game 2").build());

        Tag tag1 = Tag.builder().game(game1).keywords(List.of("ACTION", "RPG")).build();
        Tag tag2 = Tag.builder().game(game2).keywords(List.of("RPG", "FANTASY")).build();

        tagRepository.saveAll(List.of(tag1, tag2));

        // [When] unnest 함수가 적용된 SELECT 프로젝션 쿼리 실행
        List<String> distinctKeywords = tagRepository.findAllDistinctKeywords();

        // [Then] 배열 평탄화 및 중복 제거를 통한 고유 키워드 추출 결과 확인
        assertThat(distinctKeywords).hasSize(3);
        assertThat(distinctKeywords).containsExactlyInAnyOrder("ACTION", "RPG", "FANTASY");
    }

    @Test
    @DisplayName("cardinality 함수 비교 기반 키워드 한글화 누락 태그 필터링 검증")
    void findTagsNeedingKeywordLocalization_Success() {
        // [Given] 영/한 키워드 배열 크기가 불일치하는 타겟 결측 데이터 세팅
        Game targetGame = gameRepository.save(Game.builder().title("Target").build());
        Game validGame = gameRepository.save(Game.builder().title("Valid").build());

        Tag targetTag = Tag.builder()
                .game(targetGame)
                .keywords(List.of("APPLE", "BANANA")) // 영문 2개
                .keywordsKo(List.of("사과"))            // 한글 1개 (개수 불일치 -> 타겟)
                .build();

        Tag validTag = Tag.builder()
                .game(validGame)
                .keywords(List.of("ORANGE"))
                .keywordsKo(List.of("오렌지"))           // 개수 일치 -> 정상 데이터
                .build();

        tagRepository.saveAll(List.of(targetTag, validTag));

        // [When] 배열 크기(cardinality) 비교 로직 포함 쿼리 실행
        List<Tag> results = tagRepository.findTagsNeedingKeywordLocalization(10);

        // [Then] 배열 크기 불일치 조건에 따른 타겟 데이터 필터링 정합성 확인
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getGame().getTitle()).isEqualTo("Target");
    }
}
