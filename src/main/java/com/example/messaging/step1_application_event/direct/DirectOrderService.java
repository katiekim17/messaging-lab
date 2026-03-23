package com.example.messaging.step1_application_event.direct;

import com.example.messaging.step1_application_event.domain.Order;
import com.example.messaging.step1_application_event.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직접 호출 방식: 모든 후속 서비스에 의존한다.
 * 후속 로직이 추가될 때마다 이 클래스의 의존성과 코드가 수정되어야 한다.
 */
@Service
public class DirectOrderService {

    private final OrderRepository orderRepository;
    private final DirectStockService stockService;
    private final DirectCouponService couponService;
    private final DirectPointService pointService;

    public DirectOrderService(OrderRepository orderRepository,
                              DirectStockService stockService,
                              DirectCouponService couponService,
                              DirectPointService pointService) {
        this.orderRepository = orderRepository;
        this.stockService = stockService;
        this.couponService = couponService;
        this.pointService = pointService;
    }

    @Transactional
    public Long createOrder(String userId, long amount) {
        Order order = new Order(userId, amount);
        orderRepository.save(order);

        // 후속 처리 — 각 서비스를 직접 호출
        stockService.deductStock(order.getId());
        couponService.issueCoupon(userId);
        pointService.addPoint(userId, amount);

        return order.getId();
    }
}
