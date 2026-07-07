package io.github.seoleeder.owls_pick.service.genai;

import io.github.seoleeder.owls_pick.repository.GenaiFailedTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * GenAI 파이프라인 공통 데이터 클린업 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenaiTaskCleanupService {

    private final GenaiFailedTaskRepository failedTaskRepository;

    /**
     * 보관 기한이 만료된 조치 완료 상태의 실패 작업 일괄 삭제
     */
    @Transactional
    public void cleanupResolvedTasks(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long deletedCount = failedTaskRepository.deleteHandledTasksOlderThan(cutoff);

        if (deletedCount > 0) {
            log.info("[GenAI] Cleaned up {} resolved tasks older than {} days.", deletedCount, retentionDays);
        }
    }
}