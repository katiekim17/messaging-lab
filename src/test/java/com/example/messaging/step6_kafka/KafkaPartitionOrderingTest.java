package com.example.messaging.step6_kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka의 파티션 기반 순서 보장을 확인한다.
 * 같은 key → 같은 partition → 순서 보장.
 */
class KafkaPartitionOrderingTest extends KafkaTestBase {

    @Test
    void 같은_key의_메시지는_같은_파티션에_저장된다() throws Exception {
        String topic = "partition-key-test";
        createTopic(topic, 3);

        try (KafkaProducer<String, String> producer = createProducer();
             KafkaConsumer<String, String> consumer = createConsumer("partition-group")) {

            // 같은 key로 5건 발행
            for (int i = 0; i < 5; i++) {
                producer.send(new ProducerRecord<>(topic, "order-1001", "event-" + i)).get();
            }

            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = pollMessages(consumer, 5, Duration.ofSeconds(10));

            // 모두 동일한 파티션에 저장됨
            Set<Integer> partitions = records.stream()
                    .map(ConsumerRecord::partition)
                    .collect(Collectors.toSet());

            assertThat(records).hasSize(5);
            assertThat(partitions).hasSize(1); // 하나의 파티션에만 들어감
        }
    }

    @Test
    void 같은_파티션의_메시지는_발행_순서대로_소비된다() throws Exception {
        String topic = "partition-order-test";
        createTopic(topic, 3);

        try (KafkaProducer<String, String> producer = createProducer();
             KafkaConsumer<String, String> consumer = createConsumer("order-verify-group")) {

            // 같은 key → 같은 파티션 → 순서 보장
            producer.send(new ProducerRecord<>(topic, "order-1001", "생성")).get();
            producer.send(new ProducerRecord<>(topic, "order-1001", "결제")).get();
            producer.send(new ProducerRecord<>(topic, "order-1001", "배송")).get();

            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = pollMessages(consumer, 3, Duration.ofSeconds(10));

            assertThat(records.stream().map(ConsumerRecord::value).toList())
                    .containsExactly("생성", "결제", "배송");
        }
    }

    @Test
    void 다른_key의_메시지는_다른_파티션으로_분배될_수_있다() throws Exception {
        String topic = "partition-distribution-test";
        createTopic(topic, 3);

        try (KafkaProducer<String, String> producer = createProducer();
             KafkaConsumer<String, String> consumer = createConsumer("distribution-group")) {

            // 여러 key로 발행 → 다른 파티션으로 분배
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>(topic, "order-" + i, "event")).get();
            }

            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = pollMessages(consumer, 10, Duration.ofSeconds(10));

            Set<Integer> partitions = records.stream()
                    .map(ConsumerRecord::partition)
                    .collect(Collectors.toSet());

            assertThat(records).hasSize(10);
            // 10개 key가 3개 파티션에 분배 → 최소 2개 파티션 사용
            assertThat(partitions.size()).isGreaterThanOrEqualTo(2);
        }
    }
}
