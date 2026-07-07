package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.repository.custom.GenaiFailedTaskRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GenaiFailedTaskRepository extends JpaRepository<GenaiFailedTask, Long>, GenaiFailedTaskRepositoryCustom {
}