package io.github.seoleeder.owls_pick.service.client.hltb;

import io.github.seoleeder.owls_pick.dto.response.HltbSyncResponse;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.Playtime;
import io.github.seoleeder.owls_pick.entity.game.enums.status.SyncStatus;
import io.github.seoleeder.owls_pick.global.config.properties.HltbProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.GameRepository;
import io.github.seoleeder.owls_pick.repository.PlaytimeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class HltbSyncService {
    private final PlaytimeRepository playtimeRepository;
    private final GameRepository gameRepository;
    private final RestClient hltbRestClient;
    private final HltbProperties hltbProperties;
    private final TransactionTemplate transactionTemplate;
    private final AsyncTaskExecutor taskExecutor;

    public HltbSyncService(
            PlaytimeRepository playtimeRepository,
            GameRepository gameRepository,
            @Qualifier("hltbRestClient") RestClient hltbRestClient,
            HltbProperties hltbProperties,
            TransactionTemplate transactionTemplate,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.playtimeRepository = playtimeRepository;
        this.gameRepository = gameRepository;
        this.hltbRestClient = hltbRestClient;
        this.hltbProperties = hltbProperties;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 환경 변수에 설정된 기본 청크 사이즈를 사용하여 HLTB 동기화 파이프라인 실행
     */
    public void runSyncPipeline() {
        runSyncPipeline(hltbProperties.chunkSize());
    }

    /**
     * 지정된 청크 사이즈로 HLTB 동기화 파이프라인 실행
     */
    public void runSyncPipeline(int chunkSize) {
        log.info("[HLTB Sync] Starting Playtime Synchronization Pipeline (Chunk: {})...", chunkSize);

        int totalProcessedCount = 0;

        while (true) {
            // 동기화 대상 조회 (UNSYNCED, FAILED)
            List<Game> targets = playtimeRepository.findGamesWithUnsyncedPlaytime(chunkSize);
            if (targets.isEmpty()) break;

            // 가상 스레드 기반 병렬 처리
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(game -> CompletableFuture.runAsync(() -> syncSingleGame(game), taskExecutor))
                    .toList();

            // 청크 내 전체 작업 완료 대기
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 누적 카운트 업데이트
            totalProcessedCount += targets.size();
            log.info("[HLTB Sync] Successfully processed chunk of {} games. (Cumulative Total: {})",
                    targets.size(), totalProcessedCount);
        }

        log.info("[HLTB Sync] Pipeline Finished.");
    }

    /**
     * 단일 청크 동기화 파이프라인 실행
     */
    public int runSingleBatchSync(int chunkSize) {
        log.info("[HLTB Sync] Running Single Batch Sync (Size: {})...", chunkSize);

        // 동기화 대상 조회
        List<Game> targets = playtimeRepository.findGamesWithUnsyncedPlaytime(chunkSize);

        if (targets.isEmpty()) {
            log.info("[HLTB Sync] No unsynced games found.");
            return 0;
        }

        List<CompletableFuture<Void>> futures = targets.stream()
                .map(game -> CompletableFuture.runAsync(() -> syncSingleGame(game), taskExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("[HLTB Sync] Single Batch Sync Completed. Processed count: {}", targets.size());

        return targets.size();
    }

    /**
     * 단건 플레이타임 데이터 동기화 및 DB 반영
     */
    private void syncSingleGame(Game game) {
        try {
            // FastAPI 서버에 HLTB 데이터 수집 요청
            HltbSyncResponse response = fetchFromFastApi(game.getTitle());

            // 정상 수집된 플레이타임 데이터 DB 업데이트
            transactionTemplate.executeWithoutResult(status -> updatePlaytime(game, response));
        } catch (Exception e) {
            log.error("[HLTB Sync] Failed to sync game [{}]: {}", game.getTitle(), e.getMessage());

            // 수집 실패 시 상태값 DB 업데이트
            transactionTemplate.executeWithoutResult(status -> markAsFailed(game));
        }
    }

    /**
     * FastAPI 서버와의 통신하여 게임의 플레이타임 정보 요청
     */
    private HltbSyncResponse fetchFromFastApi(String gameName) {

        try {
            return hltbRestClient.get()
                    .uri(uriBuilder -> UriComponentsBuilder.fromUriString(hltbProperties.fastapiUrl())
                            .path("/api/hltb/scrape")
                            .queryParam("game_name", gameName)
                            .build()
                            .toUri())
                    .retrieve()
                    .body(HltbSyncResponse.class);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
        }
    }

    /**
     * HLTB에서 수집한 데이터를 Playtime 엔티티에 병합 및 업데이트
     */
    private void updatePlaytime(Game game, HltbSyncResponse response) {
        // 기존 플레이타임 정보 DB 조회 (없을 경우 신규 생성)
        Playtime playtime = playtimeRepository.findById(game.getId())
                .orElseGet(() -> {
                    // Game 프록시 객체를 획득하여 영속성 유지
                    Game gameProxy = gameRepository.getReferenceById(game.getId());
                    return Playtime.builder().game(gameProxy).build();
                });

        // 플레이타임 데이터가 모두 비어있는지(0 또는 null) 확인
        boolean hasNoData = (response.getMainStoryAsMinutes() == null || response.getMainStoryAsMinutes() == 0) &&
                (response.getMainExtraAsMinutes() == null || response.getMainExtraAsMinutes() == 0) &&
                (response.getCompletionistAsMinutes() == null || response.getCompletionistAsMinutes() == 0);

        // FastAPI가 SUCCESS를 리턴했어도, 실제 데이터가 없으면 NO_DATA로 상태 보정
        SyncStatus finalStatus = response.status();
        if (finalStatus == SyncStatus.SUCCESS && hasNoData) {
            log.info("[HLTB Sync] Game [{}] found on HLTB, but no playtime data exists. Marked as NO_DATA.", game.getTitle());
            finalStatus = SyncStatus.NO_DATA;
        }

        // HLTB 응답 데이터 엔티티 적용 (분 단위 변환)
        playtime.updateSyncResult(
                response.getMainStoryAsMinutes(),
                response.getMainExtraAsMinutes(),
                response.getCompletionistAsMinutes(),
                finalStatus
        );
        playtimeRepository.save(playtime);
    }

    /**
     * 동기화 실패 시 해당 게임의 플레이타임 상태를 FAILED로 마킹하여 무한 재조회 방지
     */
    private void markAsFailed(Game game) {
        // 기존 플레이타임 정보 DB 조회 (없을 경우 신규 생성)
        Playtime playtime = playtimeRepository.findById(game.getId())
                .orElseGet(() -> {
                    // Game 프록시 객체를 획득하여 영속성 유지
                    Game gameProxy = gameRepository.getReferenceById(game.getId());
                    return Playtime.builder().game(gameProxy).build();
                });

        // 플레이타임 수집 실패 상태 적용
        playtime.updateSyncResult(null, null, null, SyncStatus.FAILED);
        playtimeRepository.save(playtime);
    }
}
//    /**
//     * 스프링 컨텍스트 종료 시 가상 스레드 풀 자원 반환
//     */
//    @PreDestroy
//    public void shutdownExecutor() {
//        log.info("[HLTB Sync] Shutting down HLTB Sync ExecutorService...");
//
//        // 새로운 작업 수락 중단
//        executorService.shutdown();
//
//        try {
//            // 5초 대기 후 남은 작업 강제 종료
//            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
//                executorService.shutdownNow();
//            }
//        } catch (InterruptedException e) {
//            executorService.shutdownNow();
//            Thread.currentThread().interrupt();
//        }
//    }

