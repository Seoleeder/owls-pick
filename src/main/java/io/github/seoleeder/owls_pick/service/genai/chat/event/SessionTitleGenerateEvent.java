package io.github.seoleeder.owls_pick.service.genai.chat.event;

/**
 * 채팅 세션 타이틀 비동기 생성을 위한 이벤트 레코드
 */
public record SessionTitleGenerateEvent(
        Long sessionId,
        String userMessage
) {}
