# Step 6 — Kafka 학습 테스트

Kafka의 메시지 보존, Consumer Group 독립성, 파티션 기반 순서 보장을 확인한다.
Step 3의 Event Store를 Kafka로 릴레이하면 Transactional Outbox Pattern이 완성된다.

---

## KafkaBasicPipelineTest

Kafka Producer → Consumer 기본 파이프라인.

### Producer가 보낸 메시지를 Consumer가 수신한다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant Prod as Producer
    participant Kafka as Kafka (1 partition)
    participant Cons as Consumer

    Prod->>Kafka: send(key="key-1", value="주문이 생성되었다")
    Cons->>Kafka: subscribe + poll

    Kafka-->>Cons: 1건 수신

    Note over Test: key = "key-1" ✅<br/>value = "주문이 생성되었다" ✅
```

### 여러 메시지를 순서대로 발행하면 같은 파티션에서 순서대로 소비된다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka (1 partition)
    participant Cons as Consumer

    Prod->>Kafka: key="order-1", "생성"
    Prod->>Kafka: key="order-1", "결제"
    Prod->>Kafka: key="order-1", "배송"

    Cons->>Kafka: poll

    Note over Cons: 수신 순서:<br/>1. "생성"<br/>2. "결제"<br/>3. "배송"<br/>파티션 내 순서 보장 ✅
```

---

## KafkaMessagePreservationTest

Kafka의 메시지 보존 — Consumer가 중지되어도 메시지는 로그에 남아있다.

### Consumer가 중지된 사이에 발행된 메시지를 재시작 후 이어서 읽는다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka
    participant CA as ConsumerA
    participant CB as ConsumerB

    rect rgb(230, 255, 230)
        Note over CA: Phase 1: ConsumerA 읽기
        Prod->>Kafka: msg1, msg2, msg3
        CA->>Kafka: poll → 3건 수신
        CA->>Kafka: commit (offset=3)
        Note over CA: ConsumerA 종료
    end

    rect rgb(255, 230, 230)
        Note over Kafka: Phase 2: Consumer 없음
        Prod->>Kafka: msg4, msg5
        Note over Kafka: 메시지 보존됨<br/>(Redis와 다름!)
    end

    rect rgb(230, 240, 255)
        Note over CB: Phase 3: ConsumerB 시작 (같은 Group)
        CB->>Kafka: poll (offset 3부터)
        Kafka-->>CB: msg4, msg5
        Note over CB: 중지 중 발행된 메시지를<br/>이어서 읽기 ✅
    end
```

### 구독자가 없어도 메시지는 Kafka에 보존된다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka
    participant Cons as Consumer (나중에 연결)

    Prod->>Kafka: msg1, msg2, msg3
    Note over Kafka: 구독자 없음<br/>메시지는 로그에 보존

    Note over Cons: 나중에 연결
    Cons->>Kafka: subscribe (from beginning)
    Kafka-->>Cons: msg1, msg2, msg3

    Note over Cons: 3건 모두 수신 ✅<br/>Redis Pub/Sub이었다면<br/>모두 유실됐을 것
```

---

## KafkaConsumerGroupIndependenceTest

Consumer Group 간 독립적 소비 — 각 Group은 자기만의 offset을 관리한다.

### 두 Consumer Group이 같은 토픽의 모든 메시지를 각각 독립적으로 수신한다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka
    participant GA as settlement-group (정산)
    participant GB as notification-group (알림)

    Prod->>Kafka: msg1, msg2, msg3

    GA->>Kafka: poll
    Kafka-->>GA: msg1, msg2, msg3 (3건)

    GB->>Kafka: poll
    Kafka-->>GB: msg1, msg2, msg3 (3건)

    Note over GA,GB: 각 Group이 독립적으로<br/>모든 메시지를 수신 (Fan-Out)
```

### 한 Consumer Group의 소비 속도가 다른 Group에 영향을 주지 않는다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka
    participant Slow as slow-group
    participant Fast as fast-group

    rect rgb(230, 255, 230)
        Prod->>Kafka: msg1, msg2, msg3
        Slow->>Kafka: poll → 3건 + commit (offset=3)
    end

    rect rgb(255, 245, 230)
        Prod->>Kafka: msg4, msg5
        Note over Slow: slow-group은 아직 읽지 않음
    end

    Fast->>Kafka: poll (from beginning)
    Kafka-->>Fast: msg1~msg5 (5건 전부)

    Slow->>Kafka: poll (offset 3부터)
    Kafka-->>Slow: msg4, msg5 (2건)

    Note over Slow,Fast: fast-group: 5건<br/>slow-group: 2건 (이어서 읽기)<br/>속도 독립성 ✅
```

---

## KafkaPartitionOrderingTest

파티션 기반 순서 보장 — 같은 key → 같은 partition → 순서 보장.

### 같은 key의 메시지는 같은 파티션에 저장된다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka (3 partitions)

    Prod->>Kafka: key="order-1001", msg1
    Prod->>Kafka: key="order-1001", msg2
    Prod->>Kafka: key="order-1001", msg3
    Prod->>Kafka: key="order-1001", msg4
    Prod->>Kafka: key="order-1001", msg5

    Note over Kafka: 5건 모두 같은 파티션<br/>(파티션 set size = 1) ✅
```

### 같은 파티션의 메시지는 발행 순서대로 소비된다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka (3 partitions)
    participant Cons as Consumer

    Prod->>Kafka: key="order-1001", "생성"
    Prod->>Kafka: key="order-1001", "결제"
    Prod->>Kafka: key="order-1001", "배송"

    Cons->>Kafka: poll
    Note over Cons: 수신 순서:<br/>1. "생성"<br/>2. "결제"<br/>3. "배송"<br/>같은 key → 같은 파티션<br/>→ 순서 보장 ✅
```

### 다른 key의 메시지는 다른 파티션으로 분배될 수 있다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka (3 partitions)

    Prod->>Kafka: key="order-0" ~ "order-9" (10건)

    Note over Kafka: Partition 0: [order-X, order-Y, ...]
    Note over Kafka: Partition 1: [order-Z, order-W, ...]
    Note over Kafka: Partition 2: [order-A, order-B, ...]

    Note over Kafka: 사용된 파티션 >= 2개 ✅<br/>다른 key는 다른 파티션으로<br/>분배될 수 있다
```

---

## TransactionalOutboxCompletionTest

Step 3의 Event Store + Kafka Relay = Transactional Outbox Pattern 완성.

```
Step 3이 해결한 것                      Step 5가 해결한 것
─────────────────────                  ─────────────────────
"이벤트가 유실되면 안 된다"                "이벤트가 프로세스 밖으로 나가야 한다"

도메인 저장 + 이벤트 기록                  Event Store → Kafka 릴레이
= 같은 TX (원자성)                       = 보존 + 비동기 전달

       합치면
       ──────
       Transactional Outbox Pattern
       "원자적으로 기록하고, 안전하게 전달한다"
```

### 주문 저장과 이벤트 기록이 하나의 트랜잭션으로 묶인다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant OS as OrderService
    participant DB as DB
    participant OB as outbox_events

    Note over OS: TX BEGIN
    OS->>DB: INSERT 주문
    OS->>OB: INSERT 이벤트 (PENDING)
    Note over OS: TX COMMIT

    Test->>DB: 주문 조회 → 존재 ✅
    Test->>OB: 이벤트 조회 → PENDING, ORDER_CREATED ✅
```

### 릴레이가 PENDING 이벤트를 Kafka로 발행하고 SENT로 변경한다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant Relay as Relay (Scheduler)
    participant OB as outbox_events
    participant Kafka as Kafka
    participant Cons as Consumer

    rect rgb(230, 245, 230)
        Note over OB: Step 3 영역: 원자성 확보
        Note over OB: PENDING 이벤트 1건
    end

    rect rgb(230, 235, 250)
        Note over Relay,Kafka: Step 5 영역: 외부 전달
        Relay->>OB: SELECT WHERE status = 'PENDING'
        Relay->>Kafka: PUBLISH (key=orderId, value=payload)
        Relay->>OB: UPDATE status = 'SENT'
    end

    Test->>OB: findByStatus(PENDING) → 0건
    Test->>OB: findByStatus(SENT) → 1건 ✅

    Cons->>Kafka: poll
    Note over Cons: key = orderId ✅<br/>value에 "노트북" 포함 ✅
```

### Kafka 발행 실패 시 이벤트는 여전히 PENDING 상태를 유지한다

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant Relay as Relay
    participant OB as outbox_events
    participant Kafka as Kafka<br/>(연결 불가)

    Note over OB: PENDING 1건

    Relay->>OB: SELECT WHERE status = 'PENDING'
    Relay->>Kafka: PUBLISH 시도
    Kafka--xRelay: 연결 실패!

    Test->>OB: findByStatus(PENDING) → 1건 (그대로) ✅
    Test->>OB: findByStatus(SENT) → 0건

    Note over Test: 다음 릴레이 실행 시<br/>재시도 가능 (PENDING 유지)
```

---

## 학습 포인트

이 Step을 마치면 다음 질문에 답할 수 있어야 합니다:

- [ ] Redis Pub/Sub에서 구독자가 없으면 메시지가 유실되는데, Kafka에서는 왜 보존되는가?
- [ ] Consumer Group A가 느려도 Group B에 영향이 없는 이유는?
- [ ] 같은 key의 메시지가 같은 파티션에 들어가면 왜 순서가 보장되는가?
- [ ] Step 3의 Event Store + 이 Step의 Kafka Relay = Transactional Outbox. 각각이 어떤 문제를 해결하는가?
- [ ] Kafka 발행이 실패하면 이벤트 상태가 왜 PENDING으로 남아야 하는가?

> `TransactionalOutboxCompletionTest`에서 Step 3의 원자성 테스트와 이 Step의 릴레이 테스트가 어떻게 연결되는지 비교해 보세요.

---

## Testcontainer

```
KafkaContainer("confluentinc/cp-kafka:7.6.0") - KRaft mode (ZooKeeper 불필요)
```

## 중복이 왜 발생하는가 -> Step 6

Kafka는 At Least Once 전달이 기본이다.
Consumer가 메시지를 처리한 뒤 offset을 커밋하기 직전에 죽으면,
재시작 시 같은 메시지를 다시 읽게 된다.
-> 포인트가 2번 적립되거나 쿠폰이 2번 발급된다.
