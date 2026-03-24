# Step 2 — Transactional Event 학습 테스트

트랜잭션 커밋 타이밍과 이벤트 실행 타이밍의 관계를 이해한다.
@Async의 편리함과 실패 은닉 문제를 동시에 체험한다.
AFTER_COMMIT + @Async를 선택한 순간, Eventual Consistency를 수용한 것이다.

---

## EventListenerTimingTest

@EventListener의 타이밍 위험 — 커밋 전에 실행되므로 롤백 시 부수효과가 되돌려지지 않는다.

### EventListener는 커밋 전에 실행되어 롤백시 부수효과가 되돌려지지 않는다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant PL as SyncPointListener

    Note over OS: TX BEGIN
    OS->>DB: INSERT 주문
    OS->>PL: @EventListener → 동기 실행

    PL->>PL: 외부 API 호출 (이미 실행됨!)

    Note over OS: TX ROLLBACK!
    Note over PL: 외부 API 호출은<br/>되돌릴 수 없다

    Test->>PL: isExternalApiCalled()?
    PL-->>Test: true

    Test->>DB: findAll()
    Note over DB: 비어있음 (롤백됨)

    Note over Test: 주문은 취소됐지만<br/>외부 API는 이미 호출됨
```

---

## TransactionalEventListenerTest

@TransactionalEventListener(AFTER_COMMIT)의 안전한 타이밍.
커밋 후에만 실행되므로 롤백 시 리스너가 실행되지 않는다.

### TransactionalEventListener는 커밋 후에만 실행된다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant PL as TransactionalPointListener

    Note over OS: TX BEGIN
    OS->>DB: INSERT 주문
    OS->>OS: publish(OrderCreatedEvent)
    Note right of OS: 이벤트는 "등록"만 됨<br/>아직 실행되지 않음

    Note over OS: TX COMMIT

    Note over PL: 커밋 확인 후 실행
    PL->>PL: 포인트 적립

    Test->>PL: isExecuted()?
    PL-->>Test: true
```

### 트랜잭션이 롤백되면 TransactionalEventListener는 실행되지 않는다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant PL as TransactionalPointListener

    Note over OS: TX BEGIN
    OS->>DB: INSERT 주문 (실패 유도)
    Note over OS: TX ROLLBACK

    Note over PL: 커밋되지 않았으므로<br/>리스너 실행 안 됨

    Test->>PL: isExecuted()?
    PL-->>Test: false
    Test->>DB: findAll()
    Note over DB: 비어있음
```

### TransactionalEventListener 예외는 발행자 트랜잭션에 영향을 주지 않는다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant PL as TransactionalPointListener<br/>(shouldFail=true)

    Note over OS: TX BEGIN
    OS->>DB: INSERT 주문
    Note over OS: TX COMMIT ✅

    PL->>PL: 포인트 적립 시도
    PL--xPL: RuntimeException!

    Note over PL: 리스너 예외 발생했지만<br/>주문 TX는 이미 커밋됨

    Test->>DB: 주문 조회 → status=CREATED ✅
    Test->>DB: 포인트 조회 → 없음 ❌

    Note over Test: Step 1 한계 해결!<br/>리스너 실패가 주문을<br/>롤백시키지 않는다
```

---

## AsyncEventTest

@Async의 편리함과 실패 은닉 문제.

### Async 리스너는 별도 스레드에서 실행되어 응답이 빠르다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant EP as EventPublisher
    participant AL as AsyncPointListener

    Note over Test: callerThread 기록

    OS->>EP: publish(OrderCreatedEvent)
    EP-->>OS: 즉시 반환 (비동기)
    OS-->>Test: 응답 (빠름!)

    Note over AL: 별도 스레드에서 실행
    AL->>AL: 포인트 적립

    Test->>AL: getExecutedThread()
    Note over Test: callerThread ≠ listenerThread<br/>별도 스레드 확인
```

### Async 리스너 예외는 호출자에게 전파되지 않는다 — 실패가 숨겨진다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant AL as AsyncPointListener<br/>(shouldFail=true)

    OS->>DB: 주문 저장 + COMMIT
    OS-->>Test: 정상 응답

    Note over AL: 별도 스레드에서 실행
    AL--xAL: RuntimeException!
    Note over AL: 예외가 호출자에게<br/>전파되지 않는다!

    Test->>Test: assertDoesNotThrow ✅
    Test->>DB: 주문 조회 → 있음 ✅
    Test->>DB: 포인트 조회 → 없음 ❌

    Note over Test: 호출자는 성공으로 알고 있지만<br/>포인트는 적립되지 않았다
```

---

## EventualConsistencyTest

Eventual Consistency 체험 — AFTER_COMMIT + @Async를 선택한 순간, 즉시 일관성은 포기한 것이다.

### 주문 직후 포인트를 조회하면 아직 반영되지 않았을 수 있다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant AL as AsyncPointListener

    OS->>DB: 주문 저장 + COMMIT
    OS-->>Test: 200 OK

    Test->>DB: 포인트 즉시 조회
    Note over DB: 아직 반영 안 됐을 수도 있음<br/>(스레드 스케줄링에 따라 다름)

    Note over AL: 비동기 처리 중...
    AL->>DB: 포인트 적립

    Test->>Test: latch.await() (비동기 완료 대기)

    Test->>DB: 포인트 재조회
    Note over DB: amount = 500 ✅

    Note over Test: 이 "잠시"가 Eventual Consistency<br/>버그가 아니라 설계 결정이다
```

> **코드 주석:** `assertThat(point).isEmpty()`로 단정하지 않는 이유 — 스레드 스케줄링에 따라 이미 반영되었을 수 있어 테스트가 불안정해진다. Eventual Consistency의 핵심은 "없을 수도 있다"이지 "항상 없다"가 아니다.

---

## AsyncEventLossTest

@Async 이벤트가 메모리에만 존재하므로, 서버가 죽으면 유실되는 한계.
이 한계가 Step 3(Event Store)으로 넘어가는 동기가 된다.

### 서버가 재시작되면 Async 리스너가 처리하지 못한 이벤트는 유실된다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant AL as AsyncPointListener<br/>(shouldFail=true)

    OS->>DB: 주문 저장 + COMMIT ✅
    OS-->>Test: 200 OK

    Note over AL: 별도 스레드에서 실행
    AL--xAL: 실패 (서버 재시작 / 예외)
    Note over AL: 메모리에만 있던 이벤트가<br/>사라졌다

    Test->>DB: 주문 조회 → 있음 ✅
    Test->>DB: 포인트 조회 → 없음 ❌
    Test->>DB: 이벤트 기록 조회 → 없음 ❌

    Note over Test: 재처리할 기록이 없다!<br/>Step 3에서는 Event Store(DB)에<br/>PENDING 레코드가 남아<br/>스케줄러가 재처리할 수 있다
```

---

## 학습 포인트

이 Step을 마치면 다음 질문에 답할 수 있어야 합니다:

- [ ] `@EventListener`와 `@TransactionalEventListener(AFTER_COMMIT)`의 실행 타이밍 차이는?
- [ ] AFTER_COMMIT 리스너에서 예외가 발생하면 주문 트랜잭션에 영향을 주는가? 왜?
- [ ] `@Async`를 붙이면 응답은 빨라지지만 무엇을 잃는가?
- [ ] 주문 직후 포인트를 조회하면 0이 나올 수 있다 — 이것은 버그인가, 설계 결정인가?
- [ ] 서버가 재시작되면 `@Async` 스레드의 이벤트는 어디로 가는가?

> `EventualConsistencyTest` 코드에서 `assertThat(point).isEmpty()`를 쓰지 않는 이유를 주석으로 확인해 보세요. 스레드 스케줄링에 따라 이미 반영되었을 수 있어 테스트가 불안정해지기 때문입니다.

---

## 이 Step에서 인식해야 할 것

AFTER_COMMIT + @Async를 선택한 이 순간, Eventual Consistency를 수용한 것이다.

## 체험할 한계 -> Step 3으로

@Async로 별도 스레드에서 도는 순간, 서버가 재시작되면 메모리의 이벤트는 증발한다.
