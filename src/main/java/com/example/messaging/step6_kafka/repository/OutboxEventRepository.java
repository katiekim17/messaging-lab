package com.example.messaging.step6_kafka.repository;

import com.example.messaging.step6_kafka.domain.OutboxEvent;
import com.example.messaging.step6_kafka.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByStatus(OutboxStatus status);
}
