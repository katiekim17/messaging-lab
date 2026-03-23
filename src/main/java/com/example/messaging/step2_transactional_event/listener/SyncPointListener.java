package com.example.messaging.step2_transactional_event.listener;

import com.example.messaging.step2_transactional_event.event.OrderCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @EventListener: 커밋 전에 동기적으로 실행된다.
 * 롤백 시 외부 API 호출 같은 부수효과가 되돌릴 수 없는 문제가 있다.
 */
@Component
public class SyncPointListener {

    private final AtomicBoolean externalApiCalled = new AtomicBoolean(false);
    private Runnable callback;

    public void setCallback(Runnable callback) {
        this.callback = callback;
    }

    public boolean isExternalApiCalled() {
        return externalApiCalled.get();
    }

    public void reset() {
        externalApiCalled.set(false);
        callback = null;
    }

    @EventListener
    public void handle(OrderCreatedEvent event) {
        externalApiCalled.set(true);
        if (callback != null) {
            callback.run();
        }
    }
}
