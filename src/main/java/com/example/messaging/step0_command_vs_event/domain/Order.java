package com.example.messaging.step0_command_vs_event.domain;

import java.util.UUID;

public class Order {

    private final String orderId;
    private final String userId;
    private final long amount;

    private Order(String orderId, String userId, long amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
    }

    public static Order create(String userId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("주문 금액은 0보다 커야 합니다");
        }
        return new Order(UUID.randomUUID().toString(), userId, amount);
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmount() {
        return amount;
    }
}
