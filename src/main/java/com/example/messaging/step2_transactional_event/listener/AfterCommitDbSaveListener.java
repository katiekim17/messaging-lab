package com.example.messaging.step2_transactional_event.listener;

import com.example.messaging.step2_transactional_event.domain.Point;
import com.example.messaging.step2_transactional_event.event.OrderCreatedEvent;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AFTER_COMMIT에서 DB 저장 시, REQUIRES_NEW로 해결하는 방법을 보여주는 리스너.
 *
 * 핵심: @Transactional(REQUIRES_NEW) 메서드를 같은 클래스에 두면
 * this.method() 호출이 되어 Spring AOP 프록시를 거치지 않는다.
 * 반드시 별도 빈(PointSaveService)으로 분리해야 한다.
 *
 * enabled=false 시 이벤트를 무시한다 (다른 테스트와의 격리).
 */
@Component
public class AfterCommitDbSaveListener {

    private final PointSaveService pointSaveService;
    private final EntityManager entityManager;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean useRequiresNew = new AtomicBoolean(false);
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private final AtomicBoolean txActiveInHandler = new AtomicBoolean(false);
    private final AtomicReference<Exception> capturedException = new AtomicReference<>();

    public AfterCommitDbSaveListener(PointSaveService pointSaveService, EntityManager entityManager) {
        this.pointSaveService = pointSaveService;
        this.entityManager = entityManager;
    }

    public void enable() {
        this.enabled.set(true);
    }

    public void setUseRequiresNew(boolean useNew) {
        this.useRequiresNew.set(useNew);
    }

    public boolean isExecuted() {
        return executed.get();
    }

    public boolean isTxActiveInHandler() {
        return txActiveInHandler.get();
    }

    public Exception getCapturedException() {
        return capturedException.get();
    }

    public void reset() {
        enabled.set(false);
        useRequiresNew.set(false);
        executed.set(false);
        txActiveInHandler.set(false);
        capturedException.set(null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCreatedEvent event) {
        if (!enabled.get()) return;
        executed.set(true);
        txActiveInHandler.set(TransactionSynchronizationManager.isActualTransactionActive());

        if (useRequiresNew.get()) {
            // 별도 빈을 통해 호출 → Spring 프록시가 REQUIRES_NEW를 적용한다
            pointSaveService.savePoint(event.userId(), event.amount());
        } else {
            // EntityManager 직접 사용 → TX가 없으므로 TransactionRequiredException 발생
            // persist()는 1차 캐시에만 올리므로 예외가 안 남.
            // flush()가 실제로 DB에 쓰려 할 때 TX 없음을 확인하고 예외를 던진다.
            try {
                entityManager.persist(new Point(event.userId(), (long) (event.amount() * 0.01)));
                entityManager.flush();
            } catch (Exception e) {
                capturedException.set(e);
            }
        }
    }
}
