# Step 0 & Step 1 체크리스트

---

## Step 0 — Command vs Event

### 개념 이해

- [ ] Command는 "~해라"다 — 아직 일어나지 않은 의도이며 실패할 수 있다
- [ ] Event는 "~됐다"다 — 이미 확정된 사실이며 발행 자체는 항상 성공한다
- [ ] Command는 발신자가 수신자를 특정한다 (1:1)
- [ ] Event는 발신자가 수신자를 모른다 (1:N)
- [ ] Command 실패 시 발신자가 직접 결과를 받아서 대응해야 한다
- [ ] Event 처리 실패 시 발행자는 몰라도 된다 — 리스너의 책임이다

### 타임스탬프

- [ ] Command의 `requestedAt`은 "언제 요청했는가"다 — 아직 실행되지 않은 의도의 시각
- [ ] Event의 `occurredAt`은 "언제 확정됐는가"다 — 이미 일어난 사실의 시각
- [ ] 둘 다 타임스탬프가 있지만 의미가 다르다
- [ ] `occurredAt`은 이벤트 순서 판단의 기준이 된다 (Kafka 재처리, Event Store 정렬)
- [ ] `requestedAt`은 타임아웃, 감사 추적 용도다

### Command/Event 구분 기준

- [ ] "이 작업이 실패하면 주문도 실패해야 하는가?"가 판단 기준이다
- [ ] 재고 차감 실패 → 주문 실패해야 함 → Command
- [ ] 쿠폰 사용 실패 → 결제 금액이 바뀌므로 주문 실패해야 함 → Command
- [ ] 결제 요청 실패 → 주문 실패해야 함 → Command
- [ ] 포인트 적립 실패 → 주문은 유지해야 함 → Event
- [ ] 알림 발송 실패 → 주문은 유지해야 함 → Event
- [ ] 비즈니스 의존도가 Command/Event를 결정한다 — 기술이 결정하지 않는다

### Before Commit vs After Commit

- [ ] 트랜잭션 커밋 전에 Event를 발행하면 DB 롤백 후에도 Event가 나간 상황이 생길 수 있다
- [ ] Event는 커밋이 확정된 후에 발행하는 것이 원칙이다
- [ ] 판단 기준: "이 로직이 트랜잭션 성공에 의존해야 하는가?"
  - 의존해야 한다 → Before commit (같은 트랜잭션)
  - 의존 안 해도 된다 → After commit (Event 발행)
- [ ] 코드 분리 방법(`@Aspect`, `@Transactional`)보다 이 판단이 먼저다

### 실패 대응

- [ ] Command 실패 → 전체 롤백
- [ ] Event 처리 실패 → 주문 유지, 재시도로 해결
- [ ] "나중에 다시 시도"를 안전하게 구현하는 것이 Outbox 패턴이다 (Step 6)
- [ ] 중복 수신 문제를 처리하는 것이 Idempotent Consumer다 (Step 7)

### 코드 확인 포인트

- [ ] `CouponCommandHandler.handle()` — 재고 없으면 `IllegalStateException` 던짐 → Command는 실패 가능
- [ ] `OrderCreatedEvent.of()` — 항상 성공 → Event 발행 자체는 실패하지 않음
- [ ] `IssueCouponCommand` — `commandId`, `requestedAt` 필드 확인
- [ ] `OrderCreatedEvent` — `eventId`, `occurredAt` 필드 확인
- [ ] `Order.create()` — Command 실행 결과가 도메인 객체가 되고, 그 객체에서 Event를 만든다

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `Command는_실패할_수_있고_발신자가_처리해야_한다` | 재고 소진 시 예외 발생, 발신자가 결과를 받아야 함 |
| `Event는_이미_일어난_사실이므로_발행_자체는_실패하지_않는다` | `.of()`는 항상 성공, 발행 자체는 실패 없음 |
| `같은_도메인에서_Command_실행_결과가_Event가_된다` | Command → 도메인 실행 → Event 흐름 |
| `Command는_미래시제다_아직_일어나지_않은_일` | `requestedAt` 존재, `commandId` 존재 |
| `Event는_과거시제다_이미_확정된_사실` | `occurredAt` 존재, `eventId` 존재 |
| `Command는_수신자를_특정한다_1대1` | 발신자가 handler를 직접 호출 |
| `Event는_수신자를_모른다_1대N` | 발행자 코드 변경 없이 리스너 3개가 반응 |

---

## Step 1 — Application Event

### 직접 호출의 문제

- [ ] 직접 호출 방식에서 `OrderService`는 모든 후속 서비스를 생성자에서 받는다
- [ ] 생성자 파라미터 = 의존성 목록이다
- [ ] `DirectOrderService` 생성자 파라미터 4개: `OrderRepository`, `StockService`, `CouponService`, `PointService`
- [ ] 후속 서비스가 늘어날수록 생성자 파라미터도 늘어난다 — `OrderService`를 계속 수정해야 한다
- [ ] 직접 호출은 같은 트랜잭션 안에서 실행된다
- [ ] 포인트 적립 실패 → 같은 TX → 주문까지 롤백된다
- [ ] 이게 문제인 이유: 포인트 적립은 실패해도 주문은 유지해야 하기 때문이다

### ApplicationEvent로 분리

- [ ] `ApplicationEventPublisher`를 쓰면 `OrderService`가 후속 서비스를 직접 알 필요가 없다
- [ ] `EventedOrderService` 생성자 파라미터 2개: `OrderRepository`, `ApplicationEventPublisher`
- [ ] `StockService`, `CouponService`, `PointService` 의존이 사라진다
- [ ] 새 리스너를 추가할 때 `OrderService` 코드를 수정하지 않아도 된다 — 리스너만 추가하면 끝
- [ ] 이것이 OCP(Open-Closed Principle): 확장에는 열려있고, 수정에는 닫혀있다

### @EventListener의 한계

- [ ] `@EventListener`는 발행자와 **같은 스레드**에서 동기적으로 실행된다
- [ ] Spring 트랜잭션은 `ThreadLocal`에 저장된다 — 같은 스레드 = 같은 트랜잭션 공유
- [ ] 리스너에 `@Transactional`이 없어도 발행자의 TX에 자동으로 참여한다
- [ ] 리스너에서 예외 발생 → 발행자의 TX도 롤백된다
- [ ] 이벤트로 형태는 분리했지만 실행은 분리하지 못한 것이다 — 결합도는 줄었지만 장애 전파는 그대로
- [ ] 이 한계가 Step 2로 넘어가는 이유다

### "전부 이벤트로 바꾸자" 함정

- [ ] `ApplicationEvent`로 발행했다고 Event가 되는 게 아니다
- [ ] 도구(ApplicationEvent, Kafka 등)가 메시지의 성격을 바꾸지 않는다
- [ ] 이름이 `Event`로 끝나도 "해라"면 Command다
- [ ] 잘못된 이름 예시: `InventoryDeductEvent`("재고를 차감해라" → Command), `CouponUseEvent`, `PaymentRequestEvent`
- [ ] 올바른 이름 예시: `OrderCreated`, `OrderCompleted`, `OrderCancelled` (과거형)
- [ ] 재고 차감을 이벤트로 발행하면 재고가 안 차감돼도 주문이 완성되는 비즈니스 오류가 생긴다
- [ ] Command인 것(재고, 쿠폰, 결제)은 직접 호출로 남겨야 한다
- [ ] Event인 것(포인트, 알림)만 이벤트로 분리한다

### 올바른 최종 형태

- [ ] Command인 것: 직접 호출 (`StockService`, `CouponService`) → 의존성으로 남음
- [ ] Event인 것: `ApplicationEventPublisher`로 발행 (`PointService` 의존 제거)
- [ ] 최종 의존성: `OrderRepository`, `StockService`, `CouponService`, `ApplicationEventPublisher` — 4개
- [ ] "2개로 줄었다"는 전부 이벤트로 발행한 중간 단계의 이야기다 — 최종 답이 아니다
- [ ] 줄어든 의존성: `PointService` 하나 → `ApplicationEventPublisher`로 교체

### 코드 확인 포인트

- [ ] `DirectOrderService` — 생성자 파라미터 4개, `stockService.deductStock()` 직접 호출
- [ ] `EventedOrderService` — 생성자 파라미터 2개, `eventPublisher.publishEvent()` 호출
- [ ] `PointEventListener` — `@EventListener` 어노테이션, `lastExecutionThread` 저장
- [ ] `DirectPointService.setShouldFail(true)` — 실패 시뮬레이션
- [ ] `PointEventListener.setShouldFail(true)` — 리스너 실패 시뮬레이션

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `직접_호출_방식에서_OrderService는_모든_후속_서비스에_의존한다` | 생성자 파라미터 4개, StockService/CouponService/PointService 포함 |
| `직접_호출_방식에서_후속_처리_실패시_주문도_롤백된다` | PointService 실패 → 주문도 rollback → `orderRepository.findAll()` 비어있음 |
| `직접_호출_방식에서_모든_후속_처리가_성공하면_주문이_완료된다` | 정상 흐름 확인 |
| `이벤트_방식에서_OrderService는_EventPublisher에만_의존한다` | 생성자 파라미터 2개, ApplicationEventPublisher 포함 |
| `이벤트_발행_후_리스너가_정상_처리하면_모든_데이터가_저장된다` | 주문 저장 + 포인트 500원 적립 확인 |
| `후속_로직_추가시_OrderService는_수정하지_않아도_된다` | 리스너 추가해도 파라미터 수 2개 유지 |
| `리스너_예외가_발행자_트랜잭션을_롤백시킨다` | 리스너 실패 → 주문도 rollback |
| `EventListener는_발행자와_같은_스레드에서_동기적으로_실행된다` | 발행자 스레드 이름 == 리스너 실행 스레드 이름 |

---

## Step 0 → Step 1 연결 흐름

```
Step 0: Command와 Event를 개념으로 구분한다
         ↓
         "포인트 적립은 Event다 — 주문 확정 후 처리해도 된다"
         ↓
Step 1: 그럼 코드에서 어떻게 분리하는가?
         ↓
         직접 호출 → 의존성 폭발, 장애 전파
         ApplicationEvent → 의존성 감소, 확장 용이
         But: @EventListener는 같은 TX → 여전히 장애 전파
         ↓
Step 2: 트랜잭션을 진짜로 분리하는 방법 (@TransactionalEventListener, @Async)
```