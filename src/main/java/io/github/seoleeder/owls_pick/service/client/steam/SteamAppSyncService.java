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

    public void syncAppList(){
        log.info("Starting Steam AppList sync...");

        // DB에 저장된 steam_app_id 조회
        Set<String> existingSteamIds = storeDetailRepository.findAllAppIdsByStore(StoreDetail.StoreName.STEAM);
        log.debug("Loaded {} existing Steam Ids from DB. ", existingSteamIds.size());

        Long lastAppId = null;
        boolean haveMoreResults = true;
        int totalNewGames = 0;

        //데이터가 더이상 없을 때까지 데이터 수집
        while (haveMoreResults) {
            try {
                // API 호출 (lastAppId 전달)
                SteamAppListResponse response = collector.collectAppList(lastAppId);

                // 방어 로직
                if (response == null || response.response() == null) {
                    throw new RestClientException("Empty Response from Steam API");
                }

                // 커서 갱신 (다음 페이지 위치 먼저 확보)
                lastAppId = response.response().lastAppId();
                haveMoreResults = response.response().haveMoreResults();

                List<App> apps = response.response().apps();

                // DB에서 조회한 steam id와 비교해서 필터링
                if (apps != null && !apps.isEmpty()) {
                    List<App> newApps = apps.stream()
                            .filter(app -> !existingSteamIds.contains(String.valueOf(app.appId())))
                            .filter(app -> app.name() != null && !app.name().isBlank())
                            .filter(app -> app.name().length() <= 255)
                            .toList();

                    log.debug("Fetched {} apps. Found {} new apps to save. (LastAppId: {})", apps.size(), newApps.size(), lastAppId);

                    // 새로운 게임이 있으면 저장 (Chunk 단위로 쪼개고, 저장 실패 시 문제되는 데이터 제외하고 개별 저장)
                    if (!newApps.isEmpty()) {
                        int chunkSize = 1000;
                        for (int i = 0; i < newApps.size(); i += chunkSize) {
                            List<App> chunk = newApps.subList(i, Math.min(newApps.size(), i + chunkSize));
                            try{
                                //저장 메서드 내부 트랜잭션 적용
                                transactionTemplate.executeWithoutResult(status -> {
                                    saveNewApps(chunk);
                                });
                                totalNewGames += chunk.size();

                            } catch (DataAccessException e) {
                                log.warn("Chunk save failed! Attempting individual saves to isolate bad data. (Chunk size: {})", chunk.size());

                                // 청크 내에서 개별로 저장된 게임 수
                                int recoveredCount = 0;
                                for (App singleApp : chunk) {
                                    try {
                                        // 단건을 새로운 트랜잭션으로 묶어서 저장
                                        transactionTemplate.executeWithoutResult(status -> {
                                            saveNewApps(Collections.singletonList(singleApp));
                                        });
                                        recoveredCount++;
                                    } catch (DataAccessException ex) {
                                        // DB 제약조건을 위반하는 데이터 감지 및 폐기
                                        log.warn("Bad data detected and discarded - AppID: {}, Name: {}", singleApp.appId(), singleApp.name());
                                    }
                                }
                                totalNewGames += recoveredCount;
                                log.info("Chunk recovery complete: {}/{} games successfully rescued!", recoveredCount, chunk.size());
                            }
                        }
                    }
                }

                log.debug("Batch finished. LastAppId: {}, More: {}", lastAppId, haveMoreResults);

            } catch (RestClientException e) {
                // 네트워크 오류/타임아웃 -> 로그 남기고 중단 (다음 스케줄에 이어서 함)
                log.error("Steam API Error at LastAppId: {}. Stopping Sync.", lastAppId, e);
                break;

            } catch (DataAccessException e) {
                // DB 저장 실패
                // nextCursor는 유효한 상태 -> 다음 페이지 조회
                log.error("DB Save Failed at LastAppId: {}. Moving to next page", lastAppId, e);
            } catch (Exception e) {
                // 알 수 없는 오류
                log.error("Unexpected Error (LastAppId: {}): ", lastAppId, e);
                break;
            }
        }

        log.info("Steam AppList Sync Finished. Total new games saved: {}", totalNewGames);

    }

    // 새로운 게임 앱 저장
    public void saveNewApps(List<App> newApps) {

        List<Game> gamesToSave = new ArrayList<>();
        List<StoreDetail> detailsToSave = new ArrayList<>();

        // Game 엔티티 리스트 생성
        for (App app : newApps) {
            gamesToSave.add(Game.builder()
                    .title(app.name())
                    .build());
        }

        // Game 일괄 저장
        List<Game> savedGames = gameRepository.saveAll(gamesToSave);

        // StoreDetail 엔티티 리스트 생성 (저장된 Game과 연결)
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

        // StoreDetail 일괄 저장
        storeDetailRepository.saveAll(detailsToSave);

        log.debug("Successfully persisted {} games and store details.", newApps.size());
    }
}


