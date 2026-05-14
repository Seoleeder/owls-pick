package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.user.ChatSession;
import io.github.seoleeder.owls_pick.repository.custom.ChatSessionRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long>, ChatSessionRepositoryCustom {

    // 세션 ID와 유저 ID로 특정 세션 단건 조회
    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);
}
