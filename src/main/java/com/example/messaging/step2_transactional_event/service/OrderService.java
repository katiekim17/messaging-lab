package com.example.messaging.step2_transactional_event.service;

import com.example.messaging.step2_transactional_event.domain.Order;
import com.example.messaging.step2_transactional_event.event.OrderCreatedEvent;
import com.example.messaging.step2_transactional_event.repository.OrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Long createOrder(String userId, long amount) {
        Order order = new Order(userId, amount);
        orderRepository.save(order);

        eventPublisher.publishEvent(new OrderCreatedEvent(
                order.getId(), userId, amount, Instant.now()));

        return order.getId();
    }

    @Transactional
    public Long createOrderThatWillFail(String userId, long amount) {
        Order order = new Order(userId, amount);
        orderRepository.save(order);

        eventPublisher.publishEvent(new OrderCreatedEvent(
                order.getId(), userId, amount, Instant.now()));

        // 커밋 전에 강제 예외 발생 (DB 제약조건 위반 시뮬레이션)
        throw new RuntimeException("주문 처리 중 오류 발생");
    }
}
