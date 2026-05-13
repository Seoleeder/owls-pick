package io.github.seoleeder.owls_pick.dto.response.chat;

/**
 * FastAPI 서버로부터 반환받은 Owls 채팅 세션 타이틀 응답 데이터
 */
public record TitleGenerationResponse(
        String title
) {
}
