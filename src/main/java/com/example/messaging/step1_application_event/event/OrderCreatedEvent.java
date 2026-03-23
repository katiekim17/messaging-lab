package com.example.messaging.step1_application_event.event;

import java.time.Instant;

public record OrderCreatedEvent(Long orderId, String userId, long amount, Instant occurredAt) {
}
