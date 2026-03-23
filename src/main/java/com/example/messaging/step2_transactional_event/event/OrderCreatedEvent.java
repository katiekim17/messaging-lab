package com.example.messaging.step2_transactional_event.event;

import java.time.Instant;

public record OrderCreatedEvent(Long orderId, String userId, long amount, Instant occurredAt) {
}
