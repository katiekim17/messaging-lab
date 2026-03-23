package com.example.messaging.step0_command_vs_event.handler;

import com.example.messaging.step0_command_vs_event.command.IssueCouponCommand;
import com.example.messaging.step0_command_vs_event.domain.Coupon;

import java.util.HashMap;
import java.util.Map;

public class CouponCommandHandler {

    private final Map<String, Integer> stock = new HashMap<>();

    public CouponCommandHandler() {
        stock.put("WELCOME", 10);
    }

    public void setStock(String couponType, int quantity) {
        stock.put(couponType, quantity);
    }

    public Coupon handle(IssueCouponCommand command) {
        int remaining = stock.getOrDefault(command.couponType(), 0);
        if (remaining <= 0) {
            throw new IllegalStateException("재고 소진: " + command.couponType());
        }
        stock.put(command.couponType(), remaining - 1);
        return new Coupon(command.userId(), command.couponType());
    }
}
