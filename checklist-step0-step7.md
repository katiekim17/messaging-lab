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

---

## Step 2 — TransactionalEventListener + Async

### 4가지 Phase 이해

- [ ] `BEFORE_COMMIT` — TX 커밋 직전, 아직 같은 TX 안이다
- [ ] `AFTER_COMMIT` — TX 커밋 완료 후 실행된다 — 발행자 TX에 영향 없음
- [ ] `AFTER_ROLLBACK` — TX 롤백 후 실행된다 — 보상 처리, 알림 용도
- [ ] `AFTER_COMPLETION` — 커밋/롤백 관계없이 TX 종료 후 실행된다 — 리소스 정리 용도
- [ ] `BEFORE_COMMIT`에서 예외 발생 → 발행자 TX도 롤백된다 (Step 1과 동일한 문제)
- [ ] `AFTER_COMMIT`에서 예외 발생 → 발행자 TX에는 영향 없다 (진짜 분리)
- [ ] 어떤 Phase를 쓸지는 "이 리스너가 TX 결과에 의존하는가?"로 결정한다

### 4가지 함정 (Trap)

- [ ] **함정 1 — `@Transactional` 누락**: 발행자에 `@Transactional`이 없으면 `@TransactionalEventListener`가 **아예 실행되지 않는다** — 오류 없이 조용히 실패
- [ ] **함정 2 — `@EnableAsync` 누락**: `@Async`가 붙어도 `@EnableAsync`가 없으면 **동기적으로 실행된다** — 오류 없이 조용히 무시
- [ ] **함정 3 — AFTER_COMMIT에서 직접 DB 저장**: TX가 이미 닫힌 상태라 `repository.save()`가 `TransactionRequiredException` 발생
  - 해결: 별도 Bean의 `@Transactional(REQUIRES_NEW)`로 새 TX를 열어야 한다
- [ ] **함정 4 — 스레드풀 기본 설정**: 기본 설정은 corePoolSize=8, 큐 무제한 → 이벤트 폭증 시 OOM 위험
  - 해결: `ThreadPoolTaskExecutor`를 직접 설정하고 큐 크기를 제한한다

### @Async의 의미와 대가

- [ ] `@Async` 리스너는 별도 스레드에서 실행된다 → 발행자 응답이 즉시 반환된다
- [ ] 속도를 얻는 대신 **실패가 숨겨진다** — 리스너 예외가 호출자에게 전파되지 않는다
- [ ] 발행자는 리스너가 성공했는지 알 수 없다
- [ ] `AsyncUncaughtExceptionHandler`로 예외를 별도 로깅/알림해야 한다
- [ ] "빠르다"와 "성공을 보장한다"는 양립할 수 없다 — 설계 의도를 명확히 해야 한다

### Eventual Consistency 수용

- [ ] `AFTER_COMMIT + @Async` 조합은 주문 직후 포인트가 아직 반영 안 됐을 수 있다
- [ ] 이것은 버그가 아니라 **설계 선택**이다 — 최종 일관성(Eventual Consistency)
- [ ] "언젠가는 반영된다"를 보장하는 것이 중요하다 — 서버 재시작 시 이벤트 유실 문제로 이어짐
- [ ] Async 리스너가 처리 중 서버가 재시작되면 **이벤트는 유실된다** — 이것이 Step 3로 넘어가는 이유

### 코드 확인 포인트

- [ ] `TransactionalPointListener` — `@TransactionalEventListener(phase = AFTER_COMMIT)` 확인
- [ ] `AsyncTransactionalPointListener` — `@Async` + `@TransactionalEventListener` 조합 확인
- [ ] `AsyncConfig` — `ThreadPoolTaskExecutor` 설정 확인 (corePoolSize, maxPoolSize, queueCapacity)
- [ ] `PointSaveService` — `@Transactional(propagation = REQUIRES_NEW)` 확인
- [ ] `PhaseTestListener` — 4가지 Phase 전부 확인

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `TransactionalEventListener는_커밋_후에만_실행된다` | AFTER_COMMIT에서 실행됨 |
| `트랜잭션이_롤백되면_TransactionalEventListener는_실행되지_않는다` | 롤백 시 미실행 |
| `TransactionalEventListener_예외는_발행자_트랜잭션에_영향을_주지_않는다` | 예외 격리 확인 |
| `BEFORE_COMMIT_리스너에서_예외_발생_시_발행자_TX가_롤백된다` | 함정 1 위험성 |
| `AFTER_ROLLBACK_리스너는_롤백_후에만_실행된다` | 보상 처리 용도 |
| `AFTER_COMPLETION_리스너는_커밋_롤백_무관하게_실행된다` | 리소스 정리 용도 |
| `Async_리스너는_별도_스레드에서_실행되어_응답이_빠르다` | 스레드 분리 확인 |
| `Async_리스너_예외는_호출자에게_전파되지_않는다_실패가_숨겨진다` | 실패 가시성 부재 |
| `트랜잭션_없이_이벤트를_발행하면_TransactionalEventListener가_불리지_않는다` | 함정 1 |
| `EnableAsync가_있어야_Async_리스너가_별도_스레드에서_실행된다` | 함정 2 |
| `AFTER_COMMIT_리스너에서_DB_저장하면_TransactionRequiredException_발생` | 함정 3 |
| `AFTER_COMMIT_리스너에서_별도_빈의_REQUIRES_NEW로_새_TX를_열면_DB_저장_가능` | 함정 3 해결 |
| `서버가_재시작되면_Async_리스너가_처리하지_못한_이벤트는_유실된다` | Step 3 필요 이유 |
| `주문_직후_포인트를_조회하면_아직_반영되지_않았을_수_있다` | Eventual Consistency 체감 |

---

## Step 3 — Event Store

### Event Store의 핵심 아이디어

- [ ] 도메인 저장 + 이벤트 기록을 **하나의 트랜잭션**으로 묶는다
- [ ] 둘 다 커밋되거나 둘 다 롤백된다 — 부분 성공이 없다
- [ ] 이벤트를 메모리(Async 스레드)가 아닌 **DB에 저장**한다 — 서버 재시작 후에도 살아있다
- [ ] Step 2의 유실 문제가 해결된다

### 상태 기반 설계 (PENDING → PROCESSED)

- [ ] `EventRecord.status = PENDING` — 아직 처리되지 않은 이벤트
- [ ] `EventRecord.status = PROCESSED` — 처리 완료, 재처리 불필요
- [ ] 트랜잭션 락이 아닌 **상태값**으로 중복 처리를 막는다
- [ ] 이 패턴이 분산 환경에서도 통하는 이유: 트랜잭션 경계를 넘어 상태가 유지되기 때문
- [ ] 상태 전이 방향: PENDING → PROCESSED (단방향) — 역행하지 않는다

### EventRelay (스케줄러)

- [ ] `EventRelay`는 `@Scheduled`로 주기적으로 PENDING 이벤트를 조회한다
- [ ] PENDING 이벤트를 처리(PointService 호출)하고 PROCESSED로 변경한다
- [ ] 이미 PROCESSED인 이벤트는 조회 대상에서 제외된다 — 중복 처리 없음
- [ ] 서버 재시작 후에도 DB의 PENDING 이벤트를 이어서 처리한다

### 한계: 프로세스 경계

- [ ] Step 3는 같은 프로세스(같은 DB) 안에서만 동작한다
- [ ] PointService가 다른 서버에 있다면 직접 호출 불가 — 네트워크 경계가 생긴다
- [ ] 이 한계가 Step 4~6으로 넘어가는 이유다

### 코드 확인 포인트

- [ ] `OrderEventStoreService.createOrder()` — `orderRepository.save()` + `eventRecordRepository.save()` 같은 `@Transactional` 안
- [ ] `EventRecord` — `id`, `eventType`, `payload`, `status`, `createdAt` 필드 확인
- [ ] `EventStatus` enum — `PENDING`, `PROCESSED` 값 확인
- [ ] `EventRelay.relay()` — `findByStatus(PENDING)` → 처리 → `status = PROCESSED` → 저장 흐름

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `주문_저장과_이벤트_기록은_하나의_트랜잭션으로_묶인다` | 같은 TX, 둘 다 저장됨 |
| `주문_저장이_실패하면_이벤트_기록도_함께_롤백된다` | 같은 TX, 둘 다 롤백됨 |
| `스케줄러는_PENDING_상태의_이벤트를_조회하여_처리한다` | 릴레이 조회 조건 확인 |
| `처리_완료된_이벤트는_PROCESSED_상태로_변경된다` | 상태 전이 확인 |
| `이미_처리된_이벤트는_다시_처리하지_않는다` | 중복 처리 방지 |
| `서버_재시작_후에도_PENDING_이벤트는_DB에_남아있다` | 내구성(Durability) 확인 |
| `재시작_후_스케줄러가_PENDING_이벤트를_재처리한다` | 복구(Recovery) 확인 |

---

## Step 4 — Redis Pub/Sub

### Redis Pub/Sub의 동작 방식

- [ ] Publisher가 채널에 메시지를 발행하면 현재 구독 중인 모든 Subscriber가 수신한다
- [ ] 메시지는 **저장되지 않는다** — 발행 시점에 연결된 구독자만 받는다
- [ ] 구독자가 없으면 메시지는 사라진다
- [ ] 다운된 구독자는 복구 후 그 사이의 메시지를 받을 수 없다

### Redis Pub/Sub이 적합한 사례

- [ ] 캐시 무효화 신호 — 못 받아도 TTL이 만료되면 자연 해결
- [ ] 실시간 알림 — 못 받아도 다음 조회 시 최신 데이터를 가져오면 됨
- [ ] 서버 간 동기화 신호 — 못 받아도 다음 동기화 주기에 처리됨

### Redis Pub/Sub이 적합하지 않은 사례

- [ ] 독립적인 소비 속도가 필요한 경우 — 각 Consumer가 자기 페이스로 처리해야 할 때
- [ ] 메시지 순서 보장이 필요한 경우
- [ ] 이벤트 재처리(Replay)가 필요한 경우
- [ ] 비즈니스 핵심 흐름 — 메시지 유실이 허용되지 않는 경우

### 한계 인식

- [ ] 구독자가 없을 때 발행된 메시지는 유실된다
- [ ] 구독자가 다운된 동안 발행된 메시지는 복구 후에도 수신 불가
- [ ] 이 한계가 Step 5 (RabbitMQ, 큐에 저장)로 넘어가는 이유

### Event Store + Redis 하이브리드 패턴

- [ ] Redis = "빠른 알림 채널" — 즉시 처리 시도
- [ ] Event Store (DB) = "내구성 안전망" — Redis 신호를 못 받아도 PENDING 이벤트가 DB에 남아있음
- [ ] 두 개를 함께 쓰면 속도와 내구성을 모두 확보할 수 있다

### 코드 확인 포인트

- [ ] `RedisEventPublisher` — `RedisTemplate.convertAndSend(channel, message)` 확인
- [ ] `RedisMessageConfig` — `MessageListenerAdapter`, `RedisMessageListenerContainer` 설정 확인

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `발행한_메시지를_구독자가_수신한다` | 기본 Pub/Sub 파이프라인 |
| `여러_구독자가_동일한_메시지를_모두_수신한다` | Fan-out (1:N) 브로드캐스트 |
| `구독자가_없으면_발행된_메시지는_유실된다` | 비내구성 한계 |
| `구독자가_다운된_동안_발행된_메시지는_수신할_수_없다` | 복구 불가 한계 |

---

## Step 5 — RabbitMQ

### 큐 기반 저장의 핵심 차이

- [ ] RabbitMQ는 메시지를 **큐에 저장**한다 — 구독자가 없어도 메시지가 보존된다
- [ ] Consumer가 다운된 동안 발행된 메시지를 재시작 후 이어서 수신할 수 있다
- [ ] Redis Pub/Sub의 "유실" 문제가 해결된다

### 메시지 전달 보장 수준

- [ ] `At-Most-Once` — 최대 1번 전달 (Redis Pub/Sub) — 유실 가능
- [ ] `At-Least-Once` — 최소 1번 전달 (RabbitMQ, Kafka) — 중복 가능
- [ ] `Exactly-Once` — 정확히 1번 (Kafka + Idempotent Consumer 조합) — 설계 필요
- [ ] RabbitMQ는 At-Least-Once다 — 중복 수신이 발생할 수 있다

### ACK와 메시지 삭제

- [ ] Consumer가 메시지를 ACK하면 **큐에서 삭제**된다
- [ ] 삭제된 메시지는 다른 Consumer가 읽을 수 없다
- [ ] 같은 이벤트를 다른 시스템에서 독립적으로 소비하려면 **각각 별도 큐**가 필요하다
- [ ] 이것이 RabbitMQ의 한계: **이벤트 재처리(Replay) 불가**

### Competing Consumers 패턴

- [ ] 같은 큐에 Consumer 2개가 있으면 메시지를 **분산 처리**한다
- [ ] 부하 분산(Load Distribution) 용도 — 처리 속도를 높인다
- [ ] 같은 메시지를 2번 처리하지 않는다 (큐가 분배)

### 한계 인식

- [ ] ACK된 메시지는 삭제 → 동일 메시지를 여러 Consumer Group이 독립 소비 불가
- [ ] 과거 이벤트를 다시 재생(Replay) 불가
- [ ] 이 한계가 Step 6 (Kafka, 로그 기반 보관)로 넘어가는 이유

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `Producer가_보낸_메시지를_Consumer가_수신한다` | 기본 파이프라인 |
| `Consumer가_없어도_메시지는_큐에_보존된다` | Redis 대비 개선: 내구성 확보 |
| `Consumer가_다운된_동안_발행된_메시지를_재시작_후_수신한다` | 복구 후 이어받기 |
| `같은_큐의_Consumer_2개가_메시지를_나눠_처리한다` | Competing Consumers 패턴 |
| `ACK한_메시지는_큐에서_삭제되어_다시_읽을_수_없다` | RabbitMQ 한계 |
| `소비_완료된_메시지를_다른_Consumer가_다시_읽을_수_없다` | Replay 불가 한계 |

---

## Step 6 — Kafka + Transactional Outbox

### 로그 기반 저장의 핵심 차이

- [ ] Kafka는 메시지를 **로그로 보관**한다 — ACK 후에도 삭제되지 않는다
- [ ] Consumer는 **offset**으로 자기 읽기 위치를 추적한다
- [ ] Offset을 되돌리면 과거 메시지를 **재처리(Replay)**할 수 있다
- [ ] 여러 Consumer Group이 **독립적으로** 같은 토픽을 소비할 수 있다

### Consumer Group 독립성

- [ ] Consumer Group A와 B는 **각자 offset을 관리**한다
- [ ] Group A가 느려도 Group B에 영향 없다
- [ ] 새 Consumer Group을 추가해도 기존 Group에 영향 없다
- [ ] RabbitMQ에서 필요했던 "별도 큐" 설정이 불필요하다

### 파티션 키 설계

- [ ] 같은 키 → 같은 파티션 → **순서 보장**
- [ ] 다른 키 → 다른 파티션으로 분산 → 순서 미보장 (파티션 간)
- [ ] 키 선택 기준: "어떤 리소스를 두고 경쟁하는가?"
  - 쿠폰 선착순 → `couponId`가 키 (쿠폰을 두고 경쟁)
  - 사용자별 포인트 → `userId`가 키 (사용자 단위 순서 필요)
- [ ] Hot Partition 위험: 특정 키에 트래픽 집중 → 해당 파티션만 과부하

### Transactional Outbox 패턴 완성

- [ ] Step 3에서 시작한 Outbox 패턴이 Step 6에서 완성된다
- [ ] 도메인 저장 + Outbox 기록 = 1 TX (Step 3와 동일)
- [ ] Relay가 PENDING → Kafka 발행 → SENT 변경
- [ ] Consumer는 Kafka에서 독립적으로 소비

**상태 전이**:
- [ ] `PENDING` → 아직 Kafka에 발행 안 됨
- [ ] `SENT` → Kafka에 발행됨 (Consumer 처리 여부와 무관)
- [ ] Consumer는 Kafka offset으로 자기 처리 위치를 관리한다

### At-Least-Once 구조적 발생 원인

- [ ] Relay가 Kafka 발행 성공 후 SENT 업데이트 전에 서버가 다운되면:
  - 재시작 시 PENDING 상태 → 다시 발행 → **Kafka에 중복 메시지**
- [ ] 이것은 버그가 아니라 **내구성을 위한 구조적 선택**이다
- [ ] 유실보다 중복이 낫다 — Consumer가 멱등(Idempotent)하게 처리하면 된다 (Step 7)

### 코드 확인 포인트

- [ ] `OutboxOrderService.createOrder()` — `orderRepository.save()` + `outboxEventRepository.save()` 같은 TX
- [ ] `KafkaEventRelay.relay()` — `findByStatus(PENDING)` → `kafkaTemplate.send()` → `status = SENT` 흐름
- [ ] `OutboxEvent` — `id`, `eventType`, `payload`, `status`, `createdAt` 필드 확인
- [ ] `OutboxStatus` enum — `PENDING`, `SENT` 값 확인

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `Producer가_보낸_메시지를_Consumer가_수신한다` | 기본 Kafka 파이프라인 |
| `구독자가_없어도_메시지는_Kafka에_보존된다` | 로그 보관 확인 |
| `Consumer가_중지된_사이에_발행된_메시지를_재시작_후_이어서_읽는다` | Offset 추적 확인 |
| `두_Consumer_Group이_같은_토픽의_모든_메시지를_각각_독립적으로_수신한다` | Group 독립성 |
| `한_Consumer_Group의_소비_속도가_다른_Group에_영향을_주지_않는다` | 속도 독립성 |
| `같은_key의_메시지는_같은_파티션에_저장된다` | 파티션 할당 |
| `같은_파티션의_메시지는_발행_순서대로_소비된다` | 파티션 내 순서 보장 |
| `다른_key의_메시지는_다른_파티션으로_분배될_수_있다` | 파티션 분산 |
| `주문_저장과_이벤트_기록이_하나의_트랜잭션으로_묶인다` | Outbox 원자성 |
| `릴레이가_PENDING_이벤트를_Kafka로_발행하고_SENT로_변경한다` | 릴레이 정상 동작 |
| `Kafka_발행_실패_시_이벤트는_여전히_PENDING_상태를_유지한다` | 발행 실패 복원력 |

---

## Step 7 — Idempotent Consumer

### 중복 수신이 발생하는 이유

- [ ] Kafka(RabbitMQ 포함) At-Least-Once 구조에서 중복 메시지는 **구조적으로 발생**한다
- [ ] "같은 메시지를 2번 처리하면 어떻게 되는가?"가 설계 질문이다
- [ ] Consumer는 Producer를 신뢰하지 않는다 — 항상 중복을 가정하고 설계한다

### 패턴 0 — 상태 머신 (비용 없음)

- [ ] 단방향 상태 전이에만 적용 가능하다
- [ ] 예시: `CREATED → PAID → SHIPPED` — 이미 PAID면 PAID 처리를 다시 해도 상태 변화 없음
- [ ] 비용: 0 — 도메인 로직이 자연스럽게 막아준다
- [ ] 한계: 양방향 상태(`PAID ↔ REFUNDED`)나 누적 연산(포인트 적립)에는 적용 불가

### 패턴 1 — event_handled 테이블 (범용)

- [ ] `event_handled` 테이블에 처리된 `eventId`를 저장한다
- [ ] 처리 전 `existsByEventId()` 조회 → 있으면 skip
- [ ] `eventId` 컬럼에 UNIQUE 제약 → 동시 중복 처리에 대한 최후 방어선
- [ ] 비용: 테이블 + 인덱스 + 메시지마다 조회 1회
- [ ] 범용성: 어떤 도메인에든 적용 가능

### 패턴 2 — Upsert (집계 데이터)

- [ ] "최신 값으로 덮어쓰기" 방식 — `+1 증가`가 아니라 `최신값 세팅`
- [ ] 예시: 상품 조회수 (조회수 카운터를 이벤트의 값으로 덮어씀)
- [ ] 같은 이벤트를 2번 처리해도 결과가 동일하다
- [ ] 비용: 추가 테이블 없음, 단일 연산
- [ ] 한계: 포인트 적립처럼 `+100`이 필요한 경우에는 사용 불가

### 패턴 3 — 버전 비교 (순서 역전 방어)

- [ ] 이벤트에 단조 증가하는 `version` 필드가 있어야 한다
- [ ] 현재 저장된 version보다 **높은 version만 반영**한다
- [ ] 중복 처리와 순서 역전 모두 방어한다
- [ ] 비용: 이벤트에 version 필드 필요, 구현 복잡도 증가
- [ ] 적합한 경우: 재고 수량처럼 최신 상태가 중요하고 순서가 보장되어야 할 때

### Dead Letter Queue (DLQ)

- [ ] 파싱 불가능한 메시지(Poison Pill)는 Consumer를 계속 블로킹한다
- [ ] 처리할 수 없는 메시지는 DLQ 토픽으로 격리한다
- [ ] DLQ 격리 후 나머지 메시지는 정상 처리를 계속한다
- [ ] DLQ는 기술 예외 처리가 아니라 **운영 필수 인프라**다
- [ ] DLQ 모니터링, 알림, 재처리 절차를 별도로 마련해야 한다

### 패턴 선택 기준

- [ ] 상태 전이가 단방향인가? → **패턴 0** (상태 머신)
- [ ] "최신 값으로 덮어써도 되는가?" → **패턴 2** (Upsert)
- [ ] 순서 역전도 방어해야 하는가? → **패턴 3** (버전 비교)
- [ ] 위 조건이 아니면? → **패턴 1** (event_handled 테이블)

### Fail-Open vs Fail-Closed

- [ ] **Fail-Closed**: 처리 실패 시 메시지를 재처리한다 — 결제, 재고 차감 (손실 불허)
- [ ] **Fail-Open**: 처리 실패 시 넘어간다 — 마케팅 알림, 추천 (일부 유실 허용)
- [ ] 도메인의 비즈니스 중요도가 이 선택을 결정한다

### 코드 확인 포인트

- [ ] `NaivePointConsumer` — 중복 처리 시 포인트가 2배 적립되는 문제 확인
- [ ] `IdempotentPointConsumer` — `eventHandledRepo.existsByEventId()` 확인, UNIQUE 제약 확인
- [ ] `ViewCountConsumer` — `viewCountRepo.save()` (upsert 방식) 확인
- [ ] `VersionedStockConsumer` — `event.getVersion() > current.getVersion()` 조건 확인
- [ ] `EventHandled` — `eventId` 필드의 UNIQUE 제약 확인

### 테스트 대응표

| 테스트 | 확인하는 것 |
|---|---|
| `같은_메시지를_2번_소비하면_포인트가_2번_적립된다` | 멱등성 없는 Consumer의 문제 |
| `event_handled_테이블에_이미_처리된_이벤트가_있으면_스킵한다` | 패턴 1 정상 동작 |
| `서로_다른_event_id의_메시지는_각각_정상_처리된다` | 패턴 1 — 서로 다른 이벤트는 처리 |
| `같은_이벤트를_2번_처리해도_upsert로_올바른_결과가_유지된다` | 패턴 2 정상 동작 |
| `upsert는_최신_값으로_덮어쓰므로_최종_상태가_보장된다` | 패턴 2 의미론 |
| `다른_상품의_이벤트는_각각_독립적으로_upsert된다` | 패턴 2 독립성 |
| `version이_현재보다_높은_이벤트만_반영된다` | 패턴 3 정방향 |
| `version이_현재보다_낮거나_같은_이벤트는_무시된다` | 패턴 3 역방향 무시 |
| `순서가_역전된_이벤트_시퀀스에서_최종_상태가_올바르다` | 패턴 3 순서 역전 방어 |
| `파싱_불가능한_메시지가_Consumer를_막는다` | Poison Pill 문제 |
| `처리_실패한_메시지를_DLQ_토픽으로_격리할_수_있다` | DLQ 격리 |

---

## Step 1 → Step 7 전체 연결 흐름

```
Step 1: @EventListener → 같은 TX → 리스너 예외가 주문을 롤백
         ↓ 문제: TX 분리가 안 됨
Step 2: @TransactionalEventListener(AFTER_COMMIT) + @Async
         → TX 분리 성공, 빠름
         ↓ 문제: 서버 재시작 시 Async 스레드의 이벤트 유실
Step 3: Event Store — 도메인 + 이벤트 = 1 TX, DB에 PENDING 저장
         → 유실 없음, 재시작 후 복구 가능
         ↓ 문제: 프로세스 경계를 넘을 수 없음
Step 4: Redis Pub/Sub — 프로세스 간 전달
         → 크로스 프로세스 가능
         ↓ 문제: 메시지 비내구성, 구독자 없으면 유실
Step 5: RabbitMQ — 큐에 저장, 내구성 확보
         → 유실 없음, 재시작 후 이어받기 가능
         ↓ 문제: ACK 후 삭제 → Replay 불가, 독립 소비 불가
Step 6: Kafka — 로그 보관, 오프셋 기반 독립 소비
         + Transactional Outbox — 도메인 + Outbox = 1 TX → 릴레이 → Kafka
         → Replay 가능, 독립 Consumer Group, At-Least-Once
         ↓ 문제: At-Least-Once → 중복 메시지 발생
Step 7: Idempotent Consumer — 중복 처리 방어
         + DLQ — Poison Pill 격리
         → 시스템 전체 완성
```