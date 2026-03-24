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
 * Kafka의 메시지 보존을 확인한다.
 * Consumer가 중지되어도 메시지는 로그에 남아있어 재시작 후 이어서 읽을 수 있다.
 */
class KafkaMessagePreservationTest extends KafkaTestBase {

    /**
     * 흐름:
     *   Phase 1: Consumer A가 msg 1~3 읽고 commit 후 종료
     *   Phase 2: Consumer 없는 상태에서 msg 4~5 발행
     *   Phase 3: 같은 group으로 Consumer B 생성 → offset 3부터 이어서 읽음
     *
     * 증명: Redis Pub/Sub과 달리 Kafka는 메시지를 로그에 보존하여 재시작 후에도 이어서 읽는다
     */
    @Test
    void Consumer가_중지된_사이에_발행된_메시지를_재시작_후_이어서_읽는다() throws Exception {
        String topic = "preservation-test";
        createTopic(topic, 1);

        try (KafkaProducer<String, String> producer = createProducer()) {
            // Phase 1: Consumer A가 3건 읽고 commit 후 종료
            producer.send(new ProducerRecord<>(topic, "msg-1")).get();
            producer.send(new ProducerRecord<>(topic, "msg-2")).get();
            producer.send(new ProducerRecord<>(topic, "msg-3")).get();

            try (KafkaConsumer<String, String> consumerA = createConsumer("preservation-group")) {
                consumerA.subscribe(List.of(topic));
                List<ConsumerRecord<String, String>> first = pollMessages(consumerA, 3, Duration.ofSeconds(10));
                assertThat(first).hasSize(3);
                consumerA.commitSync();
            }
            // Consumer A 종료됨

            // Phase 2: Consumer 없는 상태에서 2건 추가 발행
            producer.send(new ProducerRecord<>(topic, "msg-4")).get();
            producer.send(new ProducerRecord<>(topic, "msg-5")).get();

            // Phase 3: 같은 group으로 Consumer B 생성 → offset 3부터 이어서 읽음
            try (KafkaConsumer<String, String> consumerB = createConsumer("preservation-group")) {
                consumerB.subscribe(List.of(topic));
                List<ConsumerRecord<String, String>> resumed = pollMessages(consumerB, 2, Duration.ofSeconds(10));

                assertThat(resumed).hasSize(2);
                assertThat(resumed.stream().map(ConsumerRecord::value).toList())
                        .containsExactly("msg-4", "msg-5");
                // → Redis Pub/Sub이었다면 msg-4, msg-5는 유실되었을 것이다
            }
        }
    }

    @Test
    void 구독자가_없어도_메시지는_Kafka에_보존된다() throws Exception {
        String topic = "no-subscriber-test";
        createTopic(topic, 1);

        // 구독자 없이 발행
        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(topic, "msg-1")).get();
            producer.send(new ProducerRecord<>(topic, "msg-2")).get();
            producer.send(new ProducerRecord<>(topic, "msg-3")).get();
        }

        // 나중에 Consumer 연결
        try (KafkaConsumer<String, String> consumer = createConsumer("late-group")) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = pollMessages(consumer, 3, Duration.ofSeconds(10));

            // 3건 전부 수신됨 — 메시지가 로그에 보존되어 있었다
            assertThat(records).hasSize(3);
            assertThat(records.stream().map(ConsumerRecord::value).toList())
                    .containsExactly("msg-1", "msg-2", "msg-3");
            // → Redis Pub/Sub은 구독자 없으면 즉시 폐기되었다 (Step 4 참고)
        }
    }
}
