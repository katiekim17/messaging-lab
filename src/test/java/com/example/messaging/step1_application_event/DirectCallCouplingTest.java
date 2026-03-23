package com.example.messaging.step1_application_event;

import com.example.messaging.step1_application_event.direct.DirectCouponService;
import com.example.messaging.step1_application_event.direct.DirectOrderService;
import com.example.messaging.step1_application_event.direct.DirectPointService;
import com.example.messaging.step1_application_event.direct.DirectStockService;
import com.example.messaging.step1_application_event.domain.Order;
import com.example.messaging.step1_application_event.domain.OrderStatus;
import com.example.messaging.step1_application_event.repository.OrderRepository;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 직접 호출 방식의 결합도 문제를 체험한다.
 */
@SpringBootTest(classes = Step1TestConfig.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DirectCallCouplingTest {

    @Autowired
    DirectOrderService orderService;

    @Autowired
    DirectPointService pointService;

    @Autowired
    OrderRepository orderRepository;

    @Test
    void 직접_호출_방식에서_OrderService는_모든_후속_서비스에_의존한다() {
        // DirectOrderService의 생성자 파라미터 = 의존하는 서비스 목록
        Constructor<?> constructor = DirectOrderService.class.getConstructors()[0];
        Class<?>[] paramTypes = constructor.getParameterTypes();

        // OrderRepository + StockService + CouponService + PointService = 4개
        assertThat(paramTypes).hasSize(4);
        assertThat(paramTypes).contains(
                DirectStockService.class,
                DirectCouponService.class,
                DirectPointService.class
        );
        // 후속 로직이 추가될 때마다 이 숫자는 계속 늘어난다
    }

    @Test
    void 직접_호출_방식에서_후속_처리_실패시_주문도_롤백된다() {
        // Given: PointService가 실패하도록 설정
        pointService.setShouldFail(true);

        // When & Then: 포인트 적립 실패 → 주문까지 롤백
        assertThatThrownBy(() -> orderService.createOrder("user-1", 50_000L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("포인트 적립 실패");

        // 주문 자체도 저장되지 않음 (같은 트랜잭션이므로 롤백)
        assertThat(orderRepository.findAll()).isEmpty();
    }

    @Test
    void 직접_호출_방식에서_모든_후속_처리가_성공하면_주문이_완료된다() {
        Long orderId = orderService.createOrder("user-1", 50_000L);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }
}
