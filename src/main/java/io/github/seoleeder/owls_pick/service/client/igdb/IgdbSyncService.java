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

    private final GameRepository gameRepository;
    private final StoreDetailRepository storeDetailRepository;
    private final TagRepository tagRepository;
    private final ScreenshotRepository screenshotRepository;
    private final CompanyRepository companyRepository;
    private final GameCompanyRepository gameCompanyRepository;
    private final LanguageSupportRepository languageSupportRepository;

    private final TransactionTemplate transactionTemplate;

    /**
     * ID 기준으로 IGDB 전체 게임 데이터 순차 수집.
     * DB에 최종 수집된 IGDB ID를 커서로 설정하여 이후 데이터부터 순차적으로 수집함.
     */
    public void backfillAllGames() {
        log.info("Starting IGDB Full Backfill (ID based)...");

        // 최종 저장된 IGDB ID 커서 조회
        long lastId = gameRepository.findTopByOrderByIgdbIdDesc()
                .map(Game::getIgdbId)
                .orElse(0L);

        log.info("Resuming backfill from ID: {}", lastId);

        // 누적 조회 건수 카운터
        int processedCount = 0;

        while (true) {
            try {
                // 커서 기준 500건 단위 IGDB 게임 요약 정보 수집
                List<IgdbGameSummaryResponse> summaries = collector.collectGameSummary(lastId);

                // 빈 응답 반환 시 루프 종료
                if (summaries.isEmpty()) {
                    log.info("Backfill Completed! Total processed: {}, Last ID: {}", processedCount, lastId);
                    break;
                }

                // 배치 단위 요약 정보 및 상세 정보 저장 처리
                processBatch(summaries);

                // 다음 조회를 위한 커서 갱신
                lastId = summaries.getLast().igdbId();

                processedCount += summaries.size();

                log.debug("Processed batch up to ID: {}", lastId);

                // 누적 5,000건(10개 배치) 도달 시 누적 진행률 로깅
                if (processedCount % 5000 == 0) {
                    log.info("[IGDB Backfill Progress] Processed cumulative {} games. Current Last IGDB ID: {}",
                            processedCount, lastId);
                }

                // API 호출 초당 4회 제한 준수를 위한 딜레이 처리
                sleep(250);

            } catch (RestClientException e) {
                log.warn("IGDB API Error at ID {}: {}", lastId, e.getMessage());
                sleep(5000);
            }
            catch (Exception e) {
                log.error("Backfill paused at ID: {}", lastId, e);
                sleep(5000);
            }
        }
    }

    /**
     * IGDB 변경 데이터 동기화.
     * 최종 수정 시각(updated_at) 이후에 변경되거나 신규 등록된 데이터만 수집함.
     */
    public void syncUpdatedGames() {
        // 최종 IGDB 수정 시각 조회 (Epoch Time 변환)
        long lastTimestamp = gameRepository.findMaxIgdbUpdatedAt()
                        .map(TimestampUtils::toEpoch)
                                .orElse(0L);

        log.info("Starting Update Sync (Timestamp based) from: {}", lastTimestamp);

        // 누적 처리 건수 카운터
        int processedCount = 0;

        while (true) {
            try {
                // 기준 시각 이후 변경 데이터 수집
                List<IgdbGameSummaryResponse> summaries = collector.collectUpdatedGameSummary(lastTimestamp);

                if (summaries.isEmpty()) {
                    log.info("No more updates found. Total updated: {}", processedCount);
                    break;
                }

                // 배치 단위 데이터 저장 처리
                processBatch(summaries);

                // 다음 조회를 위해 커서 위치를 최종 수정 시각으로 갱신
                lastTimestamp = summaries.getLast().updatedAt();

                processedCount += summaries.size();

                log.debug("Synced batch up to timestamp: {}", lastTimestamp);

                // 누적 5,000건 단위 진행률 로깅
                if (processedCount % 1000 == 0) {
                    log.info("[IGDB Update Progress] Synced cumulative {} games. Current Last Timestamp: {}",
                            processedCount, lastTimestamp);
                }

                sleep(250);

            }catch (RestClientException e) {
                // 통신 예외 발생 시 대기
                log.warn("IGDB API Error at Timestamp {}: {}", lastTimestamp, e.getMessage());
                sleep(5000);
            }
            catch (Exception e) {
                // 예기치 않은 오류 발생 시 업데이트 중단
                log.error("Update Sync Failed", e);
                break;
            }
        }
    }

    /**
     * 500건 단위로 요약 데이터(Igdb Summary) 및 상세 연관 데이터(Igdb Detail) 저장.
     */
    protected void processBatch(List<IgdbGameSummaryResponse> summaries) {
        List<Game> savedGames;
        try {
            // 요약 데이터 매핑 및 Game 엔티티 트랜잭션 갱신
            savedGames = transactionTemplate.execute(status ->
                    upsertGamesSummaries(summaries)
            );
        } catch (Exception e) {
            log.error("Failed to save Game Summaries batch.", e);
            return;
        }

        // 정상 갱신 데이터 없을 시 상세 조회 생략
        if (savedGames == null || savedGames.isEmpty()) return;

        // 상세 정보 API 요청용 IGDB ID 목록 추출
        List<Long> igdbIds = savedGames.stream().map(Game::getIgdbId).toList();

        List<IgdbGameDetailResponse> details;

        try {
            // ID 목록 기반 상세 정보 일괄 조회
            details = collector.collectGameDetail(igdbIds);
        } catch (RestClientException e) {
            log.warn("IGDB API Error. Skipping details for this batch: {}", e.getMessage());
            return;
        } catch (Exception e) {
            log.error("Unexpected error during API call.", e);
            return;
        }

        if (details == null || details.isEmpty()) return;

        try {
            // 상세 연관 데이터 매핑 및 트랜잭션 저장
            transactionTemplate.executeWithoutResult(status -> syncDetails(savedGames, details));
            log.debug("Successfully processed details for {} games in this batch.", savedGames.size());
        } catch (Exception e) {
            log.error("Failed to save Game Details batch.", e);
        }
    }



    /**
     * 게임 요약 정보를 파싱하고 기존 StoreDetail과 매핑하여 Game 엔티티 갱신.
     * */
    private List<Game> upsertGamesSummaries(List<IgdbGameSummaryResponse> summaries) {
        // Steam App ID 매핑을 위한 Map 생성 (Key: Steam App ID, Value: IGDB 응답 DTO)
        Map<String, IgdbGameSummaryResponse> steamIdToDtoMap = new HashMap<>();
        List<String> steamAppIds = new ArrayList<>();

        // 응답 객체에서 Steam App ID 속성 추출 및 매핑
        for (IgdbGameSummaryResponse dto : summaries) {
            String steamId = extractSteamId(dto);
            if (steamId != null) {
                steamAppIds.add(steamId);
                steamIdToDtoMap.put(steamId, dto);
            }
        }

        if (steamAppIds.isEmpty()) return List.of();

        // 추출된 Steam App ID로 기존 저장된 StoreDetail 엔티티 일괄 조회
        List<StoreDetail> existingDetails = storeDetailRepository.findByStoreNameAndStoreAppIdIn(
                StoreDetail.StoreName.STEAM,
                steamAppIds
        );

        List<Game> gamesToUpdate = new ArrayList<>();

        // 기존 게임 엔티티에 수집된 속성 반영
        for (StoreDetail detail : existingDetails) {
            String steamId = detail.getStoreAppId();
            IgdbGameSummaryResponse dto = steamIdToDtoMap.get(steamId);

            if (dto != null) {
                Game game = detail.getGame();

                // 신규 식별자 매핑
                if(game.getIgdbId() == null){
                    game.connectToIgdb(dto.igdbId());
                }

                // Epoch Time을 LocalDateTime 및 LocalDate 타입으로 변환
                LocalDateTime igdbUpdatedAt = TimestampUtils.toLocalDateTime(dto.updatedAt());
                LocalDate firstRelease = TimestampUtils.toLocalDate(dto.first_release());

                // 한글화 타이틀 및 심의 등급 데이터 추출
                String localTitle = extractLocalization(dto);
                Ratings ratings = extractAgeRatings(dto.ageRatings());

                String coverId = (dto.cover() != null) ? dto.cover().imageId() : null;
                String typeName = (dto.type() != null) ? dto.type().type() : null;
                String statusName = (dto.gameStatus() != null) ? dto.gameStatus().status() : null;

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

                // Game 엔티티 업데이트
                game.updateFromSummary(
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
     * 게임 상세 데이터(Tag, Screenshot, Language, Company) 생성 및 연관 테이블 갱신.
     */
    private void syncDetails(List<Game> games, List<IgdbGameDetailResponse> details) {

        // IGDB ID 기준 상세 응답 데이터 매핑
        Map<Long, IgdbGameDetailResponse> detailMap = details.stream()
                .collect(Collectors.toMap(IgdbGameDetailResponse::igdbId, Function.identity()));

        List<Tag> tags = new ArrayList<>();
        List<Screenshot> screenshots = new ArrayList<>();
        List<LanguageSupport> languages = new ArrayList<>();

        Map<GameCompanyId, GameCompany> gameCompanyMap = new HashMap<>();

        // 배치 내 회사 이름 추출 및 중복 제거
        Set<String> companyNames = new HashSet<>();
        for (IgdbGameDetailResponse dto : details) {
            if (dto.companies() != null) {
                dto.companies().stream()
                        .map(IgdbGameDetailResponse.Company::companyDetail)
                        .filter(Objects::nonNull)
                        .map(IgdbGameDetailResponse.Company.CompanyDetail::name)
                        .filter(name -> name != null && !name.isBlank())
                        .forEach(companyNames::add);
            }
        }

        Map<String, Company> savedCompanyMap = new HashMap<>();

        // DB에 존재하는 기존 회사 정보 조회 및 신규 회사 생성
        if (!companyNames.isEmpty()) {
            List<Company> existingCompanies = companyRepository.findByNameIn(List.copyOf(companyNames));
            existingCompanies.forEach(c -> savedCompanyMap.put(c.getName(), c));

            List<Company> newCompanies = new ArrayList<>();
            for (String name : companyNames) {
                if (!savedCompanyMap.containsKey(name)) {
                    newCompanies.add(Company.builder()
                            .name(name)
                            .build());
                }
            }

            if (!newCompanies.isEmpty()) {
                List<Company> saved = companyRepository.saveAll(newCompanies);
                saved.forEach(c -> savedCompanyMap.put(c.getName(), c));
            }
        }

        // 게임 엔티티에 상세 연관 데이터 파싱 및 연결
        for (Game game : games) {
            IgdbGameDetailResponse dto = detailMap.get(game.getIgdbId());
            if (dto == null) continue;

            // 추가 DB 조회 방지를 위한 프록시 참조 객체 생성
            Game gameProxy = gameRepository.getReferenceById(game.getId());

            // 장르, 테마, 키워드 목록 변환 및 Tag 엔티티 생성
            Tag tag = Tag.builder()
                    .game(gameProxy)
                    .genres(extractValues(dto.genres(), IgdbGameDetailResponse.Genre::name))
                    .themes(extractValues(dto.themes(), IgdbGameDetailResponse.Theme::name))
                    .keywords(extractValues(dto.keywords(), IgdbGameDetailResponse.Keyword::name))
                    .build();
            tags.add(tag);

            // 태그 정보를 바탕으로 성인 콘텐츠 여부 판별
            game.evaluateAdultStatus(tag);

            // 스크린샷 ID 및 해상도 변환
            if (dto.screenshots() != null) {
                dto.screenshots().forEach(s -> screenshots.add(
                        Screenshot.builder()
                                .game(gameProxy).imageId(s.imageId()).width(s.width()).height(s.height())
                                .build()));
            }

            // 언어별 지원 타입(Audio, Subtitles, Interface) 집계 및 LanguageSupport 변환
            if (dto.languageSupports() != null) {
                Map<String, Set<String>> langMap = new HashMap<>();

                for (var l : dto.languageSupports()) {
                    if (l.languageInfo() == null || l.languageInfo().name() == null) continue;

                    String langName = l.languageInfo().name();
                    String supportType = (l.supportType() != null) ? l.supportType().name() : "";

                    langMap.computeIfAbsent(langName, k -> new HashSet<>()).add(supportType);
                }

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

            // 회사 권한(Developer, Publisher) 속성 GameCompany 다대다 매핑 변환
            if (dto.companies() != null) {
                dto.companies().forEach(c -> {
                    if (c.companyDetail() != null) {
                        String companyName = c.companyDetail().name();
                        Company company = savedCompanyMap.get(companyName);

                        if (company != null) {
                            GameCompanyId compoundId = new GameCompanyId(gameProxy.getId(), company.getId());

                            // 동일한 복합키 발생 시 권한 속성 병합 처리
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
                                    existing.updateRoles(c.isDeveloper(), c.isPublisher());
                                    return existing;
                                }
                            });
                        }
                    }
                });
            }

        }

        // 연관 테이블 기존 데이터 삭제 후 갱신 데이터 일괄 삽입
        if (!games.isEmpty()) {
            tagRepository.deleteByGameIn(games);
            screenshotRepository.deleteByGameIn(games);
            languageSupportRepository.deleteByGameIn(games);
            gameCompanyRepository.deleteByGameIn(games);
        }

        tagRepository.saveAll(tags);
        screenshotRepository.saveAll(screenshots);
        languageSupportRepository.saveAll(languages);
        gameCompanyRepository.saveAll(gameCompanyMap.values());

        // 성인 게임 판별 결과가 반영된 Game 엔티티 상태 저장
        gameRepository.saveAll(games);

    }

    // ---------------------------------------------------------------------------------
    // 헬퍼 메서드
    // ---------------------------------------------------------------------------------

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * 목록 데이터에서 특정 속성을 추출하여 문자열 리스트로 반환.
     */
    private <T> List<String> extractValues(List<T> list, Function<T, String> mapper) {
        if (list == null) return new ArrayList<>();
        return list.stream().map(mapper).toList();
    }

    /**
     * GameSummaryResponse에서 스팀 ID 추출
     * storeAppID 필터링 후 문자열 변환
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
     * Localization에서 한국어(Region ID: 2) 타이틀 추출.
     */
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
     * 게임 심의 정보(ESRB, GRAC) 매핑용 레코드.
     */
    private record Ratings(String esrb, String kr) {}

    /**
     * 심의 데이터에서 국내(GRAC) 및 북미(ESRB) 기준 등급 값 추출.
     */
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

            if (orgId == 1L) {          // ESRB (북미 표준)
                esrb = ratingValue;
            } else if (orgId == 5L) {   // GRAC (한국 표준)
                kr = ratingValue;
            } else {
                long catId = rating.ratingCategories().id();
                if (catId >= 1 && catId <= 6) esrb = ratingValue;
                else if (catId >= 24 && catId <= 32) kr = ratingValue;
            }

            if (esrb != null && kr != null) {
                break;
            }
        }

        return new Ratings(esrb, kr);
    }
}