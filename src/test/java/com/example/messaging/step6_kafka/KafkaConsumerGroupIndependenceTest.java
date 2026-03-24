package com.example.messaging.step6_kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer Group 간 독립적 소비를 확인한다.
 * 각 Group은 자기만의 offset을 관리하며, 같은 메시지를 독립적으로 읽는다.
 */
class KafkaConsumerGroupIndependenceTest extends KafkaTestBase {

    @Test
    void 두_Consumer_Group이_같은_토픽의_모든_메시지를_각각_독립적으로_수신한다() throws Exception {
        String topic = "group-independence-test";
        createTopic(topic, 1);

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(topic, "msg-1")).get();
            producer.send(new ProducerRecord<>(topic, "msg-2")).get();
            producer.send(new ProducerRecord<>(topic, "msg-3")).get();
        }

        // Group A: 정산 서비스
        try (KafkaConsumer<String, String> groupA = createConsumer("settlement-group")) {
            groupA.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> recordsA = pollMessages(groupA, 3, Duration.ofSeconds(10));
            assertThat(recordsA).hasSize(3);
        }

        // Group B: 알림 서비스 — Group A와 완전히 독립
        try (KafkaConsumer<String, String> groupB = createConsumer("notification-group")) {
            groupB.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> recordsB = pollMessages(groupB, 3, Duration.ofSeconds(10));
            assertThat(recordsB).hasSize(3);
        }

        // 두 Group 모두 동일한 3건을 각각 수신했다
    }

    /**
     * 흐름:
     *   Phase 1: msg 1~3 발행 → Group A(slow)가 3건 읽고 commit
     *   Phase 2: msg 4~5 추가 발행
     *   → Group B(fast): 5건 전부 읽기 (독립적 offset, earliest)
     *   → Group A 재연결: msg 4~5만 읽기 (committed offset 이후)
     *
     * 증명: 각 Consumer Group은 자기만의 offset을 관리하며 서로 영향을 주지 않는다
     */
    @Test
    void 한_Consumer_Group의_소비_속도가_다른_Group에_영향을_주지_않는다() throws Exception {
        String topic = "group-speed-test";
        createTopic(topic, 1);

        // Phase 1: 3건 발행 → Group A가 읽고 commit
        try (KafkaProducer<String, String> producer = createProducer()) {
            for (int i = 1; i <= 3; i++) {
                producer.send(new ProducerRecord<>(topic, "msg-" + i)).get();
            }
        }

        try (KafkaConsumer<String, String> groupA = createConsumer("slow-group")) {
            groupA.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> recordsA = pollMessages(groupA, 3, Duration.ofSeconds(10));
            assertThat(recordsA).hasSize(3);
            groupA.commitSync();
        }

        // Phase 2: 2건 추가 발행
        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(topic, "msg-4")).get();
            producer.send(new ProducerRecord<>(topic, "msg-5")).get();
        }

        // Group B: 5건 전부 읽기 (독립적 offset)
        try (KafkaConsumer<String, String> groupB = createConsumer("fast-group")) {
            groupB.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> recordsB = pollMessages(groupB, 5, Duration.ofSeconds(10));
            assertThat(recordsB).hasSize(5);
        }

        // Group A 재연결: 나머지 2건만 읽기
        try (KafkaConsumer<String, String> groupA2 = createConsumer("slow-group")) {
            groupA2.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> remaining = pollMessages(groupA2, 2, Duration.ofSeconds(10));
            assertThat(remaining).hasSize(2);
            assertThat(remaining.stream().map(ConsumerRecord::value).toList())
                    .containsExactly("msg-4", "msg-5");
        }
    }
}
