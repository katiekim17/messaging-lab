package com.example.messaging.step5_kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Producer → Consumer 기본 파이프라인을 확인한다.
 */
class KafkaBasicPipelineTest extends KafkaTestBase {

    @Test
    void Producer가_보낸_메시지를_Consumer가_수신한다() throws Exception {
        String topic = "basic-test";
        createTopic(topic, 1);

        try (KafkaProducer<String, String> producer = createProducer();
             KafkaConsumer<String, String> consumer = createConsumer("basic-group")) {

            // When
            producer.send(new ProducerRecord<>(topic, "key-1", "주문이 생성되었다")).get();

            // Then
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = pollMessages(consumer, 1, Duration.ofSeconds(10));

            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo("key-1");
            assertThat(records.get(0).value()).isEqualTo("주문이 생성되었다");
        }
    }

    @Test
    void 여러_메시지를_순서대로_발행하면_같은_파티션에서_순서대로_소비된다() throws Exception {
        String topic = "order-test";
        createTopic(topic, 1);

        try (KafkaProducer<String, String> producer = createProducer();
             KafkaConsumer<String, String> consumer = createConsumer("order-group")) {

            // When: 같은 key → 같은 파티션 → 순서 보장
            producer.send(new ProducerRecord<>(topic, "order-1", "생성")).get();
            producer.send(new ProducerRecord<>(topic, "order-1", "결제")).get();
            producer.send(new ProducerRecord<>(topic, "order-1", "배송")).get();

            // Then
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = pollMessages(consumer, 3, Duration.ofSeconds(10));

            assertThat(records).hasSize(3);
            assertThat(records.stream().map(ConsumerRecord::value).toList())
                    .containsExactly("생성", "결제", "배송");
        }
    }
}
