# 용어 정리 (Glossary)

> 이 lab에서 사용하는 핵심 용어를 등장 순서대로 정리합니다.
> 각 용어 옆의 Step 번호는 해당 개념이 처음 등장하거나 핵심적으로 다뤄지는 위치입니다.

---

## Step 0 — Command vs Event

| 용어 | 설명 |
|------|------|
| **Command** | "~해라" — 아직 일어나지 않은 일에 대한 1:1 지시. 실패할 수 있고, 발신자가 결과를 처리해야 한다. 예: `IssueCouponCommand` |
| **Event** | "~되었다" — 이미 확정된 사실에 대한 1:N 통지. 발행자는 누가 듣는지 모른다. 예: `OrderCreatedEvent` |

---

## Step 1 — Application Event

| 용어 | 설명 |
|------|------|
| **ApplicationEventPublisher** | Spring이 제공하는 프로세스 내 이벤트 발행 인터페이스. `publish(event)`로 등록된 리스너에 이벤트를 전달한다. |
| **@EventListener** | Spring 이벤트를 수신하는 메서드에 붙이는 어노테이션. 발행자와 같은 스레드, 같은 트랜잭션에서 동기적으로 실행된다. |
| **결합도 (Coupling)** | 컴포넌트 간 의존 관계의 강도. 직접 호출은 높은 결합도, 이벤트 방식은 낮은 결합도. |
| **OCP (Open-Closed Principle)** | 확장에는 열려 있고, 수정에는 닫혀 있어야 한다. 이벤트 방식에서는 리스너 추가 시 발행자를 수정하지 않는다. |

---

## Step 2 — Transactional Event

| 용어 | 설명 |
|------|------|
| **@TransactionalEventListener** | 트랜잭션 상태에 따라 실행 타이밍을 제어하는 리스너. `AFTER_COMMIT`, `AFTER_ROLLBACK` 등의 phase를 지정할 수 있다. |
| **AFTER_COMMIT** | 트랜잭션이 커밋된 후에만 리스너를 실행하는 phase. 커밋 전에 부수효과가 발생하는 위험을 방지한다. |
| **@Async** | Spring의 비동기 실행 어노테이션. 별도 스레드에서 실행되어 응답이 빨라지지만, 예외가 호출자에게 전파되지 않는다. |
| **Eventual Consistency** | 최종적 일관성. 데이터가 즉시 일관되지 않지만, 일정 시간이 지나면 일관된 상태에 도달한다. AFTER_COMMIT + @Async를 선택한 순간 이를 수용한 것이다. |
| **Strong Consistency** | 강한 일관성. 쓰기 직후 읽기에서 항상 최신 값을 보장한다. 같은 트랜잭션 안에서만 가능. |

---

## Step 3 — Event Store

| 용어 | 설명 |
|------|------|
| **Event Store** | 이벤트를 안전하게 전달하기 위해 DB에 저장하는 중간 저장소. Event Sourcing과는 다른 개념이다. |
| **Relay (릴레이)** | 스케줄러가 PENDING 상태의 이벤트를 주기적으로 조회하여 후속 처리하는 컴포넌트. |
| **PENDING / PROCESSED / SENT** | 이벤트의 생명주기 상태. PENDING(대기) → PROCESSED(처리 완료) 또는 SENT(발행 완료). |
| **원자성 (Atomicity)** | 도메인 저장과 이벤트 기록이 하나의 트랜잭션으로 묶여, 둘 다 성공하거나 둘 다 실패하는 것. |
| **Event Sourcing** | 이벤트를 상태의 원본(Source of Truth)으로 사용하는 패턴. 이 lab의 Event Store와는 목적이 다르다. |

---

## Step 4 — Redis Pub/Sub

| 용어 | 설명 |
|------|------|
| **Pub/Sub (Publish/Subscribe)** | 발행자가 채널에 메시지를 보내고, 구독자가 채널을 구독하여 수신하는 메시징 패턴. |
| **Fire-and-forget** | 메시지를 보내고 결과를 확인하지 않는 방식. Redis Pub/Sub의 기본 동작. 구독자가 없으면 메시지는 사라진다. |
| **Fan-Out** | 하나의 메시지를 모든 구독자에게 브로드캐스트하는 패턴. 서로 다른 관심사가 같은 이벤트를 각자 처리할 때 적합. |
| **Competing Consumers** | 같은 관심사의 여러 인스턴스가 메시지를 나눠서 처리하는 부하 분산 패턴. Redis Pub/Sub으로는 불가능, Kafka Consumer Group으로 가능. |
| **Backpressure** | Producer가 Consumer보다 빠를 때 발생하는 압력. Redis Pub/Sub에서는 처리 못한 메시지가 유실되고, Kafka에서는 lag으로 쌓인다. |

---

## Step 5 — Kafka

| 용어 | 설명 |
|------|------|
| **Topic** | Kafka에서 메시지가 발행되는 논리적 채널. 하나의 토픽은 여러 파티션으로 구성된다. |
| **Partition** | 토픽의 물리적 분할 단위. 파티션 내에서는 메시지 순서가 보장된다. |
| **Offset** | 파티션 내 메시지의 순번. Consumer는 자신의 offset을 관리하며 이어 읽기가 가능하다. |
| **Consumer Group** | 같은 토픽을 구독하는 Consumer의 논리적 그룹. 그룹 간은 독립(Fan-Out), 그룹 내는 부하 분산(Competing Consumers). |
| **Append-only Log** | Kafka의 저장 모델. 메시지는 로그 끝에 추가만 되며, 수정이나 삭제가 없다. |
| **Key-Partition Mapping** | 같은 key의 메시지는 같은 파티션에 저장된다. 이를 통해 특정 엔티티의 이벤트 순서를 보장한다. |
| **Transactional Outbox Pattern** | 도메인 변경과 이벤트 기록을 같은 TX로 묶고(Step 3), 릴레이가 외부로 전달하는(Step 5) 패턴. 원자성과 전달 보장을 동시에 확보한다. |
| **At Least Once** | 메시지가 최소 한 번은 전달되는 보장. 중복은 발생할 수 있다. Kafka의 기본 전달 보장. |

---

## Step 6 — Idempotent Consumer

| 용어 | 설명 |
|------|------|
| **멱등성 (Idempotency)** | 같은 연산을 여러 번 수행해도 결과가 한 번 수행한 것과 같은 성질. At Least Once 환경에서 필수. |
| **event_handled 테이블** | 처리된 이벤트 ID를 기록하여 중복을 감지하는 범용 멱등 패턴. |
| **Upsert** | INSERT or UPDATE. 존재하면 갱신, 없으면 삽입. 집계성 데이터(조회수, 좋아요수)에 적합한 멱등 패턴. |
| **Version 비교** | 메시지의 version을 현재 데이터와 비교하여, 더 높은 version만 반영하는 패턴. 중복뿐 아니라 순서 역전까지 방어한다. |
| **Poison Pill** | 파싱 불가능하거나 처리할 수 없는 비정상 메시지. 방치하면 Consumer를 영구적으로 막는다. |
| **DLQ (Dead Letter Queue)** | 처리 실패한 메시지를 격리하는 별도 토픽. 정상 메시지 처리를 보호하며, 나중에 수동 확인/재처리할 수 있다. |
