package io.github.seoleeder.owls_pick.service.client.itad.event;

import java.time.LocalDateTime;

/**
 * 게임 할인 발생 시 알림 파이프라인으로 전달되는 이벤트 레코드
 */
public record GameDiscountEvent(
        Long gameId,
        int discountRate,
        LocalDateTime expiryDate
) {
}
