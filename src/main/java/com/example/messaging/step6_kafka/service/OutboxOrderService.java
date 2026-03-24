package com.example.messaging.step6_kafka.service;

import com.example.messaging.step6_kafka.domain.Order;
import com.example.messaging.step6_kafka.domain.OutboxEvent;
import com.example.messaging.step6_kafka.repository.OrderRepository;
import com.example.messaging.step6_kafka.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxOrderService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxOrderService(OrderRepository orderRepository,
                              OutboxEventRepository outboxEventRepository,
                              ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long createOrder(String productName, long amount) {
        Order order = new Order(productName, amount);
        orderRepository.save(order);

        String payload = serializePayload(order);

        outboxEventRepository.save(new OutboxEvent(
                "ORDER_CREATED", order.getId().toString(), payload));

        return order.getId();
    }

    private String serializePayload(Order order) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("orderId", order.getId());
            node.put("productName", order.getProductName());
            node.put("amount", order.getAmount());
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("이벤트 payload 직렬화 실패", e);
        }
    }
}
