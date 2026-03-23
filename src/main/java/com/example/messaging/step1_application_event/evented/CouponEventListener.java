package com.example.messaging.step1_application_event.evented;

import com.example.messaging.step1_application_event.event.OrderCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CouponEventListener {

    @EventListener
    public void handle(OrderCreatedEvent event) {
        // 쿠폰 발급 로직 (학습 목적으로 단순화)
    }
}
