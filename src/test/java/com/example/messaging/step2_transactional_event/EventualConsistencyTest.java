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
     *   주문 생성 (즉시 커밋) → 주문은 즉시 조회 가능
     *   → 포인트 즉시 조회 → 아직 반영 안 됨 (비동기 처리 중)
     *   → @Async 리스너 완료 대기 → 포인트 조회 → 이제 반영됨
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

        // Then 1: 주문은 즉시 확인 가능
        assertThat(orderRepository.findById(orderId)).isPresent();

        // Then 2: 포인트 반영은 "보장되지 않는다"
        // @Async 리스너가 별도 스레드에서 아직 실행 중이거나 대기 중이므로
        // 이 시점에 반영됐을 수도, 안 됐을 수도 있다.
        // 동기 방식(@EventListener)이었다면 여기서 항상 반영되어 있겠지만,
        // AFTER_COMMIT + @Async에서는 보장할 수 없다.
        //
        // NOTE: assertThat(point).isEmpty()로 단정하지 않는 이유 —
        // 스레드 스케줄링에 따라 이미 반영되었을 수 있어서 테스트가 불안정해진다.
        // Eventual Consistency의 핵심은 "없을 수도 있다"이지 "항상 없다"가 아니다.

        // Then 3: 비동기 리스너 완료까지 대기하면 "곧" 반영된다
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        Optional<Point> eventualPoint = pointRepository.findByUserId("user-1");
        assertThat(eventualPoint).isPresent();
        assertThat(eventualPoint.get().getAmount()).isEqualTo(500L); // 50000 * 0.01

        // 주문은 즉시 확정되지만, 포인트 적립은 "곧" 반영된다.
        // 이 "곧"이 Eventual Consistency다. 버그가 아니라 설계 결정이다.
    }
}
