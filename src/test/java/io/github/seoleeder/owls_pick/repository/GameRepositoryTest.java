package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.config.TestQueryDSLConfig;
import io.github.seoleeder.owls_pick.dto.embedding.EmbeddingSourceDto;
import io.github.seoleeder.owls_pick.dto.request.GameSearchConditionRequest;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.ReviewStat;
import io.github.seoleeder.owls_pick.entity.game.Tag;
import io.github.seoleeder.owls_pick.entity.game.enums.GenreType;
import io.github.seoleeder.owls_pick.entity.game.enums.ThemeType;
import io.github.seoleeder.owls_pick.repository.dto.GameWithReviewStatDto;
import io.github.seoleeder.owls_pick.repository.support.EmbeddingExpressions;
import io.github.seoleeder.owls_pick.repository.support.GameExpressions;
import io.github.seoleeder.owls_pick.repository.support.LocalizationExpressions;
import io.github.seoleeder.owls_pick.support.AbstractContainerBaseTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest // JPA 관련 빈만 로드
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 내장 DB 교체 방지 (Testcontainers 사용)
@Import({TestQueryDSLConfig.class, GameExpressions.class, LocalizationExpressions.class, EmbeddingExpressions.class})
public class GameRepositoryTest extends AbstractContainerBaseTest {

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocalDate targetReleaseDate;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // [Given] 출시 필터링 조건을 충족하는 기준일(일주일 전) 세팅
        targetReleaseDate = LocalDate.now().minusDays(7);
    }

    @Test
    @DisplayName("PostgreSQL 배열 연산 기반 유저 선호 태그 맞춤형 추천 및 가중치 정렬 검증")
    void findPersonalizedGamesByPreferredTags_Success() {
        // [Given] 태그 교집합 가중치 검증을 위한 게임 데이터 세팅
        Game rpgFantasyGame = Game.builder().title("RPG Fantasy Game").firstRelease(targetReleaseDate).build();
        Game indieFantasyGame = Game.builder().title("Indie Fantasy Game").firstRelease(targetReleaseDate).build();
        Game strategyGame = Game.builder().title("Strategy Survival Game").firstRelease(targetReleaseDate).build();

        gameRepository.saveAll(List.of(rpgFantasyGame, indieFantasyGame, strategyGame));

        // [Given] PostgreSQL text[] 연산 검증을 위한 태그 데이터 매핑
        Tag tag1 = Tag.builder()
                .game(rpgFantasyGame)
                .genres(List.of("RPG"))
                .themes(List.of("FANTASY"))
                .build();

        Tag tag2 = Tag.builder()
                .game(indieFantasyGame)
                .genres(List.of("INDIE"))
                .themes(List.of("FANTASY"))
                .build();

        Tag tag3 = Tag.builder()
                .game(strategyGame)
                .genres(List.of("STRATEGY"))
                .themes(List.of("SURVIVAL"))
                .build();

        tagRepository.saveAll(List.of(tag1, tag2, tag3));

        // [Given] 유저 선호 태그 파라미터 설정
        List<String> userPreferredTags = List.of("RPG", "FANTASY");

        // [When] 커스텀 연산식(array_overlap, cardinality)이 적용된 맞춤형 추천 쿼리 실행
        Page<GameWithReviewStatDto> result = gameRepository.findPersonalizedGamesByPreferredTags(
                userPreferredTags, PageRequest.of(0, 10)
        );

        // [Then] 조건 필터링 및 가중치 정렬 정합성 확인
        List<GameWithReviewStatDto> content = result.getContent();

        // 매칭 조건이 없는 데이터의 결과셋 배제 확인 (array_overlap 검증)
        assertThat(content).hasSize(2);

        // 태그 교집합 개수(가중치 점수)에 따른 내림차순 정렬 확인 (cardinality 검증)
        assertThat(content.get(0).game().getTitle()).isEqualTo("RPG Fantasy Game");
        assertThat(content.get(1).game().getTitle()).isEqualTo("Indie Fantasy Game");
    }

    @Test
    @DisplayName("유저 선호 태그 리스트가 비어있을 경우 예외 없이 빈 페이징 객체 반환")
    void findPersonalizedGamesByPreferredTags_EmptyTags_ReturnEmptyPage() {
        // [Given] 유저 선호 태그가 없는 코너 케이스 파라미터 세팅
        List<String> emptyPreferredTags = List.of();

        // [When] 빈 태그 리스트로 맞춤형 추천 쿼리 실행
        Page<GameWithReviewStatDto> result = gameRepository.findPersonalizedGamesByPreferredTags(
                emptyPreferredTags, PageRequest.of(0, 10)
        );

        // [Then] 구문 예외(Syntax Error) 발생 없이 빈 페이징 객체 반환 확인
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("통합 검색 쿼리 스트레스 테스트 (다중 커스텀 함수 및 조인 충돌 검증)")
    void searchGames_AllFilters_Success() {
        // [Given] 통합 검색 대상 게임 데이터 세팅
        Game targetGame = Game.builder()
                .title("Elden Ring")
                .firstRelease(targetReleaseDate)
                .build();
        gameRepository.save(targetGame);

        // [Given] 배열 연산 검증용 태그 데이터 매핑
        Tag tag = Tag.builder()
                .game(targetGame)
                .genres(List.of("RPG", "ADVENTURE"))
                .themes(List.of("FANTASY"))
                .build();
        tagRepository.save(tag);

        // [Given] similarity 및 array_overlap 등 모든 커스텀 표현식이 발동하는 다중 검색 조건 설정
        GameSearchConditionRequest condition = new GameSearchConditionRequest(
                "Elden",
                List.of(GenreType.RPG),
                List.of(ThemeType.FANTASY),
                null, null, null, null, null, null  // 나머지 가격/플탐 조건 생략
        );

        // [When] 복합 커스텀 함수가 포함된 다중 조건 통합 검색 쿼리 실행
        Page<GameWithReviewStatDto> result = gameRepository.searchGames(condition, PageRequest.of(0, 10));

        // [Then] 쿼리 정상 파싱 및 검색 조건 필터링 정합성 확인
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).game().getTitle()).isEqualTo("Elden Ring");
    }

    @Test
    @DisplayName("한글화가 필요한 게임(descriptionKo 누락 등) 목록 조회 검증")
    void findUnlocalizedGames_Success() {
        // [Given] 한글화 누락 데이터 및 완료 데이터 세팅
        Game unlocalizedGame = Game.builder()
                .title("Unlocalized Game")
                .description("English Description Only")
                .descriptionKo(null)
                .build();

        Game localizedGame = Game.builder()
                .title("Localized Game")
                .description("English Description")
                .descriptionKo("한국어 설명 완료")
                .build();

        // [Given] 한글화가 누락되었으나 '실패 이력'이 존재하는 데이터 세팅
        Game failedGame = Game.builder()
                .title("Failed Unlocalized Game")
                .description("English Description")
                .descriptionKo(null)
                .build();

        gameRepository.saveAll(List.of(unlocalizedGame, localizedGame));

        // 실패 이력 강제 삽입
        jdbcTemplate.update(
                "INSERT INTO genai_failed_task (pipeline_type, target_id, fail_reason, is_handled, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, NOW(), NOW())",
                "GAME_LOCALIZATION", failedGame.getId(), "NETWORK_ERROR", false
        );

        // [When] 한글화 대상(결측치) 조회 쿼리 실행
        List<Game> result = gameRepository.findUnlocalizedGames(10);

        // [Then] 한글화 누락 조건 필터링 정합성 확인
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Unlocalized Game");
    }

    @Test
    @DisplayName("임베딩 생성이 필요한 원본 게임 데이터(Vector 데이터 없는 게임) 조회 검증")
    void findGamesForEmbedding_Success() {
        // [Given] 임베딩 최소 자격 요건을 만족하는 게임 데이터 세팅
        Game targetGame = Game.builder()
                .title("Target Vector Game")
                .description("Desc")
                .isAdult(false)
                .build();
        gameRepository.save(targetGame);

        // [Given] 태그 배열 자격 요건 세팅 (genres 데이터 존재)
        Tag tag = Tag.builder()
                .game(targetGame)
                .genres(List.of("ACTION"))
                .build();
        tagRepository.save(tag);

        // [Given] 리뷰 스탯 자격 요건 세팅 및 임베딩 결측 유도
        ReviewStat reviewStat = ReviewStat.builder()
                .game(targetGame)
                .reviewScoreDesc("Very Positive")
                .reviewSummary("Great Action Game")
                .build();

        entityManager.persist(reviewStat);

        // [Given] 임베딩 실패 이력(FAILED)이 존재하는 게임 데이터 세팅
        Game failedGame = Game.builder().title("Failed Vector Game").description("Desc").isAdult(false).build();
        gameRepository.save(failedGame);

        Tag failedTag = Tag.builder().game(failedGame).genres(List.of("ACTION")).build();
        tagRepository.save(failedTag);

        ReviewStat failedStat = ReviewStat.builder().game(failedGame).reviewScoreDesc("Mixed").reviewSummary("Not bad").build();
        entityManager.persist(failedStat);

        // 실패 임베딩 레코드 강제 삽입 (vectorEmbedding.isNull() 조건에 의해 배제되는지 확인)
        jdbcTemplate.update(
                "INSERT INTO vector_embedding (game_id, embedding_status, source_text) VALUES (?, ?, ?)",
                failedGame.getId(), "FAILED", "test_source"
        );

        entityManager.clear();

        // [When] 임베딩 대상 추출 쿼리 실행
        List<EmbeddingSourceDto> result = gameRepository.findGamesForEmbedding(10);

        // [Then] 복합 자격 검증(cardinality, 결측치 등) 통과 및 DTO 프로젝션 확인
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Target Vector Game");
        assertThat(result.get(0).genres()).containsExactly("ACTION");
    }

}
