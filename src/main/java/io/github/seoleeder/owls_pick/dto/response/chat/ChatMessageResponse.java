package io.github.seoleeder.owls_pick.dto.response.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "채팅 메시지 내역 응답 DTO")
public record ChatMessageResponse(
        @Schema(description = "메시지 ID", example = "105")
        Long messageId,

        @Schema(description = "발화자 역할 (USER or ASSISTANT)", example = "USER")
        String role,

        @Schema(description = "메시지 내용", example = "양자 역학 요소가 들어간 우주 게임 추천해줘.")
        String content,

        @Schema(description = "생성 시각", example = "2026-05-14T21:40:00")
        LocalDateTime createdAt
) {}
