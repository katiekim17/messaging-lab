package com.example.messaging.step0_command_vs_event.command;

import java.time.Instant;
import java.util.UUID;

/**
 * "주문을 생성해라" — 아직 일어나지 않은 일. 실패할 수 있다.
 */
public record CreateOrderCommand(
        String commandId,
        String userId,
        long amount,
        Instant requestedAt
) {
    public CreateOrderCommand(String userId, long amount) {
        this(UUID.randomUUID().toString(), userId, amount, Instant.now());
    }
}
