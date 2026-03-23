package com.example.messaging.step0_command_vs_event.command;

/**
 * "쿠폰을 발급해라" — 아직 일어나지 않은 일. 재고가 없으면 실패한다.
 */
public record IssueCouponCommand(String userId, String couponType) {
}
