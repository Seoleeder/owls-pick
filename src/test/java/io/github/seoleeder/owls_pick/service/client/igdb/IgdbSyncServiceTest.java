package io.github.seoleeder.owls_pick.service.client.igdb;

import io.github.seoleeder.owls_pick.client.igdb.IgdbDataCollector;
import io.github.seoleeder.owls_pick.client.igdb.dto.IgdbGameDetailResponse;
import io.github.seoleeder.owls_pick.client.igdb.dto.IgdbGameSummaryResponse;
import io.github.seoleeder.owls_pick.global.util.TimestampUtils;
import io.github.seoleeder.owls_pick.entity.game.*;
import io.github.seoleeder.owls_pick.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IgdbSyncServiceTest {

    @InjectMocks
    private IgdbSyncService igdbSyncService;

    @Mock private IgdbDataCollector collector;
    @Mock private GameRepository gameRepository;
    @Mock private StoreDetailRepository storeDetailRepository;
    @Mock private TagRepository tagRepository;
    @Mock private ScreenshotRepository screenshotRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private GameCompanyRepository gameCompanyRepository;
    @Mock private LanguageSupportRepository languageSupportRepository;
    @Mock private TransactionTemplate transactionTemplate;

    @Captor private ArgumentCaptor<List<Game>> gameListCaptor;
    @Captor private ArgumentCaptor<List<Tag>> tagListCaptor;
    @Captor private ArgumentCaptor<List<Game>> deletedGameCaptor;

    @Captor private ArgumentCaptor<Iterable<GameCompany>> gameCompanyCaptor;

    @BeforeEach
    void setUp() {
        // 반환값 유무에 따른 TransactionTemplate 람다 콜백 강제 실행
        lenient().doAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());

        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("[초기 수집] 스팀 ID 매핑, 기존 상세 데이터 삭제 및 신규 저장 검증")
    void backfillAllGames_Success() {
        // [Given] 초기 수집을 위해 마지막으로 저장된 IGDB ID 조회 시 빈 값을 반환하도록 모킹
        given(gameRepository.findTopByOrderByIgdbIdDesc()).willReturn(Optional.empty());

        // [Given] IGDB Summary 응답 구성
        IgdbGameSummaryResponse.ExternalApp externalApp = new IgdbGameSummaryResponse.ExternalApp("100", 1);
        IgdbGameSummaryResponse summary = new IgdbGameSummaryResponse(
                55L, List.of(externalApp), null, null, null, null, "Test Desc",
                null, null, 1700000000L, null, null, null, null, 0
        );

        // 배치 루프 종료 조건 설정 (1회차 응답, 2회차 Empty 반환)
        given(collector.collectGameSummary(anyLong()))
                .willReturn(List.of(summary))
                .willReturn(Collections.emptyList());

        // [Given] 스팀 ID(100)를 보유한 타겟 게임 엔티티 매핑 상태 구성
        Game existingGame = Game.builder().id(1L).title("Old Title").build();
        StoreDetail detail = StoreDetail.builder().game(existingGame).storeName(StoreDetail.StoreName.STEAM).storeAppId("100").build();

        given(storeDetailRepository.findByStoreNameAndStoreAppIdIn(any(), anyList())).willReturn(List.of(detail));
        given(gameRepository.saveAll(anyList())).willReturn(List.of(existingGame));
        given(gameRepository.getReferenceById(1L)).willReturn(existingGame);

        // [Given] IGDB Detail 데이터 응답 시 특정 장르(RPG)를 포함하도록 모킹
        IgdbGameDetailResponse.Genre genre = new IgdbGameDetailResponse.Genre(1L, "RPG");
        IgdbGameDetailResponse detailRes = new IgdbGameDetailResponse(
                55L, null, List.of(genre), null, null, null, null, null
        );
        given(collector.collectGameDetail(anyList())).willReturn(List.of(detailRes));

        // [When] IGDB 대량 데이터 동기화 파이프라인 실행
        igdbSyncService.backfillAllGames();

        // [Then] 요약 데이터(Summary)와 상세 데이터(Detail) 갱신 흐름에 따라 saveAll 2회 호출 검증
        verify(gameRepository, times(2)).saveAll(gameListCaptor.capture());
        Game savedGame = gameListCaptor.getAllValues().get(0).get(0);
        assertThat(savedGame.getIgdbId()).isEqualTo(55L);

        // [Then] 상세 데이터 삽입 전 기존 연관 태그가 삭제(deleteByGameIn)되었는지 검증
        verify(tagRepository, times(1)).deleteByGameIn(deletedGameCaptor.capture());
        assertThat(deletedGameCaptor.getValue().get(0).getId()).isEqualTo(1L);

        // [Then] Tag 엔티티 벌크 인서트 시 응답 태그 (RPG 장르) 맵핑 정합성 검증
        verify(tagRepository, times(1)).saveAll(tagListCaptor.capture());
        List<Tag> savedTags = tagListCaptor.getValue();
        assertThat(savedTags).hasSize(1);
        assertThat(savedTags.get(0).getGenres()).contains("RPG");
    }

    @Test
    @DisplayName("[정기 업데이트] 타임스탬프 기준 갱신 데이터 수집 및 상태 업데이트 검증")
    void syncUpdatedGames_Success() {
        // [Given] DB에 기록된 업데이트 시각을 특정 타임스탬프로 모킹
        LocalDateTime lastUpdate = LocalDateTime.of(2024, 1, 1, 0, 0);
        long newUpdateEpoch = 1704153600L;
        LocalDateTime expectedTime = TimestampUtils.toLocalDateTime(newUpdateEpoch);

        given(gameRepository.findMaxIgdbUpdatedAt()).willReturn(Optional.of(lastUpdate));

        // [Given] 변경된 설명(Description)과 갱신된 타임스탬프를 가진 API 응답 모킹
        IgdbGameSummaryResponse.ExternalApp externalApp = new IgdbGameSummaryResponse.ExternalApp("200", 1);
        IgdbGameSummaryResponse updatedSummary = new IgdbGameSummaryResponse(
                77L, List.of(externalApp), Collections.emptyList(), null, null,
                Collections.emptyList(), "Updated Description",
                null, 1704153600L, newUpdateEpoch,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, 0
        );

        given(collector.collectUpdatedGameSummary(anyLong()))
                .willReturn(List.of(updatedSummary))
                .willReturn(Collections.emptyList());

        // [Given] 업데이트 대상이 될 기존 게임 엔티티와 스토어 매핑 정보 세팅
        Game existingGame = Game.builder().id(10L).igdbId(77L).description("Old Description").build();
        StoreDetail detail = StoreDetail.builder().game(existingGame).storeAppId("200").build();

        given(storeDetailRepository.findByStoreNameAndStoreAppIdIn(any(), anyList())).willReturn(List.of(detail));
        given(gameRepository.saveAll(anyList())).willReturn(List.of(existingGame));

        // [When] IGDB 데이터 정기 업데이트 로직 실행
        igdbSyncService.syncUpdatedGames();

        // [Then] 기존 엔티티의 설명과 업데이트 시각이 최신 값으로 갱신되었는지 확인 (Dirty Checking 검증)
        assertThat(existingGame.getDescription()).isEqualTo("Updated Description");
        assertThat(existingGame.getIgdbUpdatedAt()).isEqualTo(expectedTime);
    }

    @Test
    @DisplayName("[비즈니스 룰] 단일 회사의 개발사/퍼블리셔 역할 병합 검증")
    void syncDetails_MergeCompanyRoles() {
        // [Given] 하나의 회사가 개발과 퍼블리싱을 분리해서 응답하는 형태의 API 결과 모킹
        IgdbGameDetailResponse.Company.CompanyDetail cd =
                new IgdbGameDetailResponse.Company.CompanyDetail(1L, null, "FromSoftware", null);

        IgdbGameDetailResponse.Company devRole = new IgdbGameDetailResponse.Company(10L, cd, true, false);
        IgdbGameDetailResponse.Company pubRole = new IgdbGameDetailResponse.Company(20L, cd, false, true);

        IgdbGameDetailResponse detailRes = new IgdbGameDetailResponse(
                55L, null, null, null, null, List.of(devRole, pubRole), null, null
        );

        // [Given] DB에 해당 게임과 회사 정보가 이미 존재하는 상태로 세팅
        Game game = Game.builder().id(1L).igdbId(55L).build();
        Company company = Company.builder().id(100L).name("FromSoftware").build();

        given(companyRepository.findByNameIn(anyList())).willReturn(List.of(company));
        given(gameRepository.getReferenceById(1L)).willReturn(game);

        // [Given] 상세 데이터를 매핑하기 위해, 선행 조건인 IGDB 주요 데이터 응답 세팅
        given(gameRepository.findTopByOrderByIgdbIdDesc()).willReturn(Optional.empty());
        IgdbGameSummaryResponse.ExternalApp externalApp = new IgdbGameSummaryResponse.ExternalApp("100", 1);
        IgdbGameSummaryResponse summary = new IgdbGameSummaryResponse(
                55L, List.of(externalApp), null, null, null, null, "Desc",
                null, null, 1700000000L, null, null, null, null, 0
        );
        given(collector.collectGameSummary(anyLong())).willReturn(List.of(summary)).willReturn(Collections.emptyList());
        StoreDetail detail = StoreDetail.builder().game(game).storeName(StoreDetail.StoreName.STEAM).storeAppId("100").build();
        given(storeDetailRepository.findByStoreNameAndStoreAppIdIn(any(), anyList())).willReturn(List.of(detail));
        given(gameRepository.saveAll(anyList())).willReturn(List.of(game));
        given(collector.collectGameDetail(anyList())).willReturn(List.of(detailRes));

        // [When] 대량 수집 및 상세 데이터 매핑 로직 실행
        igdbSyncService.backfillAllGames();

        // [Then] GameCompany가 중복으로 생성되지 않고, 개발사와 퍼블리셔 여부가 true로 병합되었는지 검증
        verify(gameCompanyRepository, times(1)).saveAll(gameCompanyCaptor.capture());

        List<GameCompany> savedCompanies = new ArrayList<>();
        gameCompanyCaptor.getValue().forEach(savedCompanies::add);

        assertThat(savedCompanies).hasSize(1);
        assertThat(savedCompanies.get(0).isDeveloper()).isTrue();
        assertThat(savedCompanies.get(0).isPublisher()).isTrue();
    }
}