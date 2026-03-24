package com.example.messaging.step5_rabbitmq;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소비 완료된 메시지를 다른 Consumer가 다시 읽을 수 없다.
 * Kafka의 Consumer Group처럼 독립적 재소비가 불가능하다.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RabbitMQNoReplayTest {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Test
    void 소비_완료된_메시지를_다른_Consumer가_다시_읽을_수_없다() {

        CachingConnectionFactory factory = new CachingConnectionFactory(
                rabbit.getHost(), rabbit.getAmqpPort());

        String queueName = "no-replay-test";

        RabbitTemplate consumer1Template = new RabbitTemplate(factory);
        consumer1Template.execute(channel -> {
            channel.queueDeclare(queueName, false, false, false, null);
            return null;
        });

        // 3건 발행
        consumer1Template.convertAndSend(queueName, "msg-1");
        consumer1Template.convertAndSend(queueName, "msg-2");
        consumer1Template.convertAndSend(queueName, "msg-3");

        // Consumer 1이 3건 전부 소비
        consumer1Template.receiveAndConvert(queueName, 5000);
        consumer1Template.receiveAndConvert(queueName, 5000);
        consumer1Template.receiveAndConvert(queueName, 5000);

        // Consumer 2 (다른 시스템이라고 가정)가 같은 큐에서 읽기 시도
        RabbitTemplate consumer2Template = new RabbitTemplate(factory);
        Object fromConsumer2 = consumer2Template.receiveAndConvert(queueName, 1000);

        // Consumer 1이 이미 소비해서 큐가 비었다 → Consumer 2는 읽을 수 없다
        assertThat(fromConsumer2)
                .as("소비 완료된 메시지는 큐에서 삭제되어 다른 Consumer가 읽을 수 없다")
                .isNull();

        // Kafka라면? Consumer Group이 다르면 같은 메시지를 독립적으로 읽을 수 있다.

        factory.destroy();
    }
}
