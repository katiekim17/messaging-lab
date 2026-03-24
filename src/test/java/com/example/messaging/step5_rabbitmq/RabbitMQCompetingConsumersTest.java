package com.example.messaging.step5_rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 큐에 Consumer가 여러 개 붙으면 메시지를 나눠 가진다 (Competing Consumers).
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RabbitMQCompetingConsumersTest {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Test
    void 같은_큐의_Consumer_2개가_메시지를_나눠_처리한다() throws Exception {

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbit.getHost());
        factory.setPort(rabbit.getAmqpPort());

        String queueName = "competing-test";

        try (Connection conn = factory.newConnection()) {
            // 큐 선언 + 메시지 4건 발행
            try (Channel prodChannel = conn.createChannel()) {
                prodChannel.queueDeclare(queueName, false, false, false, null);
                for (int i = 1; i <= 4; i++) {
                    prodChannel.basicPublish("", queueName, null,
                            ("msg-" + i).getBytes(StandardCharsets.UTF_8));
                }
            }

            // 잠시 대기 (메시지가 큐에 도착할 때까지)
            Thread.sleep(500);

            // Consumer 1: 큐에서 하나씩 읽기
            List<String> consumer1Messages = new ArrayList<>();
            List<String> consumer2Messages = new ArrayList<>();

            try (Channel ch1 = conn.createChannel(); Channel ch2 = conn.createChannel()) {
                // prefetch=1로 한 번에 하나씩만 가져가게 설정
                ch1.basicQos(1);
                ch2.basicQos(1);

                // 4건을 두 Consumer가 번갈아 가져감
                for (int i = 0; i < 4; i++) {
                    GetResponse resp;
                    if (i % 2 == 0) {
                        resp = ch1.basicGet(queueName, true);
                        if (resp != null) consumer1Messages.add(new String(resp.getBody(), StandardCharsets.UTF_8));
                    } else {
                        resp = ch2.basicGet(queueName, true);
                        if (resp != null) consumer2Messages.add(new String(resp.getBody(), StandardCharsets.UTF_8));
                    }
                }
            }

            // 두 Consumer가 합쳐서 4건을 처리
            int total = consumer1Messages.size() + consumer2Messages.size();
            assertThat(total).isEqualTo(4);

            // 각 Consumer가 최소 1건 이상 처리
            assertThat(consumer1Messages).isNotEmpty();
            assertThat(consumer2Messages).isNotEmpty();
        }
    }
}
