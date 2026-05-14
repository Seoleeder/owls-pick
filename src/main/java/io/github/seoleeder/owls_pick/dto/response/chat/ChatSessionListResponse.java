package io.github.seoleeder.owls_pick.dto.response.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Owls 채팅 세션 목록 응답 DTO")
public record ChatSessionListResponse(
        @Schema(description = "세션 ID", example = "1")
        Long sessionId,

        @Schema(description = "세션 타이틀", example = "Spring Boot 비동기 처리 방법")
        String title,

        @Schema(description = "최종 수정 시각", example = "2026-05-14T02:49:39")
        LocalDateTime updatedAt
) {}