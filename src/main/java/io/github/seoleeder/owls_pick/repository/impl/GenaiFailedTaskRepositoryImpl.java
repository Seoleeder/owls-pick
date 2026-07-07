package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.repository.custom.GenaiFailedTaskRepositoryCustom;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

import static io.github.seoleeder.owls_pick.entity.genai.QGenaiFailedTask.genaiFailedTask;

@RequiredArgsConstructor
public class GenaiFailedTaskRepositoryImpl implements GenaiFailedTaskRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /**
     * 특정 GenAI 파이프라인의 실패 작업 목록 조회
     */
    @Override
    public List<GenaiFailedTask> findUnhandledTasks(GenaiPipelineType pipelineType) {
        return queryFactory
                .selectFrom(genaiFailedTask)
                .where(
                        // 파이프라인 타입 필터링
                        genaiFailedTask.pipelineType.eq(pipelineType),
                        // 아직 조치되지 않은 작업 데이터 필터링
                        genaiFailedTask.isHandled.eq(false)
                )
                // 식별자 기준 오름차순 정렬
                .orderBy(genaiFailedTask.id.asc())
                .fetch();
    }

    /**
     *  보관 기한을 초과한 조치 완료 데이터 일괄 삭제
     */
    @Override
    public long deleteHandledTasksOlderThan(LocalDateTime cutoffDateTime) {
        return queryFactory
                .delete(genaiFailedTask)
                .where(
                        genaiFailedTask.isHandled.eq(true),
                        genaiFailedTask.updatedAt.lt(cutoffDateTime)
                )
                .execute();
    }
}