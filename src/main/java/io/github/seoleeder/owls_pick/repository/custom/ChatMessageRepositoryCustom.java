package io.github.seoleeder.owls_pick.repository.custom;

import io.github.seoleeder.owls_pick.dto.response.chat.ChatMessageResponse;
import io.github.seoleeder.owls_pick.entity.user.ChatMessage;

import java.util.List;

public interface ChatMessageRepositoryCustom {
    // 세션의 최근 대화 내역을 지정한 개수만큼 최신순으로 조회
    List<ChatMessage> findRecentMessages(Long sessionId, int limit);

    // 사용자 ID와 세션 ID를 기반으로 채팅 메시지 목록 조회
    List<ChatMessageResponse> findMessagesBySessionIdAndUserId(Long sessionId, Long userId);
}
