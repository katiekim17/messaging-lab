package com.example.messaging.step0_command_vs_event.event;

import java.time.Instant;

/**
 * "주문이 생성되었다" — 이미 확정된 사실. 발행자는 누가 듣는지 모른다.
 */
public record OrderCreatedEvent(String orderId, String userId, long amount, Instant occurredAt) {
}
