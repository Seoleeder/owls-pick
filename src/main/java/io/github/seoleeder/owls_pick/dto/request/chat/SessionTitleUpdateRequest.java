package io.github.seoleeder.owls_pick.dto.request.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 클라이언트가 기존 채팅 세션의 타이틀을 수동 변경할 때 사용하는 요청 데이터
 */
public record SessionTitleUpdateRequest(
        @NotBlank(message = "Title cannot be blank")
        @Size(max = 30, message = "Title must not exceed 30 characters")
        String title
) {}
