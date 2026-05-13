package io.github.seoleeder.owls_pick.dto.request.chat;

/**
 * Owls 채팅 세션 타이틀 요약을 위한 FastAPI 요청 데이터
 */
public record TitleGenerationRequest(
        String userMessage
) {
}
