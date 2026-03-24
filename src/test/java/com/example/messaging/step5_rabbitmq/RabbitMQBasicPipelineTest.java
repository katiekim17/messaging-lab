package com.example.messaging.step5_rabbitmq;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RabbitMQ Producer → Consumer 기본 파이프라인을 확인한다.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RabbitMQBasicPipelineTest {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Test
    void Producer가_보낸_메시지를_Consumer가_수신한다() {

        CachingConnectionFactory factory = new CachingConnectionFactory(
                rabbit.getHost(), rabbit.getAmqpPort());
        RabbitTemplate template = new RabbitTemplate(factory);

        String queueName = "order-events";
        template.execute(channel -> {
            channel.queueDeclare(queueName, false, false, false, null);
            return null;
        });

        // Producer: 메시지 발행
        template.convertAndSend(queueName, "주문이 생성되었다");

        // Consumer: 메시지 수신
        Object received = template.receiveAndConvert(queueName, 5000);

        assertThat(received).isNotNull();
        assertThat(received.toString()).isEqualTo("주문이 생성되었다");

        factory.destroy();
    }
}
