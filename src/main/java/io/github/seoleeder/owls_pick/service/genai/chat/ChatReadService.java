package io.github.seoleeder.owls_pick.service.genai.chat;

import io.github.seoleeder.owls_pick.dto.response.chat.ChatMessageResponse;
import io.github.seoleeder.owls_pick.dto.response.chat.ChatSessionListResponse;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.ChatMessageRepository;
import io.github.seoleeder.owls_pick.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatReadService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 유저의 전체 채팅 세션 목록 반환
     */
    public List<ChatSessionListResponse> getChatSessions(Long userId) {
        log.info("[ChatReadService] Fetching chat sessions for User ID: {}", userId);
        return chatSessionRepository.findSessionsByUserId(userId);
    }

    /**
     * 특정 세션의 전체 채팅 메시지 내역 상세 조회
     */
    public List<ChatMessageResponse> getChatHistory(Long userId, Long sessionId) {
        log.info("[ChatReadService] Fetching chat history for Session ID: {} by User ID: {}", sessionId, userId);

        // 세션 존재 여부 및 소유권 검증
        chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_SESSION));

        // 해당 세션 내 채팅 목록 조회 반환
        return chatMessageRepository.findMessagesBySessionIdAndUserId(sessionId, userId);
    }
}