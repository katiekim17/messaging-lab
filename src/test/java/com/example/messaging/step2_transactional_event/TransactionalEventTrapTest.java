package com.example.messaging.step2_transactional_event;

import com.example.messaging.step2_transactional_event.listener.AfterCommitDbSaveListener;
import com.example.messaging.step2_transactional_event.listener.AsyncTransactionalPointListener;
import com.example.messaging.step2_transactional_event.listener.SelfInvocationListener;
import com.example.messaging.step2_transactional_event.listener.TransactionalPointListener;
import com.example.messaging.step2_transactional_event.repository.OrderRepository;
import com.example.messaging.step2_transactional_event.repository.PointRepository;
import com.example.messaging.step2_transactional_event.service.NonTransactionalOrderService;
import com.example.messaging.step2_transactional_event.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.TransactionRequiredException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @TransactionalEventListener + @Async 조합에서 밟기 쉬운 함정 4가지.
 */
@SpringBootTest(classes = Step2TestConfig.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TransactionalEventTrapTest {

    @Autowired
    OrderService orderService;

    @Autowired
    NonTransactionalOrderService nonTxOrderService;

    @Autowired
    TransactionalPointListener transactionalPointListener;

    @Autowired
    AsyncTransactionalPointListener asyncPointListener;

    @Autowired
    AfterCommitDbSaveListener afterCommitDbSaveListener;

    @Autowired
    SelfInvocationListener selfInvocationListener;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PointRepository pointRepository;

    @Autowired
    ApplicationContext applicationContext;

    @AfterEach
    void tearDown() {
        transactionalPointListener.reset();
        asyncPointListener.reset();
        afterCommitDbSaveListener.reset();
        selfInvocationListener.reset();
        orderRepository.deleteAll();
        pointRepository.deleteAll();
    }

    /**
     * 함정 1: @Transactional이 없으면 @TransactionalEventListener가 불리지 않는다.
     * 트랜잭션의 커밋/롤백을 감지해서 실행되는데, 트랜잭션 자체가 없으면 감지할 게 없다.
     */
    @Test
    void 트랜잭션_없이_이벤트를_발행하면_TransactionalEventListener가_불리지_않는다() {
        // When: @Transactional이 없는 서비스에서 이벤트 발행
        nonTxOrderService.createOrderWithoutTx("user-1", 50_000L);

        // Then: @TransactionalEventListener는 실행되지 않는다!
        assertThat(transactionalPointListener.isExecuted()).isFalse();

        // 주문은 저장됨 (auto-commit), 하지만 리스너는 안 불림
        assertThat(orderRepository.findAll()).isNotEmpty();
    }

    /**
     * 함정 2: @EnableAsync 없이 @Async를 달면 동기로 실행된다.
     * Spring이 @Async를 처리하려면 @EnableAsync가 필요하다.
     * 없으면 @Async가 조용히 무시되어 호출자 스레드에서 동기로 실행된다.
     * 에러도 경고도 없다.
     */
    @Test
    void EnableAsync가_있어야_Async_리스너가_별도_스레드에서_실행된다() throws InterruptedException {
        // @EnableAsync가 활성화되어 있으므로 @Async 리스너가 별도 스레드에서 실행된다.
        // @EnableAsync를 제거하면 같은 스레드에서 동기적으로 실행되며, 아래 assertion이 실패한다.
        CountDownLatch latch = new CountDownLatch(1);
        asyncPointListener.setLatch(latch);

        String callerThread = Thread.currentThread().getName();
        orderService.createOrder("user-async-check", 50_000L);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // @EnableAsync 덕분에 다른 스레드에서 실행됨
        assertThat(asyncPointListener.getExecutedThread()).isNotEqualTo(callerThread);
        // @EnableAsync를 제거하면 이 assertion이 실패한다 (같은 스레드에서 실행)
    }

    /**
     * 함정 3-1: AFTER_COMMIT 시점에 기존 TX는 이미 커밋되었다.
     *
     * REQUIRES_NEW 없이는 안전한 DB 저장이 보장되지 않는다.
     * Spring Data JPA의 save()는 자체 @Transactional로 우연히 동작할 수 있지만,
     * EntityManager를 직접 사용하면 TransactionRequiredException이 발생한다.
     * 의도한 TX 경계가 아니므로 REQUIRES_NEW를 사용해야 한다.
     */
    @Test
    void AFTER_COMMIT_리스너에서_DB_저장하면_TransactionRequiredException_발생() {
        // Given: 다른 포인트 저장 리스너 비활성화 (격리)
        transactionalPointListener.setEnabled(false);
        asyncPointListener.setEnabled(false);

        // AFTER_COMMIT 리스너 활성화 (REQUIRES_NEW 미사용)
        afterCommitDbSaveListener.enable();
        afterCommitDbSaveListener.setUseRequiresNew(false);

        // When
        orderService.createOrder("user-no-tx", 50_000L);

        // Then: 핸들러는 실행됐지만 EntityManager.persist() 호출 즉시 예외 발생
        assertThat(afterCommitDbSaveListener.isExecuted()).isTrue();
        assertThat(afterCommitDbSaveListener.getCapturedException())
                .isInstanceOf(TransactionRequiredException.class);
        assertThat(pointRepository.findByUserId("user-no-tx")).isEmpty();
    }

    /**
     * 함정 3-2: AFTER_COMMIT에서 DB 저장은 별도 빈의 REQUIRES_NEW로 새 TX를 열어야 안전하다.
     *
     * AFTER_COMMIT 시점에 기존 TX는 이미 커밋되었다.
     * 같은 클래스 내부에서 this.method()를 호출하면 Spring AOP 프록시를 거치지 않아
     * @Transactional(REQUIRES_NEW)이 무시된다. 반드시 별도 빈으로 분리해야 한다.
     */
    @Test
    void AFTER_COMMIT_리스너에서_별도_빈의_REQUIRES_NEW로_새_TX를_열면_DB_저장_가능() {
        // Given: 다른 포인트 저장 리스너 비활성화 (격리)
        transactionalPointListener.setEnabled(false);
        asyncPointListener.setEnabled(false);

        // 별도 빈(PointSaveService)을 통한 REQUIRES_NEW 사용
        afterCommitDbSaveListener.enable();
        afterCommitDbSaveListener.setUseRequiresNew(true);

        // When
        orderService.createOrder("user-trap-new", 50_000L);

        // Then: 별도 빈의 REQUIRES_NEW로 새 TX에서 저장 성공
        assertThat(afterCommitDbSaveListener.isExecuted()).isTrue();
        assertThat(pointRepository.findByUserId("user-trap-new")).isPresent();
    }

    /**
     * 확인: AFTER_COMMIT 시점의 실제 TX 상태.
     *
     * AbstractPlatformTransactionManager.commit() 실행 순서:
     *   1. doCommit()               ← 실제 DB commit
     *   2. triggerAfterCommit()     ← AFTER_COMMIT 리스너 실행 ← 여기
     *   3. triggerAfterCompletion()
     *   4. cleanupAfterCompletion() ← 여기서 isActualTransactionActive = false 로 바뀜
     *
     * Spring의 isActualTransactionActive()는 AFTER_COMMIT 시점에 아직 true다.
     * cleanupAfterCompletion()이 아직 실행되지 않았기 때문이다.
     *
     * 그럼에도 EntityManager.persist()가 TransactionRequiredException을 던지는 이유:
     * Spring TX 플래그와 JPA EntityManager 세션 상태는 별개다.
     * JPA 세션은 이미 커밋 완료 상태이므로 persist()를 거부한다.
     */
    @Test
    void AFTER_COMMIT_시점에_Spring_TX_플래그는_아직_true지만_JPA_세션은_이미_커밋됐다() {
        // Given
        transactionalPointListener.setEnabled(false);
        asyncPointListener.setEnabled(false);
        afterCommitDbSaveListener.enable();
        afterCommitDbSaveListener.setUseRequiresNew(false);

        // When
        orderService.createOrder("user-tx-state-check", 50_000L);

        // Then
        assertThat(afterCommitDbSaveListener.isExecuted()).isTrue();
        // Spring TX 플래그: cleanupAfterCompletion() 전이라 아직 true
        assertThat(afterCommitDbSaveListener.isTxActiveInHandler()).isTrue();
        // JPA EntityManager: 이미 커밋 완료 → flush() 시 TransactionRequiredException 발생
        Exception ex = afterCommitDbSaveListener.getCapturedException();
        System.out.println("\n=== AFTER_COMMIT에서 flush() 호출 시 발생한 예외 ===");
        System.out.println(ex.getClass().getName() + ": " + ex.getMessage());
        System.out.println("===================================================\n");
        assertThat(ex).isInstanceOf(TransactionRequiredException.class);
    }

    /**
     * 함정 4: Spring Boot는 기본으로 ThreadPoolTaskExecutor를 등록하지만,
     * corePoolSize=8, maxPoolSize=Integer.MAX_VALUE, queueCapacity=Integer.MAX_VALUE가 기본값이다.
     * 프로덕션에서는 반드시 적절한 풀 사이즈와 큐 용량을 설정해야 한다.
     */
    @Test
    void Async_기본_스레드풀은_큐_제한이_없어_프로덕션에서_튜닝이_필요하다() {
        // Spring Boot가 자동 등록하는 applicationTaskExecutor 확인
        ThreadPoolTaskExecutor executor = applicationContext
                .getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);

        // 기본값: 제한이 사실상 없다 → 프로덕션에서 OOM 위험
        assertThat(executor.getCorePoolSize()).isEqualTo(8);
        assertThat(executor.getMaxPoolSize()).isEqualTo(Integer.MAX_VALUE);
        assertThat(executor.getQueueCapacity()).isEqualTo(Integer.MAX_VALUE);

        // 프로덕션에서는 서비스 특성에 맞게 제한해야 한다:
        // executor.setCorePoolSize(5);
        // executor.setMaxPoolSize(10);
        // executor.setQueueCapacity(100);
    }

    /**
     * 함정 3-3 (Self-invocation): 같은 클래스 안에서 this.savePoint()를 호출하면
     * Spring AOP 프록시를 거치지 않아 @Transactional(REQUIRES_NEW)이 무시된다.
     *
     * AFTER_COMMIT 시점에 기존 TX는 이미 커밋 완료.
     * this.savePoint()가 REQUIRES_NEW로 새 TX를 열어야 하지만,
     * 프록시 없이 직접 호출되므로 새 TX가 생기지 않는다.
     * → EntityManager.flush()가 TransactionRequiredException을 던진다.
     *
     * 해결: REQUIRES_NEW 메서드를 반드시 별도 빈(PointSaveService)으로 분리해야 한다.
     */
    @Test
    void Self_invocation으로_REQUIRES_NEW를_호출하면_프록시를_거치지_않아_새_TX가_열리지_않는다() {
        // Given: 다른 리스너 비활성화 (격리)
        transactionalPointListener.setEnabled(false);
        asyncPointListener.setEnabled(false);

        selfInvocationListener.enable();

        // When
        orderService.createOrder("user-self-invocation", 50_000L);

        // Then: 리스너는 실행됐지만 this.savePoint() 호출로 REQUIRES_NEW가 무시됨
        assertThat(selfInvocationListener.isExecuted()).isTrue();
        // 새 TX가 열리지 않았으므로 flush()에서 TransactionRequiredException 발생
        assertThat(selfInvocationListener.getCapturedException())
                .isInstanceOf(TransactionRequiredException.class);
        // 포인트는 저장되지 않음
        assertThat(pointRepository.findByUserId("user-self-invocation")).isEmpty();
    }
}
