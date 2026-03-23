package com.example.messaging.step2_transactional_event;

import com.example.messaging.step2_transactional_event.listener.SyncPointListener;
import com.example.messaging.step2_transactional_event.repository.OrderRepository;
import com.example.messaging.step2_transactional_event.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @EventListener의 타이밍 위험을 체험한다.
 * 커밋 전에 실행되므로 롤백 시 부수효과가 되돌려지지 않는다.
 */
@SpringBootTest(classes = Step2TestConfig.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EventListenerTimingTest {

    @Autowired
    OrderService orderService;

    @Autowired
    SyncPointListener syncPointListener;

    @Autowired
    OrderRepository orderRepository;

    @AfterEach
    void tearDown() {
        syncPointListener.reset();
    }

    /**
     * 흐름:
     *   @EventListener 내부에서 외부 API 호출 → 트랜잭션 도중 예외 → 롤백
     *   → 외부 API 호출은 이미 실행되었으므로 되돌릴 수 없다
     *
     * 증명: @EventListener는 커밋 전에 실행되므로, 롤백되어도 부수효과는 남는다
     */
    @Test
    void EventListener는_커밋_전에_실행되어_롤백시_부수효과가_되돌려지지_않는다() {
        // Given: @EventListener가 외부 API를 호출하는 상황 시뮬레이션
        syncPointListener.setCallback(() -> {
            // 외부 API 호출 (HTTP, 메일 발송 등)을 시뮬레이션
        });

        // When: 트랜잭션 도중 강제 예외로 롤백
        assertThatThrownBy(() -> orderService.createOrderThatWillFail("user-1", 50_000L))
                .isInstanceOf(RuntimeException.class);

        // Then: @EventListener는 이미 실행되었다! (커밋 전이니까)
        assertThat(syncPointListener.isExternalApiCalled()).isTrue();

        // 주문은 롤백되었지만, 외부 API 호출은 되돌릴 수 없다
        assertThat(orderRepository.findAll()).isEmpty();
    }
}
