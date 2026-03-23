package com.example.messaging.step2_transactional_event;

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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * @Async의 편리함과 실패 은닉 문제를 동시에 체험한다.
 */
@SpringBootTest(classes = Step2TestConfig.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AsyncEventTest {

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
     *   주문 생성 (메인 스레드) → 이벤트 발행 → @Async 리스너가 별도 스레드에서 실행
     *   → CountDownLatch로 완료 대기 → 스레드 이름 비교
     *
     * 증명: @Async 리스너는 발행자와 다른 스레드에서 실행되어 응답을 막지 않는다
     */
    @Test
    void Async_리스너는_별도_스레드에서_실행되어_응답이_빠르다() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        asyncPointListener.setLatch(latch);

        // When
        String callerThread = Thread.currentThread().getName();
        orderService.createOrder("user-1", 50_000L);

        // Then: 비동기 리스너 완료 대기
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // 다른 스레드에서 실행됨을 확인
        assertThat(asyncPointListener.getExecutedThread()).isNotEqualTo(callerThread);
    }

    /**
     * 흐름:
     *   리스너가 예외를 던지도록 설정 → 주문 생성 → @Async 리스너 예외 발생
     *   → 호출자에게는 예외가 전파되지 않음 → 주문은 성공, 포인트는 적립 안 됨
     *
     * 증명: @Async의 실패는 호출자에게 보이지 않는다. 이것이 Step 3이 필요한 이유다.
     */
    @Test
    void Async_리스너_예외는_호출자에게_전파되지_않는다_실패가_숨겨진다() throws InterruptedException {
        // Given: 리스너가 예외를 던지도록 설정
        asyncPointListener.setShouldFail(true);
        CountDownLatch latch = new CountDownLatch(1);
        asyncPointListener.setLatch(latch);

        // When: 주문 생성 — 예외가 발생하지 않는다!
        Long orderId = assertDoesNotThrow(
                () -> orderService.createOrder("user-1", 50_000L));

        // 비동기 리스너 완료 대기
        latch.await(5, TimeUnit.SECONDS);

        // Then: 주문은 성공
        assertThat(orderRepository.findById(orderId)).isPresent();

        // 포인트는 적립되지 않았다 — 그런데 아무도 모른다!
        assertThat(pointRepository.findByUserId("user-1")).isEmpty();

        // 이것이 @Async의 위험: 실패가 보이지 않는다
        // → Step 3에서 Event Store로 이 문제를 해결한다
    }
}
