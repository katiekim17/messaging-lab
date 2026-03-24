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
 * RabbitMQ의 메시지 보존을 확인한다.
 * Consumer가 없어도 메시지는 큐에 남아있어서, 나중에 연결해도 받을 수 있다.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RabbitMQMessagePreservationTest {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Test
    void Consumer가_없어도_메시지는_큐에_보존된다() {

        CachingConnectionFactory factory = new CachingConnectionFactory(
                rabbit.getHost(), rabbit.getAmqpPort());
        RabbitTemplate template = new RabbitTemplate(factory);

        String queueName = "preservation-test";
        template.execute(channel -> {
            channel.queueDeclare(queueName, false, false, false, null);
            return null;
        });

        // Consumer 없이 3건 발행
        template.convertAndSend(queueName, "msg-1");
        template.convertAndSend(queueName, "msg-2");
        template.convertAndSend(queueName, "msg-3");

        // 나중에 Consumer가 연결
        Object msg1 = template.receiveAndConvert(queueName, 5000);
        Object msg2 = template.receiveAndConvert(queueName, 5000);
        Object msg3 = template.receiveAndConvert(queueName, 5000);

        // 3건 전부 수신 — Redis Pub/Sub이었다면 전부 유실됐을 것
        assertThat(msg1.toString()).isEqualTo("msg-1");
        assertThat(msg2.toString()).isEqualTo("msg-2");
        assertThat(msg3.toString()).isEqualTo("msg-3");

        factory.destroy();
    }

    @Test
    void Consumer가_다운된_동안_발행된_메시지를_재시작_후_수신한다() {

        CachingConnectionFactory factory = new CachingConnectionFactory(
                rabbit.getHost(), rabbit.getAmqpPort());
        RabbitTemplate template = new RabbitTemplate(factory);

        String queueName = "downtime-test";
        template.execute(channel -> {
            channel.queueDeclare(queueName, false, false, false, null);
            return null;
        });

        // Phase 1: 정상 소비
        template.convertAndSend(queueName, "msg-1");
        Object received1 = template.receiveAndConvert(queueName, 5000);
        assertThat(received1.toString()).isEqualTo("msg-1");

        // Phase 2: Consumer 다운 (아무도 안 읽음)
        template.convertAndSend(queueName, "msg-2");
        template.convertAndSend(queueName, "msg-3");

        // Phase 3: Consumer 재시작 → 다운 중 발행된 메시지 수신
        Object received2 = template.receiveAndConvert(queueName, 5000);
        Object received3 = template.receiveAndConvert(queueName, 5000);

        assertThat(received2.toString()).isEqualTo("msg-2");
        assertThat(received3.toString()).isEqualTo("msg-3");

        factory.destroy();
    }
}
