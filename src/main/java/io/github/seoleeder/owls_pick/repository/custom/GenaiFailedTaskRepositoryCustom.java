package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;

import java.time.LocalDateTime;
import java.util.List;

public interface GenaiFailedTaskRepositoryCustom {

    // 특정 GenAI 파이프라인의 실패 작업 목록 조회
    List<GenaiFailedTask> findUnhandledTasks(GenaiPipelineType pipelineType);

    // 보관 기한을 초과한 조치 완료 데이터 일괄 삭제
    long deleteHandledTasksOlderThan(LocalDateTime cutoffDateTime);
}

