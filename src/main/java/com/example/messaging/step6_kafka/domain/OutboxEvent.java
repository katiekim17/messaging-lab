package com.example.messaging.step6_kafka.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "step5_outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false, length = 2000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventType, String aggregateId, String payload) {
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
    }
}
