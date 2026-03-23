package com.example.messaging.step2_transactional_event;

import com.example.messaging.step2_transactional_event.domain.Point;
import com.example.messaging.step2_transactional_event.listener.AsyncTransactionalPointListener;
import com.example.messaging.step2_transactional_event.repository.OrderRepository;
import com.example.messaging.step2_transactional_event.repository.PointRepository;
import com.example.messaging.step2_transactional_event.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eventual Consistency를 체험한다.
 * AFTER_COMMIT + @Async를 선택한 순간, 즉시 일관성은 포기한 것이다.
 */
@SpringBootTest(classes = Step2TestConfig.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EventualConsistencyTest {

    @Autowired
    OrderService orderService;

    @Autowired
    AsyncTransactionalPointListener asyncPointListener;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PointRepository pointRepository;

    @AfterEach
    void tearDown() {
        asyncPointListener.reset();
        orderRepository.deleteAll();
        pointRepository.deleteAll();
    }

    /**
     * 흐름:
     *   주문 생성 (즉시 커밋) → 주문 조회 성공 → @Async 리스너 완료 대기
     *   → 포인트 조회 → "곧" 반영됨
     *
     * 증명: AFTER_COMMIT + @Async를 선택한 순간, 즉시 일관성은 포기한 것이다.
     *       이 시간차는 버그가 아니라 설계 결정(Eventual Consistency)이다.
     */
    @Test
    void 주문_직후_포인트를_조회하면_아직_반영되지_않았을_수_있다() throws InterruptedException {
        // Given: @Async + @TransactionalEventListener(AFTER_COMMIT)
        CountDownLatch latch = new CountDownLatch(1);
        asyncPointListener.setLatch(latch);

        // When: 주문 생성
        Long orderId = orderService.createOrder("user-1", 50_000L);

        // Then: 주문은 즉시 확인 가능
        assertThat(orderRepository.findById(orderId)).isPresent();

        // 비동기 리스너 완료까지 대기
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // "곧" 반영된다 — 이 "곧"이 Eventual Consistency다
        Optional<Point> point = pointRepository.findByUserId("user-1");
        assertThat(point).isPresent();
        assertThat(point.get().getAmount()).isEqualTo(500L); // 50000 * 0.01

        // 주문은 즉시 확정되지만, 포인트 적립은 "곧" 반영된다.
        // 이 시간차는 버그가 아니라 설계 결정이다.
    }
}
