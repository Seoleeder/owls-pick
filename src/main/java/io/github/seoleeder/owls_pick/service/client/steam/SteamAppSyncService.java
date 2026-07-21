package io.github.seoleeder.owls_pick.service.client.steam;

import io.github.seoleeder.owls_pick.client.steam.SteamDataCollector;
import io.github.seoleeder.owls_pick.client.steam.dto.SteamAppListResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.SteamAppListResponse.Response.App;
import io.github.seoleeder.owls_pick.client.steam.util.SteamGameUrlBuilder;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SteamAppSyncService {
    private final SteamDataCollector collector;
    private final GameRepository gameRepository;
    private final StoreDetailRepository storeDetailRepository;
    private final SteamGameUrlBuilder urlBuilder;
    private final TransactionTemplate transactionTemplate;

    /**
     * Steam 전체 앱 목록 수집 및 신규 게임 저장.
     * */
    public void syncAppList(){
        log.info("Starting Steam AppList sync...");

        // 기존에 저장된 Steam App ID 목록 조회
        Set<String> existingSteamIds = storeDetailRepository.findAllAppIdsByStore(StoreDetail.StoreName.STEAM);
        log.debug("Loaded {} existing Steam Ids from DB. ", existingSteamIds.size());

        // 페이징 커서 및 상태 변수 초기화
        Long lastAppId = null;
        boolean haveMoreResults = true;
        int totalNewGames = 0;

        // 누적 조회 페이지 카운터
        int processedPages = 0;

        // 응답 데이터가 존재할 때까지 페이지 조회 반복
        while (haveMoreResults) {
            try {
                // 커서(lastAppId) 기반 현재 페이지 데이터 조회
                SteamAppListResponse response = collector.collectAppList(lastAppId);

                // API 응답 데이터 유효성 검증
                if (response == null || response.response() == null) {
                    throw new RestClientException("Empty Response from Steam API");
                }

                // 다음 페이지 조회를 위한 커서 및 반복 플래그 갱신
                lastAppId = response.response().lastAppId();
                haveMoreResults = response.response().haveMoreResults();

                List<App> apps = response.response().apps();

                if (apps != null && !apps.isEmpty()) {
                    // 기존 ID 제외 및 앱 이름 길이 유효성 필터링
                    List<App> newApps = apps.stream()
                            .filter(app -> !existingSteamIds.contains(String.valueOf(app.appId())))
                            .filter(app -> app.name() != null && !app.name().isBlank())
                            .filter(app -> app.name().length() <= 255)
                            .toList();

                    log.debug("Fetched {} apps. Found {} new apps to save. (LastAppId: {})", apps.size(), newApps.size(), lastAppId);

                    if (!newApps.isEmpty()) {
                        int chunkSize = 1000;
                        // 1,000건 단위 청크 분할
                        for (int i = 0; i < newApps.size(); i += chunkSize) {
                            List<App> chunk = newApps.subList(i, Math.min(newApps.size(), i + chunkSize));
                            try{
                                // 청크 단위 일괄 저장 트랜잭션 실행
                                transactionTemplate.executeWithoutResult(status -> {
                                    saveNewApps(chunk);
                                });
                                totalNewGames += chunk.size();

                            } catch (DataAccessException e) {
                                log.warn("Chunk save failed! Attempting individual saves to isolate bad data. (Chunk size: {})", chunk.size());

                                // 일괄 저장 실패 시 단건 저장으로 롤백 및 정상 데이터 보존 처리
                                int recoveredCount = 0;
                                for (App singleApp : chunk) {
                                    try {
                                        // 단건 트랜잭션 실행
                                        transactionTemplate.executeWithoutResult(status -> {
                                            saveNewApps(Collections.singletonList(singleApp));
                                        });
                                        recoveredCount++;
                                    } catch (DataAccessException ex) {
                                        // 제약 조건 위반 데이터 저장 실패 로깅
                                        log.warn("Bad data detected and discarded - AppID: {}, Name: {}", singleApp.appId(), singleApp.name());
                                    }
                                }
                                totalNewGames += recoveredCount;
                                log.info("Chunk recovery complete: {}/{} games successfully rescued!", recoveredCount, chunk.size());
                            }
                        }
                    }
                }

                // 처리된 페이지 수 갱신
                processedPages++;
                log.debug("Batch finished. LastAppId: {}, More: {}", lastAppId, haveMoreResults);

                // 페이지 조회 단위 진행률 출력
                log.info("[Steam AppList Progress] Page {} completed. Current LastAppId: {}, Cumulative new games saved: {}",
                        processedPages, lastAppId, totalNewGames);

            } catch (RestClientException e) {
                // API 통신 예외 발생 시 루프 중단
                log.error("Steam API Error at LastAppId: {}. Stopping Sync.", lastAppId, e);
                break;

            } catch (DataAccessException e) {
                // DB 예외 발생 시 다음 페이지 조회 진행
                log.error("DB Save Failed at LastAppId: {}. Moving to next page", lastAppId, e);
            } catch (Exception e) {
                log.error("Unexpected Error (LastAppId: {}): ", lastAppId, e);
                break;
            }
        }
        log.info("Steam AppList Sync Finished. Total new games saved: {}", totalNewGames);
    }

    /**
     * 신규 스팀 앱 데이터를 Game 및 StoreDetail 엔티티로 변환 후 저장
     */
    public void saveNewApps(List<App> newApps) {

        List<Game> gamesToSave = new ArrayList<>();
        List<StoreDetail> detailsToSave = new ArrayList<>();

        // 응답 데이터를 Game 엔티티로 변환
        for (App app : newApps) {
            gamesToSave.add(Game.builder()
                    .title(app.name())
                    .build());
        }

        // Game 엔티티 일괄 삽입
        List<Game> savedGames = gameRepository.saveAll(gamesToSave);

        // 생성된 Game 엔티티의 ID를 매핑하여 StoreDetail 엔티티 변환
        for (int i = 0; i < newApps.size(); i++) {
            App app = newApps.get(i);
            Game savedGame = savedGames.get(i);

            String steamAppId = String.valueOf(app.appId());
            String steamUrl = urlBuilder.buildUrl(steamAppId);

            detailsToSave.add(StoreDetail.builder()
                    .game(savedGame)
                    .storeName(StoreDetail.StoreName.STEAM)
                    .storeAppId(steamAppId)
                    .url(steamUrl)
                    .build());
        }

        // StoreDetail 엔티티 일괄 삽입
        storeDetailRepository.saveAll(detailsToSave);

        log.debug("Successfully persisted {} games and store details.", newApps.size());
    }
}


