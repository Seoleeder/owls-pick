package io.github.seoleeder.owls_pick.service.client.itad;

import io.github.seoleeder.owls_pick.client.itad.ItadDataCollector;
import io.github.seoleeder.owls_pick.client.itad.ItadStore;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadBulkResponse;
import io.github.seoleeder.owls_pick.client.itad.dto.ItadPriceResponse;
import io.github.seoleeder.owls_pick.global.config.properties.ItadProperties;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail.StoreName;
import io.github.seoleeder.owls_pick.service.client.itad.event.GameDiscountEvent;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ItadSyncService {

    private final ItadDataCollector collector;
    private final GameRepository gameRepository;
    private final StoreDetailRepository storeDetailRepository;

    private final AsyncTaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;

    private final ApplicationEventPublisher eventPublisher;

    private final ItadProperties props;

    private static final String NOT_FOUND = "NONE";
    private static final String MAIN_GAME_TYPE = "Main Game";

    public ItadSyncService(
            ItadDataCollector collector,
            GameRepository gameRepository,
            StoreDetailRepository storeDetailRepository,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher,
            ItadProperties props) {
        this.collector = collector;
        this.gameRepository = gameRepository;
        this.storeDetailRepository = storeDetailRepository;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.props = props;
    }


    /**
     * Steam ID로 ITAD UUID 조회 & 저장
     * (ITAD ID가 없는 게임들에 대해 수행)
     */
    public void syncMissingItadIds() {
        log.info("Starting ITAD ID Sync (Filling missing IDs)...");
        int totalUpdated = 0;
        int totalAttempted = 0;
        Long lastId = 0L; //커서 기반 페이징

        int steamShopId = ItadStore.STEAM.getItadId();

        while (true) {
            try {
                // 커서(lastId) 기준으로 지정된 배치 사이즈만큼 타겟 데이터 조회
                List<StoreDetail> targetDetails = storeDetailRepository.findValidGamesMissingItadId(
                        StoreName.STEAM,
                        lastId,
                        props.batchSize()
                );

                if (targetDetails.isEmpty()) {
                    log.info("No more games missing ITAD IDs.");
                    break;  // 처리할 데이터가 없으면 배치 종료
                }

                // 다음 배치 조회를 위해 현재 배치의 마지막 PK 값으로 커서 갱신
                lastId = targetDetails.get(targetDetails.size() - 1).getId();

                // Steam AppID 추출 및 매핑 준비
                Map<String, Game> steamIdToGameMap = targetDetails.stream()
                        .collect(Collectors.toMap(
                                StoreDetail::getStoreAppId,
                                StoreDetail::getGame,
                                (existing, replacement) -> existing
                        ));

                // ITAD Bulk API 규격("app/{steamId}")에 맞게 요청 파라미터 리스트 포맷팅
                List<String> formattedSteamIds = steamIdToGameMap.keySet().stream()
                        .map(id -> "app/" + id)
                        .toList();

                log.debug(">> ITAD API Request: Fetching UUIDs for {} games...", formattedSteamIds.size());
                long start = System.currentTimeMillis();

                // ITAD ID 수집 API 호출
                // 대량의 스팀 ID로 ITAD ID 조회
                ItadBulkResponse bulkResponse = collector.collectItadIdsBulk(steamShopId, formattedSteamIds);

                long duration = System.currentTimeMillis() - start;
                log.debug("<< ITAD API Response: Received matches. (Duration: {}ms)", duration);

                // DB 업데이트 로직 호출 (transactionTemplate으로 내부 트랜잭션 적용)
                Integer batchUpdated = transactionTemplate.execute(status -> updateItadIds(steamIdToGameMap, bulkResponse));

                if(batchUpdated != null) {
                    totalUpdated += batchUpdated;
                }

                totalAttempted += targetDetails.size();

                log.debug("Progress: Mapped {} new IDs in this batch. (Cumulative: {} / Attempted: {})",
                        batchUpdated, totalUpdated, totalAttempted);


            } catch (Exception e) {
                log.error("Error occurred during ITAD ID Sync batch.", e);
            }
        }

        log.info("ITAD ID Sync Completed. Total games updated: {}", totalUpdated);
    }

    /**
     * ITAD ID Bulk API 응답 결과를 Game 엔티티에 병합하고 DB 반영
     * @param gameMap [스팀 ID, Game]
     * @param bulkResponse ITAD ID API로부터 반환된 매핑 응답 DTO
     * */
    protected int updateItadIds(Map<String, Game> gameMap, ItadBulkResponse bulkResponse) {
        List<Game> gamesToSave = new ArrayList<>();
        int mappedCount = 0; // 스팀 ID과 ITAD ID 가 매칭된 횟수

        for (Map.Entry<String, Game> entry : gameMap.entrySet()) {
            String steamId = entry.getKey();
            Game game = entry.getValue();

            // 응답 DTO에서 해당 스팀 ID의 매핑 결과(UUID) 추출
            String itadUuid = bulkResponse.getUuidBySteamId(steamId);

            if (itadUuid != null) {
                // 매칭 성공: 응답으로 온 UUID를 엔티티에 업데이트
                game.updateItadId(itadUuid);
                mappedCount++;
            } else {
                // 매칭 실패: "NONE"으로 마킹하여 다음 쿼리에서 제외되도록 함
                game.updateItadId("NONE");
            }

            gamesToSave.add(game);
        }

        // "NONE"으로 마킹된 게임을 포함하여 모두 DB에 반영
        if (!gamesToSave.isEmpty()) {
            gameRepository.saveAll(gamesToSave);
        }

        // 로그 출력을 위해 '실제로 매칭된 개수'만 반환
        return mappedCount;
    }

    /**
     * ITAD ID로 최신 가격 데이터 수집
     * 정가, 할인가, 할인율, 할인 만료 시각, 스토어 구매 URL 등 동기화
     * (주기적으로 실행되는 메인 로직)
     * */
    public void syncPrices() {
        log.info("Starting ITAD Price Sync...");

        // ITAD ID가 있는 모든 게임 조회
        List<Game> gamesWithItadId = gameRepository.findByItadIdIsNotNullAndItadIdNot(NOT_FOUND);

        if (gamesWithItadId.isEmpty()) {
            log.info("No games with ITAD IDs found. Run syncMissingItadIds() first.");
            return;
        }

        // 리스트를 배치 사이즈에 맞게 분할
        List<List<Game>> partitions = partitionList(gamesWithItadId, props.batchSize());

        int totalBatches = partitions.size();

        log.info("Divided into {} batches. Processing concurrently via Fixed Thread Pool...", totalBatches);

        // 카운터 선언
        AtomicInteger completedBatches = new AtomicInteger(0);
        AtomicInteger totalUpdatedDetails = new AtomicInteger(0);

        // 분할된 배치 리스트를 가상 스레드 풀에 할당하여 병렬 API 수집 진행
        List<CompletableFuture<Void>> futures = partitions.stream()
                .map(batchGames -> CompletableFuture.runAsync(() -> {
                    // 가격 데이터 수집 후 업데이트된 개수 반환
                    int updatedCount = processPriceBatchSafe(batchGames);

                    // 카운트 증가
                    int currentBatch = completedBatches.incrementAndGet();
                    totalUpdatedDetails.addAndGet(updatedCount);

                    // 실시간 로깅
                    log.debug("[ITAD Price Sync] Batch {}/{} completed. (Details updated: {})",
                            currentBatch, totalBatches, updatedCount);
                }, taskExecutor))
                .toList();

        // 전체 배치 작업이 완료될 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("ITAD Price Sync Completed. Processed {} games. Total details updated: {}",
                gamesWithItadId.size(), totalUpdatedDetails.get());
    }

    protected int processPriceBatchSafe(List<Game> games) {
        try {
            // 가격 데이터 요청용 ITAD ID 리스트 추출
            List<String> itadIds = games.stream().map(Game::getItadId).toList();

            // 스토어별 가격 정보 수집
            List<ItadPriceResponse> prices = collector.collectPrices(itadIds);

            if (prices != null && !prices.isEmpty()) {
                Integer updatedCount = transactionTemplate.execute(status -> savePricesInternal(games, prices));
                return updatedCount != null ? updatedCount : 0;
            }
        } catch (Exception e) {
            log.warn("ITAD Price Batch Failed after retries: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 게임 가격 정보 저장 로직 (StoreDetail Update)
     * @param games 가격 정보를 저장할 게임 리스트
     * @param prices 게임의 스토어별 가격 정보
     */
    private int savePricesInternal(List<Game> games, List<ItadPriceResponse> prices) {

        // 응답 매핑용 Map 생성 (Itad Id -> Game)
        // 동일한 Itad Id를 가진 모든 게임을 묶음
        Map<String, List<Game>> itadIdToGamesMap = games.stream()
                .collect(Collectors.groupingBy(Game::getItadId));

        //저장할 스토어 상세 정보 리스트
        List<StoreDetail> detailsToSave = new ArrayList<>();

        for (ItadPriceResponse priceResponse : prices) {
            List<Game> matchedGames = itadIdToGamesMap.get(priceResponse.id()); // Itad Id로 게임 찾기
            if (matchedGames == null || priceResponse.deals() == null){
                log.debug("No matched games in DB for ITAD ID: {}", priceResponse.id());
                continue;
            }

            // 동일한 ID를 가진 게임들 중  본편만 추출
            Game game = findCanonicalGame(matchedGames);



            // 다중 플랫폼 키(Steam, Epic 등)를 취급하는 외부 키 셀러의 중복 딜 방어
            // 동일 스토어에서 여러 데이터가 반환된 경우, 최저가 딜만 남겨 DB 제약조건 충돌 방지
            Map<String, ItadPriceResponse.Deal> bestDealsByStore = new HashMap<>();

            // 딜 리스트 순회
            for (ItadPriceResponse.Deal deal : priceResponse.deals()) {
                if (deal.shop() == null || deal.currentPrice() == null) continue;

                // 스토어 식별 (ITAD ID -> Enum 매핑)
                ItadStore itadStore = ItadStore.fromId(Integer.parseInt(deal.shop().id()));
                if (itadStore == null || itadStore.getStoreName() == null) continue;

                String storeNameString = itadStore.getStoreName().name();

                // 스토어 이름을 Key로 하여 가장 저렴한 가격을 가진 Deal 로직 병합
                bestDealsByStore.merge(storeNameString, deal, (existingDeal, newDeal) -> {
                    int existingPrice = existingDeal.currentPrice().amountInt();
                    int newPrice = newDeal.currentPrice().amountInt();
                    return newPrice < existingPrice ? newDeal : existingDeal;
                });
            }

            // 필터링이 완료된 최저가 딜만 순회하여 영속성 컨텍스트 반영
            for (ItadPriceResponse.Deal deal : bestDealsByStore.values()) {

                ItadStore itadStore = ItadStore.fromId(Integer.parseInt(deal.shop().id()));
                //DB에 저장될 스토어 이름
                StoreName storeName = itadStore.getStoreName();

                // Upsert 조회 (기존 데이터 있는지 확인하고 없으면 빌드)
                StoreDetail storeDetail = storeDetailRepository.findByGameAndStoreName(game, storeName)
                        .orElseGet(() -> {
                                return StoreDetail.builder()
                                .game(game)
                                .storeName(storeName)
                                .storeAppId(null)
                                .build();
                        });

                // URL 정보 매핑
                // - Steam: 기존 스팀 상점 고유 URL이 있다면 덮어쓰지 않고 유지
                // - Others: ITAD에서 제공하는 스토어 구매 URL 반영
                String urlToSave;
                if (storeName == StoreName.STEAM) {
                    urlToSave = (storeDetail.getUrl() != null && !storeDetail.getUrl().isBlank())
                            ? storeDetail.getUrl()
                            : deal.url();
                } else {
                    urlToSave = deal.url();
                }

                // API 응답 데이터 파싱
                Integer currentPrice = deal.currentPrice().amountInt(); // 현재가 (할인 시에는 할인가)
                Integer originalPrice = (deal.originalPrice() != null) ? deal.originalPrice().amountInt() : currentPrice; // 정가
                Integer historicalLow = (deal.storeLow() != null) ? deal.storeLow().amountInt() : null; // 역대 최저가
                Integer cut = (deal.cut() != null) ? deal.cut() : 0; // 할인율
                OffsetDateTime expiry = deal.expiryDate(); // 할인 만료 시각

                LocalDateTime expiryKst = null;

                if (expiry != null) {
                    expiryKst = expiry
                            .atZoneSameInstant(ZoneId.of("Asia/Seoul")) // 타임존을 서울로 변경
                            .toLocalDateTime();
                }


                // 변경 감지 (Skip 최적화)
                boolean isSame = isSamePriceInfo(storeDetail, currentPrice, originalPrice, cut, historicalLow, expiryKst, urlToSave);

                // 실질적인 데이터 변경이 발생한 경우에만 엔티티 상태 갱신 및 리스트에 추가
                if (!isSame) {
                    log.debug("[TARGET-ALIVE] Found changes for: {} (Store: {})", game.getTitle(), storeName);
                    storeDetail.updatePriceInfo(currentPrice, originalPrice, historicalLow, cut, expiryKst, urlToSave);
                    detailsToSave.add(storeDetail);

                    // 실질적인 할인이 감지되었을 때(할인율 > 0) 비동기 알림 이벤트 발행
                    if (cut > 0) {
                        eventPublisher.publishEvent(
                                new GameDiscountEvent(game.getId(), cut, expiryKst)
                        );
                    }
                }
            }
        }

        // 변경된 데이터만 일괄 저장
        if (!detailsToSave.isEmpty()) {
            storeDetailRepository.saveAll(detailsToSave);
            log.info("Successfully saved {} details to DB", detailsToSave.size());
        }

        return detailsToSave.size();
    }

    // ---------------------------------------------------------------------------------
    // Helper Methods
    // ---------------------------------------------------------------------------------


    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * 변경 감지 로직 (Dirty Checking)
     * - DB의 값과 API에서 가져온 값이 '논리적으로' 같은지 비교
     */
    private boolean isSamePriceInfo(StoreDetail detail, Integer cur, Integer reg, Integer cut,
                                    Integer low, LocalDateTime expiry, String url) {
        if (detail.getId() == null) return false; // 신규 데이터는 무조건 저장

        //DB는 비어있고, 새로운 값이 들어온 경우 (초기 수집)
        if (detail.getOriginalPrice() == null && (reg != null || cur != null)) return false;

        // 정가 비교
        if (!Objects.equals(detail.getOriginalPrice(), reg)) return false;

        // 할인율 비교
        if(!Objects.equals(detail.getDiscountRate(), cut)) return false;

        // 할인가(discountPrice) 비교
        // 정책: 할인 중(cut > 0)이면 현재가가 할인가, 아니면 Null
        Integer dbDiscountPrice = detail.getDiscountPrice();
        Integer targetDiscountPrice = (cut > 0) ? cur : null;
        if (!Objects.equals(detail.getDiscountRate(), (cut == null ? 0 : cut))) return false;
        if (!Objects.equals(dbDiscountPrice, targetDiscountPrice)) return false;

        // 역대 최저가 비교
        if (!Objects.equals(detail.getHistoricalLow(), low)) return false;


        // 만료일 비교 (할인 중일 때만 유효)
        if (!Objects.equals(detail.getExpiryDate(), expiry)) return false;
        if (!Objects.equals(detail.getUrl(), url)) return false;

        // 기타 (할인율, URL)
        return true;
    }

    /**
     * 동일한 ITAD ID를 가진 여러 게임 중 본편만 반환
     */
    private Game findCanonicalGame(List<Game> games) {
        // 단일 매칭이면 바로 반환
        if (games.size() == 1) {
            return games.get(0);
        }

        // 게임 타입이 Main Game인 게임들만 필터링
        List<Game> mainGames = games.stream()
                .filter(g -> g.getType() != null && MAIN_GAME_TYPE.equals(g.getType().toString()))
                .toList();

        // Main Game이 존재하면 그것들로, 존재하지 않는다면 전체 게임 리스트를 후보군으로 선정
        List<Game> candidates = mainGames.isEmpty() ? games : mainGames;

        // 후보군 중에서 출시일이 가장 오래된 게임 선택
        return candidates.stream()
                .min(Comparator.comparing(Game::getFirstRelease, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(candidates.get(0));
    }

//    // 애플리케이션 종료 시 스레드 풀 자원 정리
//    @PreDestroy
//    public void cleanup() {
//        if (executorService != null && !executorService.isShutdown()) {
//            executorService.shutdown();
//        }
//    }
}
