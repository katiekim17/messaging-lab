package com.example.messaging.step1_application_event.evented;

import com.example.messaging.step1_application_event.domain.Order;
import com.example.messaging.step1_application_event.event.OrderCreatedEvent;
import com.example.messaging.step1_application_event.repository.OrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 이벤트 방식: ApplicationEventPublisher에만 의존한다.
 * 후속 로직이 추가되어도 이 클래스는 수정할 필요가 없다.
 */
@Service
public class EventedOrderService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EventedOrderService(OrderRepository orderRepository,
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
}
