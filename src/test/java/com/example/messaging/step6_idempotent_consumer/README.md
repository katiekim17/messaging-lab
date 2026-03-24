# Step 6 — Idempotent Consumer 학습 테스트

At Least Once 환경에서 중복 소비가 발생하는 문제를 체험한다.
세 가지 멱등 패턴을 구현하고 트레이드오프를 비교한다.
처리 불가능한 메시지(poison pill)를 DLQ로 격리하는 개념을 확인한다.

---

## DuplicateConsumptionProblemTest

멱등 처리 없이 같은 메시지를 2번 소비하면 데이터가 2번 반영되는 문제.

### 같은 메시지를 2번 소비하면 포인트가 2번 적립된다

```mermaid
sequenceDiagram
    participant Kafka as Kafka
    participant Cons as NaiveConsumer
    participant DB as PointAccount

    Kafka->>Cons: consume(eventId, userId, 100)
    Cons->>DB: balance += 100
    Note over DB: balance = 100

    Note over Cons: offset commit 직전에 크래시!<br/>재시작 후 같은 메시지 재전달

    Kafka->>Cons: consume(eventId, userId, 100)
    Cons->>DB: balance += 100
    Note over DB: balance = 200 ❌

    Note over DB: 기대값: 100<br/>실제값: 200 (중복 적립!)
```

---

## EventHandledIdempotencyTest

event_handled 테이블로 중복을 방어하는 패턴 — 범용, 어떤 도메인이든 적용 가능.

### event_handled 테이블에 이미 처리된 이벤트가 있으면 스킵한다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant EH as event_handled
    participant DB as PointAccount

    Note over Cons: 1차 소비 (evt-001)
    Cons->>EH: evt-001 존재 확인
    EH-->>Cons: 없음
    Cons->>DB: balance += 100
    Cons->>EH: evt-001 기록
    Note over Cons: return true

    Note over Cons: 2차 소비 (같은 evt-001)
    Cons->>EH: evt-001 존재 확인
    EH-->>Cons: 이미 있음
    Cons-->>Cons: SKIP
    Note over Cons: return false

    Note over DB: balance = 100 ✅ (중복 없음)
```

### 서로 다른 event_id의 메시지는 각각 정상 처리된다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant EH as event_handled
    participant DB as PointAccount

    Cons->>EH: evt-001 확인 → 없음
    Cons->>DB: balance += 100
    Cons->>EH: evt-001 기록

    Cons->>EH: evt-002 확인 → 없음
    Cons->>DB: balance += 200
    Cons->>EH: evt-002 기록

    Note over DB: balance = 300 ✅
    Note over EH: 2건 기록
```

---

## UpsertIdempotencyTest

Upsert 패턴 — 집계성 데이터(조회수, 좋아요수)에 적합한 멱등 패턴.

### 같은 이벤트를 2번 처리해도 upsert로 올바른 결과가 유지된다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant DB as ProductViewCount

    Cons->>DB: UPSERT (productId=1001, count=150)
    Note over DB: count = 150

    Cons->>DB: UPSERT (productId=1001, count=150)
    Note over DB: count = 150 (덮어쓰기)

    Note over DB: 몇 번 실행해도<br/>결과 동일 ✅ (150, not 300)
```

### upsert는 최신 값으로 덮어쓰므로 최종 상태가 보장된다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant DB as ProductViewCount

    Cons->>DB: UPSERT (productId=1001, count=100)
    Note over DB: count = 100

    Cons->>DB: UPSERT (productId=1001, count=150)
    Note over DB: count = 150 (최신 값으로 덮어쓰기)

    Note over DB: 최종 상태가 항상 보장 ✅
```

### 다른 상품의 이벤트는 각각 독립적으로 upsert된다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant DB as ProductViewCount

    Cons->>DB: UPSERT (productId=1001, count=150)
    Cons->>DB: UPSERT (productId=1002, count=80)

    Note over DB: productId=1001: count=150<br/>productId=1002: count=80<br/>독립적 집계 ✅
```

---

## VersionComparisonIdempotencyTest

version 비교 패턴 — 중복뿐 아니라 순서 역전까지 방어.

### version이 현재보다 높은 이벤트만 반영된다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant DB as StockRecord

    Cons->>DB: consume(1001, stock=100, v1)
    Note over DB: stock=100, version=1

    Cons->>DB: consume(1001, stock=80, v2)
    Note over DB: v2 > v1 → 반영
    Note over DB: stock=80, version=2 ✅
```

### version이 현재보다 낮거나 같은 이벤트는 무시된다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant DB as StockRecord

    Cons->>DB: consume(1001, stock=100, v1)
    Note over DB: stock=100, version=1

    Cons->>DB: consume(1001, stock=80, v3)
    Note over DB: v3 > v1 → 반영
    Note over DB: stock=80, version=3

    Cons->>DB: consume(1001, stock=90, v2)
    Note over DB: v2 < v3 → SKIP ❌
    Note over DB: stock=80 유지, version=3 ✅
```

### 순서가 역전된 이벤트 시퀀스에서 최종 상태가 올바르다

```mermaid
sequenceDiagram
    participant Cons as Consumer
    participant DB as StockRecord

    Cons->>DB: v1 도착 (stock=100)
    Note over DB: stock=100, version=1

    Cons->>DB: v3 먼저 도착 (stock=50)
    Note over DB: v3 > v1 → 반영<br/>stock=50, version=3

    Cons->>DB: v2 지연 도착 (stock=80)
    Note over DB: v2 < v3 → SKIP

    Cons->>DB: v4 도착 (stock=30)
    Note over DB: v4 > v3 → 반영<br/>stock=30, version=4

    Note over DB: 최종: stock=30, version=4 ✅<br/>순서 역전에도 올바른 결과
```

---

## PoisonPillAndDlqTest

Poison pill이 Consumer를 막는 문제와 DLQ 격리.
Spring Kafka의 ErrorHandler 대신 순수 Kafka API로 개념을 증명한다.

### 파싱 불가능한 메시지가 Consumer를 막는다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Kafka
    participant Cons as Consumer

    Prod->>Kafka: valid JSON
    Prod->>Kafka: "{{{{invalid json" (poison pill)
    Prod->>Kafka: valid JSON

    Cons->>Kafka: poll → 3건 수신

    Cons->>Cons: 1번째: 파싱 성공 ✅
    Cons->>Cons: 2번째: 파싱 실패 ❌
    Cons->>Cons: 3번째: 파싱 성공 ✅

    Note over Cons: failureCount = 1<br/>successCount = 2<br/>재시도하면 poison pill이<br/>Consumer를 영구적으로 막을 수 있다
```

### 처리 실패한 메시지를 DLQ 토픽으로 격리할 수 있다

```mermaid
sequenceDiagram
    participant Prod as Producer
    participant Kafka as Source Topic
    participant Cons as Consumer
    participant DLQ as DLQ Topic

    Prod->>Kafka: valid, "{{{{invalid", valid

    Cons->>Kafka: poll → 3건

    rect rgb(230, 255, 230)
        Cons->>Cons: 1번째: 성공 ✅
    end

    rect rgb(255, 230, 230)
        Cons->>Cons: 2번째: 파싱 실패
        Cons->>DLQ: 격리 (DLQ로 전송)
    end

    rect rgb(230, 255, 230)
        Cons->>Cons: 3번째: 성공 ✅
    end

    Note over Cons: successCount = 2

    participant DLQCons as DLQ Consumer
    DLQCons->>DLQ: poll
    Note over DLQCons: 1건 수신<br/>value = "{{{{invalid json"

    Note over DLQ: DLQ는 버리는 곳이 아니라<br/>나중에 수동 확인/재처리할 수 있는<br/>격리 공간
```

---

## 멱등 패턴 비교

| 패턴 | 적합한 상황 | 트레이드오프 |
|------|-----------|------------|
| event_handled(event_id PK) | 범용, 어떤 도메인이든 적용 가능 | 별도 테이블 필요, 조회 비용 |
| Upsert | 집계성 데이터 (조회수, 좋아요수) | 도메인 특성에 의존, 범용성 낮음 |
| version / updated_at 비교 | 순서 역전까지 방어해야 하는 경우 | 구현 복잡도 높음 |

---

## 학습 포인트

이 Step을 마치면 다음 질문에 답할 수 있어야 합니다:

- [ ] At Least Once 환경에서 중복이 발생하는 정확한 시나리오는? (offset commit 직전 크래시)
- [ ] event_handled 패턴은 범용적이지만 어떤 비용이 있는가?
- [ ] Upsert가 멱등한 이유는? 어떤 종류의 데이터에만 적합한가?
- [ ] version 비교 패턴은 중복뿐 아니라 무엇까지 방어하는가? (순서 역전)
- [ ] 세 패턴 중 현재 팀의 도메인에 가장 적합한 것은? 왜?
- [ ] poison pill 메시지를 DLQ로 격리하지 않으면 어떤 일이 발생하는가?

> `DuplicateConsumptionProblemTest`를 먼저 실행해서 포인트가 200이 되는 문제를 직접 확인한 뒤, 세 가지 패턴이 각각 어떻게 해결하는지 비교해 보세요.

---

## 이 Step이 도구에 종속되지 않는 이유

멱등 처리와 실패 격리는 Kafka든 RabbitMQ든 Redis Streams든 동일하게 필요한 패턴이다.
**"발행은 At Least Once, 소비는 멱등하게, 실패는 격리"** — 이것이 신뢰 가능한 이벤트 파이프라인의 최종 공식이다.
