 package com.example.messaging.step2_transactional_event.listener;

import com.example.messaging.step2_transactional_event.domain.Point;
import com.example.messaging.step2_transactional_event.event.OrderCreatedEvent;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-invocation 함정: 같은 클래스 내부에서 this.method()를 호출하면
 * Spring AOP 프록시를 거치지 않아 @Transactional(REQUIRES_NEW)이 무시된다.
 *
 * AFTER_COMMIT 시점에 기존 TX는 이미 커밋된 상태.
 * this.savePoint()로 REQUIRES_NEW를 시도해도 새 TX가 열리지 않으므로
 * EntityManager.flush() 호출 시 TransactionRequiredException이 발생한다.
 */
@Component
public class SelfInvocationListener {

    private final EntityManager entityManager;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private final AtomicReference<Exception> capturedException = new AtomicReference<>();

    public SelfInvocationListener(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void enable() {
        this.enabled.set(true);
    }

    public boolean isExecuted() {
        return executed.get();
    }

    public Exception getCapturedException() {
        return capturedException.get();
    }

    public void reset() {
        enabled.set(false);
        executed.set(false);
        capturedException.set(null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCreatedEvent event) {
        if (!enabled.get()) return;
        executed.set(true);
        // this.savePoint() 호출 → Spring AOP 프록시를 거치지 않는다
        // @Transactional(REQUIRES_NEW)이 무시되어 새 TX가 열리지 않는다
        savePoint(event.userId(), (long) (event.amount() * 0.01));
    }

    // 같은 클래스 안에서 this.savePoint()로 호출되면 이 애노테이션은 무시된다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePoint(String userId, long pointAmount) {
        try {
            entityManager.persist(new Point(userId, pointAmount));
            entityManager.flush(); // TX 없음 → TransactionRequiredException 발생
        } catch (Exception e) {
            capturedException.set(e);
        }
    }
}