package com.example.messaging.step5_kafka.relay;

import com.example.messaging.step5_kafka.domain.OutboxEvent;
import com.example.messaging.step5_kafka.domain.OutboxStatus;
import com.example.messaging.step5_kafka.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PENDING 이벤트를 Kafka로 발행하고 SENT로 상태를 변경하는 릴레이.
 * Step 3의 EventRelay가 DB 내에서 처리했다면, 이 릴레이는 Kafka로 발행한다.
 * Step 3 Event Store + 이 Kafka Relay = Transactional Outbox Pattern.
 */
@Component
public class KafkaEventRelay {

    private final OutboxEventRepository outboxEventRepository;

    public KafkaEventRelay(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public int relayPendingEvents(KafkaProducer<String, String> producer, String topic) {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatus(OutboxStatus.PENDING);
        for (OutboxEvent event : pendingEvents) {
            try {
                producer.send(new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload())).get();
                event.markSent();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                // 발행 실패 시 PENDING 상태 유지 → 다음 릴레이에서 재시도
                break;
            }
        }
        return (int) pendingEvents.stream()
                .filter(e -> e.getStatus() == OutboxStatus.SENT)
                .count();
    }
}
