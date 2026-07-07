package io.github.seoleeder.owls_pick.scheduler;

import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.service.genai.GenaiTaskCleanupService;
import io.github.seoleeder.owls_pick.service.genai.ReviewSummaryService;
import io.github.seoleeder.owls_pick.service.genai.localization.KeywordLocalizationService;
import io.github.seoleeder.owls_pick.service.genai.localization.LocalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GenAI 파이프라인 실패 작업 재시도 및 클린업 스케줄러
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenaiFailedTaskScheduler {

    private final ReviewSummaryService reviewSummaryService;
    private final LocalizationService localizationService;
    private final KeywordLocalizationService keywordLocalizationService;
    private final GenaiTaskCleanupService cleanupService;
    private final GenaiProperties props;

    /**
     * 미조치 실패 작업 주기적 재시도
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void runFailedTaskRetries() {
        log.info("[GenAI-Scheduler] Starting to retry failed tasks...");

        reviewSummaryService.retryFailedTasks();
        localizationService.retryFailedTasks();
        keywordLocalizationService.retryFailedTasks();
    }

    /**
     * 조치 완료 데이터 스케줄링 클린업
     */
    @Scheduled(cron = "0 0 5 * * *")
    public void runResolvedTaskCleanup() {
        log.info("[GenAI-Scheduler] Starting daily cleanup for resolved tasks...");

        cleanupService.cleanupResolvedTasks(props.failedTask().retentionDays());
    }
}