package com.example.messaging.step5_kafka;

import com.example.messaging.step5_kafka.domain.OutboxEvent;
import com.example.messaging.step5_kafka.domain.OutboxStatus;
import com.example.messaging.step5_kafka.relay.KafkaEventRelay;
import com.example.messaging.step5_kafka.repository.OrderRepository;
import com.example.messaging.step5_kafka.repository.OutboxEventRepository;
import com.example.messaging.step5_kafka.service.OutboxOrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 3의 Event Store + Kafka Relay = Transactional Outbox Pattern 완성.
 *
 * Step 3에서 한 것:  도메인 저장 + Event Store 기록 (같은 TX)
 * 이 Step에서 추가:  릴레이가 Event Store → Kafka로 발행
 * 합치면:           Transactional Outbox Pattern
 */
@SpringBootTest(classes = Step5TestConfig.class)
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransactionalOutboxCompletionTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    OutboxOrderService orderService;

    @Autowired
    KafkaEventRelay kafkaEventRelay;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    OrderRepository orderRepository;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void 주문_저장과_이벤트_기록이_하나의_트랜잭션으로_묶인다() {
        // When
        Long orderId = orderService.createOrder("노트북", 1_500_000L);

        // Then: 주문 + Outbox 이벤트가 같은 TX로 저장됨
        assertThat(orderRepository.findById(orderId)).isPresent();

        List<OutboxEvent> pending = outboxEventRepository.findByStatus(OutboxStatus.PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(pending.get(0).getAggregateId()).isEqualTo(orderId.toString());
    }

    /**
     * 흐름:
     *   주문 생성 → Outbox에 PENDING 기록 (같은 TX)
     *   → 릴레이가 PENDING 조회 → Kafka로 발행 → 상태를 SENT로 변경
     *   → Kafka Consumer가 메시지 수신 확인
     *
     * 증명: Step 3의 Event Store + Kafka Relay = Transactional Outbox Pattern 완성
     */
    @Test
    void 릴레이가_PENDING_이벤트를_Kafka로_발행하고_SENT로_변경한다() throws Exception {
        // Given
        String topic = "order-events";
        KafkaTestBase.createTopicWithBootstrap(KAFKA.getBootstrapServers(), topic, 1);
        Long orderId = orderService.createOrder("노트북", 1_500_000L);

        // When: Outbox → Kafka 릴레이
        try (KafkaProducer<String, String> producer = createProducerFor(KAFKA.getBootstrapServers())) {
            int sent = kafkaEventRelay.relayPendingEvents(producer, topic);
            assertThat(sent).isEqualTo(1);
        }

        // Then: Outbox 상태가 SENT로 변경
        assertThat(outboxEventRepository.findByStatus(OutboxStatus.PENDING)).isEmpty();
        assertThat(outboxEventRepository.findByStatus(OutboxStatus.SENT)).hasSize(1);

        // Then: Kafka에서 메시지 수신 가능
        try (KafkaConsumer<String, String> consumer = createConsumerFor(KAFKA.getBootstrapServers(), "outbox-verify")) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = KafkaTestBase.pollMessagesStatic(consumer, 1, Duration.ofSeconds(10));

            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo(orderId.toString());
            assertThat(records.get(0).value()).contains("노트북");
        }
    }

    @Test
    void Kafka_발행_실패_시_이벤트는_여전히_PENDING_상태를_유지한다() {
        // Given
        orderService.createOrder("노트북", 1_500_000L);

        // When: 잘못된 bootstrap server로 릴레이 시도
        try (KafkaProducer<String, String> badProducer = createProducerFor("localhost:19999")) {
            kafkaEventRelay.relayPendingEvents(badProducer, "order-events");
        } catch (Exception ignored) {
        }

        // Then: 이벤트는 여전히 PENDING — 다음 릴레이에서 재시도 가능
        assertThat(outboxEventRepository.findByStatus(OutboxStatus.PENDING)).hasSize(1);
        assertThat(outboxEventRepository.findByStatus(OutboxStatus.SENT)).isEmpty();
    }

    // -- 유틸리티 --

    private static KafkaProducer<String, String> createProducerFor(String bootstrapServers) {
        return new KafkaProducer<>(java.util.Map.of(
                "bootstrap.servers", bootstrapServers,
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "max.block.ms", "3000"
        ));
    }

    private static KafkaConsumer<String, String> createConsumerFor(String bootstrapServers, String groupId) {
        return new KafkaConsumer<>(java.util.Map.of(
                "bootstrap.servers", bootstrapServers,
                "group.id", groupId,
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false"
        ));
    }
}
