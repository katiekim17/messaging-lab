package com.example.messaging.step1_application_event;

import com.example.messaging.step1_application_event.domain.Order;
import com.example.messaging.step1_application_event.domain.OrderStatus;
import com.example.messaging.step1_application_event.domain.Point;
import com.example.messaging.step1_application_event.evented.EventedOrderService;
import com.example.messaging.step1_application_event.repository.OrderRepository;
import com.example.messaging.step1_application_event.repository.PointRepository;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.annotation.DirtiesContext;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplicationEventPublisher로 전환 후 의존성이 제거되는 것을 확인한다.
 */
@SpringBootTest(classes = Step1TestConfig.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApplicationEventDecouplingTest {

    @Autowired
    EventedOrderService orderService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PointRepository pointRepository;

    @Test
    void 이벤트_방식에서_OrderService는_EventPublisher에만_의존한다() {
        Constructor<?> constructor = EventedOrderService.class.getConstructors()[0];
        Class<?>[] paramTypes = constructor.getParameterTypes();

        // OrderRepository + ApplicationEventPublisher = 2개
        assertThat(paramTypes).hasSize(2);

        boolean hasEventPublisher = Arrays.stream(paramTypes)
                .anyMatch(ApplicationEventPublisher.class::isAssignableFrom);
        assertThat(hasEventPublisher).isTrue();

        // StockService, CouponService, PointService에 대한 의존이 없다
    }

    @Test
    void 이벤트_발행_후_리스너가_정상_처리하면_모든_데이터가_저장된다() {
        Long orderId = orderService.createOrder("user-1", 50_000L);

        // 주문 저장 확인
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);

        // 포인트 적립 확인 (PointEventListener가 처리)
        Point point = pointRepository.findByUserId("user-1").orElseThrow();
        assertThat(point.getAmount()).isEqualTo(500L); // 50000 * 0.01
    }

    @Test
    void 후속_로직_추가시_OrderService는_수정하지_않아도_된다() {
        // EventedOrderService가 의존하는 것은 딱 2개
        Constructor<?> constructor = EventedOrderService.class.getConstructors()[0];
        int dependencyCount = constructor.getParameterCount();

        // 리스너가 100개 추가되어도 이 숫자는 변하지 않는다
        assertThat(dependencyCount).isEqualTo(2);

        // 새 리스너를 추가하려면:
        // 1. @EventListener 붙인 새 클래스 생성
        // 2. OrderCreatedEvent를 받도록 메서드 작성
        // 3. EventedOrderService는 전혀 수정하지 않음!
    }
}
