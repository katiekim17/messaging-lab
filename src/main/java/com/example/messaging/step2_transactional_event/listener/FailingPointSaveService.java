package com.example.messaging.step2_transactional_event.listener;

import com.example.messaging.step2_transactional_event.domain.Point;
import com.example.messaging.step2_transactional_event.repository.PointRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW 예외 전파 실험용 빈.
 *
 * saveAndFail()은 포인트를 저장한 뒤 강제로 예외를 던진다.
 * REQUIRES_NEW로 열린 자체 TX는 롤백되고,
 * 호출한 쪽(부모 TX)으로 예외가 전파된다.
 *
 * 부모 TX가 이 예외를 catch하느냐 여부에 따라 부모 TX의 운명이 갈린다.
 */
@Component
public class FailingPointSaveService {

    private final PointRepository pointRepository;

    public FailingPointSaveService(PointRepository pointRepository) {
        this.pointRepository = pointRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAndFail(String userId, long amount) {
        long pointAmount = (long) (amount * 0.01);
        pointRepository.save(new Point(userId, pointAmount));
        throw new RuntimeException("포인트 저장 후 강제 실패 (REQUIRES_NEW)");
    }
}