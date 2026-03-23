package com.example.messaging.step5_kafka.service;

import com.example.messaging.step5_kafka.domain.Order;
import com.example.messaging.step5_kafka.domain.OutboxEvent;
import com.example.messaging.step5_kafka.repository.OrderRepository;
import com.example.messaging.step5_kafka.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxOrderService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;

    public OutboxOrderService(OrderRepository orderRepository,
                              OutboxEventRepository outboxEventRepository) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public Long createOrder(String productName, long amount) {
        Order order = new Order(productName, amount);
        orderRepository.save(order);

        String payload = String.format(
                "{\"orderId\":%d,\"productName\":\"%s\",\"amount\":%d}",
                order.getId(), productName, amount);

        outboxEventRepository.save(new OutboxEvent(
                "ORDER_CREATED", order.getId().toString(), payload));

        return order.getId();
    }
}
