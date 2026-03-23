package com.example.messaging.step0_command_vs_event.domain;

import java.util.UUID;

public class Coupon {

    private final String couponId;
    private final String userId;
    private final String couponType;

    public Coupon(String userId, String couponType) {
        this.couponId = UUID.randomUUID().toString();
        this.userId = userId;
        this.couponType = couponType;
    }

    public String getCouponId() {
        return couponId;
    }

    public String getUserId() {
        return userId;
    }

    public String getCouponType() {
        return couponType;
    }
}
