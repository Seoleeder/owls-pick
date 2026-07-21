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
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ItadSyncService {

    private final ItadDataCollector collector;
    private final GameRepository gameRepository;
    private final StoreDetailRepository storeDetailRepository;

    private final TransactionTemplate transactionTemplate;

    // DB 커넥션 풀을 고려한 병렬 처리용 고정 스레드 엔진
    private final ExecutorService executorService;
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
        this.transactionTemplate = transactionTemplate;
        this.executorService = Executors.newFixedThreadPool(props.syncThreadPoolSize());
        this.eventPublisher = eventPublisher;
        this.props = props;
    }


    /**
     * DB에 저장된 Steam 게임 중 ITAD ID가 누락된 게임에 대해 UUID 매핑 수행.
     */
    public void syncMissingItadIds() {
        log.info("Starting ITAD ID Sync (Filling missing IDs)...");
        int totalUpdated = 0;
        int totalAttempted = 0;
        Long lastId = 0L;

        int steamShopId = ItadStore.STEAM.getItadId();

        while (true) {
            try {
                // 커서 기준 ITAD ID 누락 게임 데이터 목록 조회
                List<StoreDetail> targetDetails = storeDetailRepository.findValidGamesMissingItadId(
                        StoreName.STEAM,
                        lastId,
                        props.batchSize()
                );

                if (targetDetails.isEmpty()) {
                    log.info("No more games missing ITAD IDs.");
                    break;
                }

                // 다음 조회를 위한 커서 갱신
                lastId = targetDetails.get(targetDetails.size() - 1).getId();

                // Steam App ID 추출 및 Game 엔티티 매핑 데이터 생성
                Map<String, Game> steamIdToGameMap = targetDetails.stream()
                        .collect(Collectors.toMap(
                                StoreDetail::getStoreAppId,
                                StoreDetail::getGame,
                                (existing, replacement) -> existing
                        ));

                // ITAD Bulk API 규격에 맞추어 변환
                List<String> formattedSteamIds = steamIdToGameMap.keySet().stream()
                        .map(id -> "app/" + id)
                        .toList();

                log.debug(">> ITAD API Request: Fetching UUIDs for {} games...", formattedSteamIds.size());
                long start = System.currentTimeMillis();

                // ITAD ID 매핑 API 호출 (스팀 ID로 ITAD UUID 조회)
                ItadBulkResponse bulkResponse = collector.collectItadIdsBulk(steamShopId, formattedSteamIds);

                long duration = System.currentTimeMillis() - start;
                log.debug("<< ITAD API Response: Received matches. (Duration: {}ms)", duration);

                // 내부 트랜잭션을 생성하여 ITAD UUID 업데이트
                Integer batchUpdated = transactionTemplate.execute(status -> updateItadIds(steamIdToGameMap, bulkResponse));

                if(batchUpdated != null) {
                    totalUpdated += batchUpdated;
                }

                totalAttempted += targetDetails.size();

                log.debug("Progress: Mapped {} new IDs in this batch. (Cumulative: {} / Attempted: {})",
                        batchUpdated, totalUpdated, totalAttempted);

                // 누적 2,000건(10개 배치) 도달 시 매핑 진행률 로깅
                if (totalAttempted % 2000 == 0) {
                    log.info("[ITAD ID Sync Progress] Attempted cumulative {} games. Total mapped IDs: {}",
                            totalAttempted, totalUpdated);
                }

            } catch (Exception e) {
                // 배치 실패 예외 로깅 및 루프 재개
                log.error("Error occurred during ITAD ID Sync batch.", e);
            }
        }

        log.info("ITAD ID Sync Completed. Total games updated: {}", totalUpdated);
    }

    /**
     * ITAD Bulk API 매핑 응답 결과를 Game 엔티티에 반영.
     * 매핑에 실패한 경우 이후 조회 필터링을 위해 "NONE" 상태로 갱신함.
     * */
    protected int updateItadIds(Map<String, Game> gameMap, ItadBulkResponse bulkResponse) {
        List<Game> gamesToSave = new ArrayList<>();
        int mappedCount = 0; // 스팀 ID과 ITAD ID 가 매칭된 횟수

        for (Map.Entry<String, Game> entry : gameMap.entrySet()) {
            String steamId = entry.getKey();
            Game game = entry.getValue();

            // 응답 DTO에서 해당 스팀 ID의 매핑 결과(UUID) 추출
            String itadUuid = bulkResponse.getUuidBySteamId(steamId);

            // 매칭 여부에 따른 UUID 갱신
            if (itadUuid != null) {

                game.updateItadId(itadUuid);
                mappedCount++;
            } else {
                // 매칭 실패 시 "NONE"으로 마킹하여 다음 쿼리에서 제외
                game.updateItadId("NONE");
            }

            gamesToSave.add(game);
        }

        // 갱신된 게임 리스트 일괄 저장
        if (!gamesToSave.isEmpty()) {
            gameRepository.saveAll(gamesToSave);
        }

        return mappedCount;
    }

    /**
     * ITAD UUID가 매핑된 게임들의 스토어별 최신 가격 정보를 수집하고 DB 동기화.
     * */
    public void syncPrices() {
        log.info("Starting ITAD Price Sync...");

        // 유효한 ITAD ID를 보유한 게임 목록 조회
        List<Game> gamesWithItadId = gameRepository.findByItadIdIsNotNullAndItadIdNot(NOT_FOUND);

        if (gamesWithItadId.isEmpty()) {
            log.info("No games with ITAD IDs found. Run syncMissingItadIds() first.");
            return;
        }

        // 대용량 데이터 전송 부하를 줄이기 위해 지정된 배치 크기로 목록 분할
        List<List<Game>> partitions = partitionList(gamesWithItadId, props.batchSize());

        int totalBatches = partitions.size();

        log.info("Divided into {} batches. Processing concurrently via Fixed Thread Pool...", totalBatches);

        AtomicInteger completedBatches = new AtomicInteger(0);
        AtomicInteger totalUpdatedDetails = new AtomicInteger(0);

        // 분할된 배치 리스트를 고정 스레드 풀에 할당하여 병렬 통신 및 저장 수행
        List<CompletableFuture<Void>> futures = partitions.stream()
                .map(batchGames -> CompletableFuture.runAsync(() -> {

                    // 단일 배치 단위로 가격 데이터 수집 및 DB 갱신 수행
                    int updatedCount = processPriceBatchSafe(batchGames);

                    int currentBatch = completedBatches.incrementAndGet();

                    int cumulativeUpdates = totalUpdatedDetails.addAndGet(updatedCount);

                    log.debug("[ITAD Price Sync] Batch {}/{} completed. (Details updated: {})",
                            currentBatch, totalBatches, updatedCount);

                    // 10개 배치 단위 또는 최종 완료 시 진행률 로깅
                    if (currentBatch % 10 == 0 || currentBatch == totalBatches) {
                        log.info("[ITAD Price Sync Progress] Batch {}/{} completed. (Cumulative details updated: {})",
                                currentBatch, totalBatches, cumulativeUpdates);
                    }
                }, executorService))
                .toList();

        // 모든 비동기 작업이 종료될 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("ITAD Price Sync Completed. Processed {} games. Total details updated: {}",
                gamesWithItadId.size(), totalUpdatedDetails.get());
    }

    protected int processPriceBatchSafe(List<Game> games) {
        try {
            // 배치 내 ITAD ID 추출
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
     * 게임 가격 정보 저장 메서드
     * @param games 가격 정보를 저장할 게임 리스트
     * @param prices 게임의 스토어별 가격 정보
     */
    private int savePricesInternal(List<Game> games, List<ItadPriceResponse> prices) {

        // 응답 매핑을 위한 ITAD ID 기준 그룹화
        Map<String, List<Game>> itadIdToGamesMap = games.stream()
                .collect(Collectors.groupingBy(Game::getItadId));

        // 배치 단위 일괄 조회를 위한 전체 스토어 목록 수집
        List<StoreName> allStoreNames = Arrays.asList(StoreName.values());

        // 대상 게임들과 전체 스토어 조건에 해당하는 기존 StoreDetail 일괄 조회
        List<StoreDetail> existingDetails = storeDetailRepository.findByGameInAndStoreNameIn(games, allStoreNames);

        // 메모리 상의 빠른 객체 식별을 위한 Map 생성 (Key: "gameId:storeName")
        Map<String, StoreDetail> detailMap = existingDetails.stream()
                .collect(Collectors.toMap(
                        sd -> sd.getGame().getId() + ":" + sd.getStoreName().name(),
                        sd -> sd,
                        (existing, replacement) -> existing
                ));

        // 저장할 스토어 상세 정보 리스트
        List<StoreDetail> detailsToSave = new ArrayList<>();

        for (ItadPriceResponse priceResponse : prices) {
            List<Game> matchedGames = itadIdToGamesMap.get(priceResponse.id()); // Itad Id로 게임 찾기
            if (matchedGames == null || priceResponse.deals() == null){
                log.debug("No matched games in DB for ITAD ID: {}", priceResponse.id());
                continue;
            }

            // 중복 매핑 방지를 위한 대표 게임(본편) 속성 필터링
            Game game = findCanonicalGame(matchedGames);

            Map<String, ItadPriceResponse.Deal> bestDealsByStore = new HashMap<>();

            for (ItadPriceResponse.Deal deal : priceResponse.deals()) {
                if (deal.shop() == null || deal.currentPrice() == null) continue;

                // 스토어 식별 (ITAD ID -> Enum 매핑)
                ItadStore itadStore = ItadStore.fromId(Integer.parseInt(deal.shop().id()));
                if (itadStore == null || itadStore.getStoreName() == null) continue;

                String storeNameString = itadStore.getStoreName().name();

                // 동일 스토어에서 다중 응답 발생 시 가격 비교를 통한 병합
                bestDealsByStore.merge(storeNameString, deal, (existingDeal, newDeal) -> {
                    int existingPrice = existingDeal.currentPrice().amountInt();
                    int newPrice = newDeal.currentPrice().amountInt();
                    return newPrice < existingPrice ? newDeal : existingDeal;
                });
            }

            // 스토어별 최저가 데이터 순회 및 엔티티 파싱
            for (ItadPriceResponse.Deal deal : bestDealsByStore.values()) {
                ItadStore itadStore = ItadStore.fromId(Integer.parseInt(deal.shop().id()));
                StoreName storeName = itadStore.getStoreName();

                // 스토어 상세 정보 Upsert 조회
                String lookupKey = game.getId() + ":" + storeName.name();
                StoreDetail storeDetail = detailMap.computeIfAbsent(lookupKey, k ->
                        StoreDetail.builder()
                                .game(game)
                                .storeName(storeName)
                                .storeAppId(null)
                                .build()
                );

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


                // 실질적인 데이터 변경 유무 확인
                boolean isSame = isSamePriceInfo(storeDetail, currentPrice, originalPrice, cut, historicalLow, expiryKst, urlToSave);

                // 값이 변경된 엔티티 필드 갱신
                if (!isSame) {
                    log.debug("[TARGET-ALIVE] Found changes for: {} (Store: {})", game.getTitle(), storeName);
                    storeDetail.updatePriceInfo(currentPrice, originalPrice, historicalLow, cut, expiryKst, urlToSave);
                    detailsToSave.add(storeDetail);

                    // 할인율 상승 감지 시 시스템 내부 알림을 위한 비동기 이벤트 발행
                    if (cut > 0) {
                        eventPublisher.publishEvent(
                                new GameDiscountEvent(game.getId(), cut, expiryKst)
                        );
                    }
                }
            }
        }

        // 갱신 엔티티 일괄 DB 반영
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
     * 기존 가격 데이터와 수집된 응답 값의 일치 여부 검증.
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
     * ITAD ID 중복 게임 발생 시, 본편(Main Game) 속성을 우선하여 식별 게임을 반환.
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

    // 애플리케이션 종료 시 ITAD 전용 스레드 풀 자원 안전 정리
    @PreDestroy
    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
