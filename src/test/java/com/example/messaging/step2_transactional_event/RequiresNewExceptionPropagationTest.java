package com.example.messaging.step2_transactional_event;

import com.example.messaging.step2_transactional_event.listener.AsyncTransactionalPointListener;
import com.example.messaging.step2_transactional_event.listener.FailingPointSaveService;
import com.example.messaging.step2_transactional_event.listener.TransactionalPointListener;
import com.example.messaging.step2_transactional_event.repository.OrderRepository;
import com.example.messaging.step2_transactional_event.repository.PointRepository;
import com.example.messaging.step2_transactional_event.service.OrderService;
import com.example.messaging.step2_transactional_event.service.RequiresNewDemoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQUIRES_NEW는 새 TX를 열지만 JVM 콜 스택을 공유한다.
 *
 * 자식(REQUIRES_NEW) TX가 예외를 던지면 그 예외는 부모 TX의 콜 스택으로 전파된다.
 * REQUIRES_NEW는 TX를 분리하지만 예외 전파까지 차단하지는 않는다.
 */
@SpringBootTest(classes = Step2TestConfig.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RequiresNewExceptionPropagationTest {

    @Autowired
    RequiresNewDemoService requiresNewDemoService;

    @Autowired
    OrderService orderService;

    @Autowired
    TransactionalPointListener transactionalPointListener;

    @Autowired
    AsyncTransactionalPointListener asyncPointListener;

    @Autowired
    FailingPointSaveService failingPointSaveService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PointRepository pointRepository;

    @AfterEach
    void tearDown() {
        transactionalPointListener.reset();
        asyncPointListener.reset();
        orderRepository.deleteAll();
        pointRepository.deleteAll();
    }

    /**
     * REQUIRES_NEW 자식 TX가 예외를 던지면,
     * catch하지 않을 경우 예외가 부모 TX로 전파되어 부모 TX도 롤백된다.
     *
     * 결과: 주문 0건(부모 롤백), 포인트 0건(자식 롤백)
     */
    @Test
    void REQUIRES_NEW_자식이_예외를_던지면_부모_TX도_롤백된다() {
        // 격리: AFTER_COMMIT 리스너들이 영향을 주지 않도록 비활성화
        transactionalPointListener.setEnabled(false);
        asyncPointListener.setEnabled(false);

        assertThatThrownBy(() -> requiresNewDemoService.createWithoutCatch("user-no-catch", 50_000L))
                .isInstanceOf(RuntimeException.class);

        // 자식 TX 롤백: 포인트 없음
        assertThat(pointRepository.findByUserId("user-no-catch")).isEmpty();
        // 예외가 부모 TX로 전파 → 부모 TX도 롤백: 주문 없음
        assertThat(orderRepository.findAll()).isEmpty();
    }

    /**
     * REQUIRES_NEW 자식 TX가 예외를 던지더라도,
     * 부모 TX가 catch하면 부모 TX는 정상 커밋된다.
     *
     * 결과: 주문 1건(부모 커밋), 포인트 0건(자식 롤백)
     */
    @Test
    void REQUIRES_NEW_자식_예외를_catch하면_부모_TX는_커밋된다() {
        // 격리: AFTER_COMMIT 리스너들이 영향을 주지 않도록 비활성화
        transactionalPointListener.setEnabled(false);
        asyncPointListener.setEnabled(false);

        requiresNewDemoService.createWithCatch("user-with-catch", 50_000L);

        // 자식 TX 롤백: 포인트 없음
        assertThat(pointRepository.findByUserId("user-with-catch")).isEmpty();
        // 부모 TX는 예외를 catch했으므로 커밋: 주문 있음
        assertThat(orderRepository.findAll()).hasSize(1);
    }

    /**
     * AFTER_COMMIT 리스너에서 REQUIRES_NEW로 실행하다가 예외가 발생해도
     * 주문 TX는 이미 커밋되었으므로 영향을 받지 않는다.
     *
     * Spring이 AFTER_COMMIT 리스너의 예외를 로깅하고 삼키므로
     * orderService.createOrder()는 정상적으로 반환된다.
     *
     * 결과: 주문 1건(이미 커밋), 포인트 0건(REQUIRES_NEW 롤백)
     */
    @Test
    void AFTER_COMMIT에서_REQUIRES_NEW가_예외를_던지면_주문은_유지되고_포인트만_롤백된다() {
        // 격리: asyncPointListener 비활성화 (별도 스레드에서 포인트 저장 방지)
        asyncPointListener.setEnabled(false);

        // AFTER_COMMIT 콜백에서 REQUIRES_NEW + 강제 실패
        transactionalPointListener.setCallback(
                () -> failingPointSaveService.saveAndFail("user-after-commit-fail", 50_000L));

        // Spring이 AFTER_COMMIT 리스너 예외를 로깅 후 삼킴 → 정상 반환
        orderService.createOrder("user-after-commit-fail", 50_000L);

        // 주문 TX는 AFTER_COMMIT 전에 이미 커밋됨 → 주문은 남아있다
        assertThat(orderRepository.findAll()).hasSize(1);

        // REQUIRES_NEW 포인트 TX는 예외로 롤백됨 → 포인트 없음
        // TransactionalPointListener 자체의 저장도 예외 전파로 실행 안 됨
        assertThat(pointRepository.findByUserId("user-after-commit-fail")).isEmpty();
    }
}