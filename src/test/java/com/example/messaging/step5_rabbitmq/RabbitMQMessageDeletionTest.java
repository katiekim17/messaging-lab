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
 * RabbitMQ의 핵심 특성: ACK한 메시지는 큐에서 삭제된다.
 * 소비 완료된 메시지를 다시 읽을 수 없다 — 재처리 불가.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RabbitMQMessageDeletionTest {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Test
    void ACK한_메시지는_큐에서_삭제되어_다시_읽을_수_없다() {

        CachingConnectionFactory factory = new CachingConnectionFactory(
                rabbit.getHost(), rabbit.getAmqpPort());
        RabbitTemplate template = new RabbitTemplate(factory);

        String queueName = "deletion-test";
        template.execute(channel -> {
            channel.queueDeclare(queueName, false, false, false, null);
            return null;
        });

        // 3건 발행
        template.convertAndSend(queueName, "msg-1");
        template.convertAndSend(queueName, "msg-2");
        template.convertAndSend(queueName, "msg-3");

        // 3건 소비 (auto-ack)
        Object r1 = template.receiveAndConvert(queueName, 5000);
        Object r2 = template.receiveAndConvert(queueName, 5000);
        Object r3 = template.receiveAndConvert(queueName, 5000);

        assertThat(r1.toString()).isEqualTo("msg-1");
        assertThat(r2.toString()).isEqualTo("msg-2");
        assertThat(r3.toString()).isEqualTo("msg-3");

        // 다시 읽기 시도 → 큐가 비었다
        Object nothing = template.receiveAndConvert(queueName, 1000);
        assertThat(nothing)
                .as("ACK한 메시지는 큐에서 삭제되어 다시 읽을 수 없다")
                .isNull();

        factory.destroy();
    }
}
