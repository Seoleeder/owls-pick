package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.dto.response.chat.ChatSessionListResponse;
import java.util.List;

public interface ChatSessionRepositoryCustom {

    // 특정 사용자의 세션 목록 조회
    List<ChatSessionListResponse> findSessionsByUserId(Long userId);
}