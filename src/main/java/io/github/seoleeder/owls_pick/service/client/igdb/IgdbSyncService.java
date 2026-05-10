package io.github.seoleeder.owls_pick.service.client.igdb;

import io.github.seoleeder.owls_pick.client.igdb.IgdbDataCollector;
import io.github.seoleeder.owls_pick.client.igdb.dto.IgdbGameDetailResponse;
import io.github.seoleeder.owls_pick.client.igdb.dto.IgdbGameSummaryResponse;
import io.github.seoleeder.owls_pick.entity.game.enums.GameModeType;
import io.github.seoleeder.owls_pick.entity.game.enums.PerspectiveType;
import io.github.seoleeder.owls_pick.global.util.TimestampUtils;
import io.github.seoleeder.owls_pick.entity.game.*;
import io.github.seoleeder.owls_pick.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IgdbSyncService {
    private final IgdbDataCollector collector;

    // Repositories
    private final GameRepository gameRepository;
    private final StoreDetailRepository storeDetailRepository;
    private final TagRepository tagRepository;
    private final ScreenshotRepository screenshotRepository;
    private final CompanyRepository companyRepository;
    private final GameCompanyRepository gameCompanyRepository;
    private final LanguageSupportRepository languageSupportRepository;

    private final TransactionTemplate transactionTemplate;

    /**
     * 초기 대량 수집 메서드 (ID 기준)
     * 조건에 맞는 게임 데이터 수집
     * ID = 0 부터 순차적으로 수집하되, 중단 시 가장 마지막으로 저장된 ID부터 이어서 수집
     * */
    public void backfillAllGames() {
        log.info("Starting IGDB Full Backfill (ID based)...");

        //DB에서 가장 높은 IGDB ID를 조회해서 해당 위치부터 시작
        long lastId = gameRepository.findTopByOrderByIgdbIdDesc()
                .map(Game::getIgdbId)
                .orElse(0L);

        log.info("Resuming backfill from ID: {}", lastId);

        while (true) {
            try {
                // Collector 호출 (ID 기준 수집)
                List<IgdbGameSummaryResponse> summaries = collector.collectGameSummary(lastId);

                if (summaries.isEmpty()) {
                    log.info("Backfill Completed! Last processed ID: {}", lastId);
                    break;
                }

                // 게임 테이블 공통 배치 처리
                processBatch(summaries);

                // 커서 업데이트 (마지막 ID)
                lastId = summaries.getLast().igdbId();
                log.debug("Processed batch up to ID: {}", lastId);

                // Rate Limit 방지 (IGDB 정책 준수 - 초당 4회 제한)
                sleep(250);

            } catch (RestClientException e) {
                log.warn("IGDB API Error at ID {}: {}", lastId, e.getMessage());
                sleep(5000);
            }
            catch (Exception e) {
                log.error("Backfill paused at ID: {}", lastId, e);
                sleep(5000); // 에러 시 잠시 대기 후 재시도
            }
        }
    }

    /**
     * IGDB 데이터 정기 업데이트
     * DB의 '최종 수정 시각' 이후에 변경된 데이터만 수집
     */
    public void syncUpdatedGames() {
        // IGDB 데이터 최종 수정 시각 조회 (Epoch time)
        long lastTimestamp = gameRepository.findMaxIgdbUpdatedAt()
                        .map(TimestampUtils::toEpoch)
                                .orElse(0L);

        log.info("Starting Update Sync (Timestamp based) from: {}", lastTimestamp);

        while (true) {
            try {
                // Collector 호출 (Timestamp 기준 수집 - 신규 및 수정 감지)
                List<IgdbGameSummaryResponse> summaries = collector.collectUpdatedGameSummary(lastTimestamp);

                if (summaries.isEmpty()) {
                    log.info("No more updates found.");
                    break;
                }

                // 게임 테이블 공통 배치 처리
                processBatch(summaries);

                // 커서 업데이트 (마지막 수정 시간)
                lastTimestamp = summaries.getLast().updatedAt();
                log.debug("Synced batch up to timestamp: {}", lastTimestamp);

                sleep(250);

            }catch (RestClientException e) {
                log.warn("IGDB API Error at Timestamp {}: {}", lastTimestamp, e.getMessage());
                sleep(5000);
            }
            catch (Exception e) {
                log.error("Update Sync Failed", e);
                break;
            }
        }
    }

    /**
     * 500개 단위의 게임 주요 데이터를 저장하고, 상세 정보(Company, Language Support, Screenshot, Tag)까지 저장
     */

    protected void processBatch(List<IgdbGameSummaryResponse> summaries) {
        // Step 1: Summary 저장 (Game 테이블 + StoreDetail 연결)
        List<Game> savedGames;
        try {
            // 템플릿을 블록({})처럼 사용 -> 가독성 좋음
            savedGames = transactionTemplate.execute(status ->
                    upsertGamesSummaries(summaries)
            );
        } catch (Exception e) {
            log.error("Failed to save Game Summaries batch.", e);
            return; // 기본 정보 저장 실패하면 중단
        }

        if (savedGames == null || savedGames.isEmpty()) return;

        // Step 2: 상세 정보 수집을 위한 ID 추출
        List<Long> igdbIds = savedGames.stream().map(Game::getIgdbId).toList();

        // Step 3: 상세 정보 API 호출 (Collector 내부에서 Batch 처리됨)
        List<IgdbGameDetailResponse> details;
        try {
            details = collector.collectGameDetail(igdbIds);

        } catch (RestClientException e) {
            log.warn("IGDB API Error. Skipping details for this batch: {}", e.getMessage());
            return;
        } catch (Exception e) {
            log.error("Unexpected error during API call.", e);
            return;
        }

        if (details == null || details.isEmpty()) return;

        // Step 4: 연관 테이블 저장 (Tag, Screenshot, Company, Language)
        try {
            List<Game> finalSavedGames = savedGames;
            List<IgdbGameDetailResponse> finalDetails = details;

            transactionTemplate.executeWithoutResult(status ->
                    syncDetails(finalSavedGames, finalDetails)
            );
            log.debug("Successfully processed details for {} games in this batch.", finalSavedGames.size());
        } catch (Exception e) {
            log.error("Failed to save Game Details batch.", e);
        }
    }



    /**
     * 게임 주요 데이터 저장 & 업데이트
     * 세부 구현: Summary -> StoreDetail 조회 (Steam ID와 IGDB ID 매핑) -> Game 엔티티 업데이트
     * */
    private List<Game> upsertGamesSummaries(List<IgdbGameSummaryResponse> summaries) {
        // IGDB 응답에서 Steam AppID 추출 및 맵핑 (Key: SteamAppID, Value: DTO)
        Map<String, IgdbGameSummaryResponse> steamIdToDtoMap = new HashMap<>();
        List<String> steamAppIds = new ArrayList<>();

        for (IgdbGameSummaryResponse dto : summaries) {
            String steamId = extractSteamId(dto);
            if (steamId != null) {
                steamAppIds.add(steamId);
                steamIdToDtoMap.put(steamId, dto);
            }
        }

        if (steamAppIds.isEmpty()) return List.of();

        // 스팀 ID로 이미 DB에 등록되어 있는 스팀 게임 조회
        List<StoreDetail> existingDetails = storeDetailRepository.findByStoreNameAndStoreAppIdIn(
                StoreDetail.StoreName.STEAM,
                steamAppIds
        );

        List<Game> gamesToUpdate = new ArrayList<>();

        // Game 엔티티 업데이트
        for (StoreDetail detail : existingDetails) {
            String steamId = detail.getStoreAppId();
            IgdbGameSummaryResponse dto = steamIdToDtoMap.get(steamId);

            if (dto != null) {
                Game game = detail.getGame(); // 기존 게임 엔티티

                if(game.getIgdbId() == null){
                    game.connectToIgdb(dto.igdbId());
                }

                // Epoch Time -> LocalDate, LocalDateTime
                // IGDB 기준 수정 시각, 게임 최초 출시일
                LocalDateTime igdbUpdatedAt = TimestampUtils.toLocalDateTime(dto.updatedAt());
                LocalDate firstRelease = TimestampUtils.toLocalDate(dto.first_release());

                // 한글화 타이틀, 심의 데이터 추출
                String localTitle = extractLocalization(dto); // Region ID (KR : 2)
                Ratings ratings = extractAgeRatings(dto.ageRatings()); // GRAC(국내 표준), ESRB(US 표준) 분리

                // 게임 커버 ID, 게임 타입, 게임 출시 상태 추출
                String coverId = (dto.cover() != null) ? dto.cover().imageId() : null;
                String typeName = (dto.type() != null) ? dto.type().type() : null;
                String statusName = (dto.gameStatus() != null) ? dto.gameStatus().status() : null;

                // 리스트에서 DB에 저장될 '이름' 데이터만 추출
                List<String> platforms = extractValues(dto.platforms(), IgdbGameSummaryResponse.Platform::name);
                List<GameModeType> modes = extractValues(dto.modes(), IgdbGameSummaryResponse.GameMode::name)
                        .stream()
                        .map(GameModeType::fromEngName)
                        .distinct()
                        .toList();

                List<PerspectiveType> perspectives = extractValues(dto.perspectives(), IgdbGameSummaryResponse.Perspective::name)
                        .stream()
                        .map(PerspectiveType::fromEngName)
                        .distinct()
                        .toList();

                game.updateFromSummary(// Title (필요 시 로직 수정)
                        localTitle,
                        typeName,
                        statusName,
                        platforms,
                        dto.description(),
                        dto.storyline(),
                        firstRelease,
                        dto.hypes(),
                        coverId,
                        ratings.kr(),
                        ratings.esrb(),
                        modes,
                        perspectives,
                        igdbUpdatedAt
                );

                gamesToUpdate.add(game);
            }
        }

        return gameRepository.saveAll(gamesToUpdate);
    }

    /**
     * 게임 상세 데이터 저장 & 업데이트
     * Tag, Screenshot, Language Support, Company
     * */
    private void syncDetails(List<Game> games, List<IgdbGameDetailResponse> details) {

        //igdbId를 추출해서 detail 응답과 매핑
        Map<Long, IgdbGameDetailResponse> detailMap = details.stream()
                .collect(Collectors.toMap(IgdbGameDetailResponse::igdbId, Function.identity()));

        // 배치 저장을 위한 리스트
        List<Tag> tags = new ArrayList<>();
        List<Screenshot> screenshots = new ArrayList<>();
        List<LanguageSupport> languages = new ArrayList<>();

        //GameCompanyId 중복 키 방지
        Map<GameCompanyId, GameCompany> gameCompanyMap = new HashMap<>();

        // 이번 배치에 등장하는 모든 회사 이름 수집 (중복 제거)
        Set<String> companyNames = new HashSet<>();
        for (IgdbGameDetailResponse dto : details) {
            if (dto.companies() != null) {
                dto.companies().stream()
                        .map(IgdbGameDetailResponse.Company::companyDetail)
                        .filter(Objects::nonNull)
                        .map(IgdbGameDetailResponse.Company.CompanyDetail::name) // 이름 추출
                        .filter(name -> name != null && !name.isBlank())
                        .forEach(companyNames::add);
            }
        }

        // DB 조회 & 맵핑 준비 (Key: 회사이름, Value: Company 엔티티)
        Map<String, Company> savedCompanyMap = new HashMap<>();

        if (!companyNames.isEmpty()) {
            // DB에 이미 있는 회사들 조회
            List<Company> existingCompanies = companyRepository.findByNameIn(List.copyOf(companyNames));
            existingCompanies.forEach(c -> savedCompanyMap.put(c.getName(), c));

            // DB에 없는 회사 찾아서 생성
            List<Company> newCompanies = new ArrayList<>();
            for (String name : companyNames) {
                if (!savedCompanyMap.containsKey(name)) {
                    newCompanies.add(Company.builder()
                            .name(name)
                            .build());
                }
            }

            // 신규 회사 저장 후 맵에 추가
            if (!newCompanies.isEmpty()) {
                List<Company> saved = companyRepository.saveAll(newCompanies);
                saved.forEach(c -> savedCompanyMap.put(c.getName(), c));
            }
        }

        // 게임별 상세 데이터 매핑
        for (Game game : games) {
            IgdbGameDetailResponse dto = detailMap.get(game.getIgdbId());
            if (dto == null) continue;

            // gameProxy (영속성 컨텍스트가 관리하는 가짜 객체) 생성
            // ID만 연결하는 작업이므로 DB 조회를 하지 X
            Game gameProxy = gameRepository.getReferenceById(game.getId());

            // Tags (1:1)
            // 장르, 테마, 키워드 데이터 추출 후 저장
            Tag tag = Tag.builder()
                    .game(gameProxy)
                    .genres(extractValues(dto.genres(), IgdbGameDetailResponse.Genre::name))
                    .themes(extractValues(dto.themes(), IgdbGameDetailResponse.Theme::name))
                    .keywords(extractValues(dto.keywords(), IgdbGameDetailResponse.Keyword::name))
                    .build();
            tags.add(tag);

            // 수집된 각 게임들에 대해 태그로 성인 게임 여부 판단
            game.evaluateAdultStatus(tag);

            // Screenshot (1:N)
            if (dto.screenshots() != null) {
                dto.screenshots().forEach(s -> screenshots.add(
                        Screenshot.builder()
                                .game(gameProxy).imageId(s.imageId()).width(s.width()).height(s.height())
                                .build()));
            }

            // Language (1:N)
            if (dto.languageSupports() != null) {
                // [언어, 지원 타입] 으로 매핑
                Map<String, Set<String>> langMap = new HashMap<>();

                for (var l : dto.languageSupports()) {
                    // 언어 이름이 없으면 패스
                    if (l.languageInfo() == null || l.languageInfo().name() == null) continue;

                    String langName = l.languageInfo().name();
                    String supportType = (l.supportType() != null) ? l.supportType().name() : "";

                    // 맵에 해당 언어 키가 없으면 생성하고, 지원 타입 추가
                    langMap.computeIfAbsent(langName, k -> new HashSet<>()).add(supportType);
                }

                // 모아진 데이터를 엔티티로 변환 (언어당 1개의 Row 생성)
                for (Map.Entry<String, Set<String>> entry : langMap.entrySet()) {
                    String langName = entry.getKey();
                    Set<String> types = entry.getValue();

                    languages.add(LanguageSupport.builder()
                            .game(gameProxy)
                            .language(langName)
                            .voiceSupport(types.contains("Audio"))
                            .subtitle(types.contains("Subtitles"))
                            .interSupport(types.contains("Interface"))
                            .build());
                }
            }

            // GameCompany 매핑 테이블 생성
            if (dto.companies() != null) {
                dto.companies().forEach(c -> {
                    if (c.companyDetail() != null) {
                        String companyName = c.companyDetail().name();
                        Company company = savedCompanyMap.get(companyName);

                        if (company != null) {
                            // 복합키 생성 (명시적으로 ID 주입)
                            GameCompanyId compoundId = new GameCompanyId(gameProxy.getId(), company.getId());

                            // 맵을 이용해 이미 같은 키가 있으면 기존 객체의 상태만 업데이트
                            gameCompanyMap.compute(compoundId, (id, existing) -> {
                                if (existing == null) {
                                    return GameCompany.builder()
                                            .id(id)
                                            .game(gameProxy)
                                            .company(company)
                                            .isDeveloper(c.isDeveloper())
                                            .isPublisher(c.isPublisher())
                                            .build();
                                } else {
                                    // 이미 존재하면 개발사/퍼블리셔 여부를 추가로 체크 (병합)
                                    existing.updateRoles(c.isDeveloper(), c.isPublisher());
                                    return existing;
                                }
                            });
                        }
                    }
                });
            }

        }
        // 데이터 저장

        // 기존 연관 데이터 삭제
        if (!games.isEmpty()) {
            tagRepository.deleteByGameIn(games);
            screenshotRepository.deleteByGameIn(games);
            languageSupportRepository.deleteByGameIn(games);
            gameCompanyRepository.deleteByGameIn(games);
        }

        // Tag는 1:1이므로 바로 saveAll
        tagRepository.saveAll(tags);

        // 나머지 데이터 저장
        screenshotRepository.saveAll(screenshots);
        languageSupportRepository.saveAll(languages);
        gameCompanyRepository.saveAll(gameCompanyMap.values());

        //성인 게임 여부가 업데이트된 게임 데이터 저장
        gameRepository.saveAll(games);

    }

    // ---------------------------------------------------------------------------------
    // 헬퍼 메서드
    // ---------------------------------------------------------------------------------

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**리스트에서 특정 속성을 추출하는 메서드*/
    private <T> List<String> extractValues(List<T> list, Function<T, String> mapper) {
        if (list == null) return new ArrayList<>();
        return list.stream().map(mapper).toList();
    }

    /**
     * GameSummaryResponse에서 스팀 ID 추출
     * 1. storeAppID 필터링 (steam = 1)
     * 2. String 변환
     * */
    private String extractSteamId(IgdbGameSummaryResponse dto) {
        if (dto.externalApps() == null) return null;
        return dto.externalApps().stream()
                .filter(ext -> ext.storeId() == 1) // Steam Category
                .map(IgdbGameSummaryResponse.ExternalApp::storeAppid) // steam app id 추출
                .filter(storeAppId -> storeAppId != null && !storeAppId.isBlank())   // 빈 문자열 제외
                .findFirst()
                .orElse(null);
    }

    /**
     * 한글화 타이틀 데이터 추출
     * KR : 2
     * */
    private String extractLocalization(IgdbGameSummaryResponse dto) {
        if (dto.titleLocalization() == null || dto.titleLocalization().isEmpty()) return null;
        return dto.titleLocalization().stream()
                .filter(loc -> loc.region() != null && loc.region().id() == 2L) // Korea = 2
                .map(IgdbGameSummaryResponse.TitleLocalization::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * 게임 심의 정보 추출, 매핑용
     * */
    private record Ratings(String esrb, String kr) {}

    /**
     * 국내(GRAC), 글로벌(ESRB) 심의 정보 추출
     * */
    private Ratings extractAgeRatings(List<IgdbGameSummaryResponse.AgeRating> ageRatings) {
        if (ageRatings == null || ageRatings.isEmpty()) return new Ratings(null, null);

        String esrb = null;
        String kr = null;

        for (IgdbGameSummaryResponse.AgeRating rating : ageRatings) {
            if (rating.organization() == null || rating.ratingCategories() == null) {
                continue;
            }

            long orgId = rating.organization().id();
            String ratingValue = rating.ratingCategories().rating();

            // 3. 기관 ID에 따라 값 할당
            if (orgId == 1L) {          // ESRB (Global)
                esrb = ratingValue;
            } else if (orgId == 5L) {   // GRAC (Korea)
                kr = ratingValue;
            } else {
                long catId = rating.ratingCategories().id();
                if (catId >= 1 && catId <= 6) esrb = ratingValue;
                else if (catId >= 24 && catId <= 32) kr = ratingValue;
            }

            // 두 값을 모두 찾았으면 루프 종료 (최적화)
            if (esrb != null && kr != null) {
                break;
            }
        }

        return new Ratings(esrb, kr);
    }
}