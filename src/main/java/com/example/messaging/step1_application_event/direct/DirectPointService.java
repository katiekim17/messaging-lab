package com.example.messaging.step1_application_event.direct;

import com.example.messaging.step1_application_event.domain.Point;
import com.example.messaging.step1_application_event.repository.PointRepository;
import org.springframework.stereotype.Service;

@Service
public class DirectPointService {

    private final PointRepository pointRepository;
    private boolean shouldFail = false;

    public DirectPointService(PointRepository pointRepository) {
        this.pointRepository = pointRepository;
    }

    public void setShouldFail(boolean shouldFail) {
        this.shouldFail = shouldFail;
    }

    public void addPoint(String userId, long amount) {
        if (shouldFail) {
            throw new RuntimeException("포인트 적립 실패");
        }
        long pointAmount = (long) (amount * 0.01);
        pointRepository.save(new Point(userId, pointAmount));
    }
}
