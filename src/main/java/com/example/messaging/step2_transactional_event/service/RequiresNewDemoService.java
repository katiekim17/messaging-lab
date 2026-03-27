package com.example.messaging.step2_transactional_event.service;

import com.example.messaging.step2_transactional_event.domain.Order;
import com.example.messaging.step2_transactional_event.listener.FailingPointSaveService;
import com.example.messaging.step2_transactional_event.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW 예외 전파 시나리오를 시연하는 서비스.
 *
 * 핵심: REQUIRES_NEW는 새 TX를 열지만 JVM 콜 스택은 공유한다.
 * 자식 TX에서 던진 예외는 부모 TX 콜 스택으로 전파된다.
 * 부모가 catch하면 부모 TX는 살아남고, catch 안 하면 부모 TX도 롤백된다.
 */
@Service
public class RequiresNewDemoService {

    private final OrderRepository orderRepository;
    private final FailingPointSaveService failingPointSaveService;

    public RequiresNewDemoService(OrderRepository orderRepository,
                                  FailingPointSaveService failingPointSaveService) {
        this.orderRepository = orderRepository;
        this.failingPointSaveService = failingPointSaveService;
    }

    /**
     * 자식(REQUIRES_NEW)의 예외를 catch하지 않는다.
     * 예외가 부모 TX로 전파 → 부모 TX도 롤백.
     * 결과: 주문 0건, 포인트 0건.
     */
    @Transactional
    public void createWithoutCatch(String userId, long amount) {
        orderRepository.save(new Order(userId, amount));
        // 예외가 전파됨 → 부모 TX 롤백
        failingPointSaveService.saveAndFail(userId, amount);
    }

    /**
     * 자식(REQUIRES_NEW)의 예외를 catch한다.
     * 부모 TX는 예외를 모르므로 정상 커밋.
     * 결과: 주문 1건, 포인트 0건.
     */
    @Transactional
    public void createWithCatch(String userId, long amount) {
        orderRepository.save(new Order(userId, amount));
        try {
            failingPointSaveService.saveAndFail(userId, amount);
        } catch (RuntimeException e) {
            // 자식 TX는 이미 롤백됨. 부모 TX는 계속 진행.
        }
    }
}