# 포인트는 왜 안 쌓였을까 — @TransactionalEventListener 함정 파헤치기

## 들어가며

주문 서비스를 개발하면서 이런 코드를 마주치거나 직접 작성한 경험이 있을 것이다.

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
void onOrderCreated(OrderCreatedEvent event) {
    pointRepository.save(new Point(event.userId(), event.amount()));
}
```

커밋이 끝난 뒤에 포인트를 적립한다. 트랜잭션이 성공했을 때만 실행되니까 언뜻 완벽해 보인다.

정말 그런지 테스트로 직접 확인해봤다.

```java
@Test
void AFTER_COMMIT_시점에_Spring_TX_플래그는_아직_true지만_JPA_세션은_이미_커밋됐다() {
    orderService.createOrder("user-tx-state-check", 50_000L);

    // Spring TX 플래그: 아직 true
    assertThat(afterCommitDbSaveListener.isTxActiveInHandler()).isTrue();

    // 그럼에도 flush()에서 TransactionRequiredException 발생
    assertThat(afterCommitDbSaveListener.getCapturedException())
            .isInstanceOf(TransactionRequiredException.class);
}
```

테스트를 실행하면 실제로 이 예외가 캡처된다.

```
jakarta.persistence.TransactionRequiredException:
No EntityManager with actual transaction available for current thread
```

`pointRepository.save()`도 내부적으로 `persist()` → `flush()` 흐름을 타기 때문에 같은 예외가 발생한다.
왜 이런 일이 생기는지, 어떻게 고쳐야 하는지를 하나씩 파헤친다.

---

## @TransactionalEventListener란?

Spring에서 도메인 이벤트를 처리할 때 가장 먼저 떠오르는 선택지는 `@EventListener`다.
하지만 이벤트가 트랜잭션과 결합된 순간, `@EventListener`는 예상치 못한 문제를 일으킨다.

```java
@EventListener
void onOrderCreated(OrderCreatedEvent event) {
    pointRepository.save(new Point(event.userId(), event.amount()));
}
```

위 코드는 주문 트랜잭션이 **롤백되더라도 포인트가 적립될 수 있다.**
`@EventListener`는 이벤트가 발행되는 즉시 실행되기 때문에, 트랜잭션의 커밋 여부와 관계없이 리스너가 먼저 동작한다.

```java
assertThat(orderRepository.count()).isEqualTo(0); // 주문: 롤백됨
assertThat(pointRepository.count()).isEqualTo(1); // 포인트: 이미 적립됨 → 불일치!
```

이 문제를 해결하기 위해 등장한 것이 `@TransactionalEventListener`다.
트랜잭션의 특정 시점에 맞춰 리스너 실행을 바인딩할 수 있다.

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
void onOrderCreated(OrderCreatedEvent event) { ... }
```

`phase` 옵션으로 실행 시점을 제어할 수 있다.

| phase | 실행 시점 |
|---|---|
| `BEFORE_COMMIT` | 트랜잭션 커밋 직전 |
| `AFTER_COMMIT` | 트랜잭션 커밋 직후 (기본값) |
| `AFTER_ROLLBACK` | 트랜잭션 롤백 후 |
| `AFTER_COMPLETION` | 커밋/롤백 관계없이 완료 후 |

### TransactionSynchronization 수명주기

`@TransactionalEventListener`는 내부적으로 Spring의 `TransactionSynchronization`을 등록하는 방식으로 동작한다.
트랜잭션 커밋 흐름을 따라가보면 리스너가 어느 시점에 실행되는지 명확히 보인다.

```
AbstractPlatformTransactionManager.commit()
  ├── doCommit()                  ← 실제 DB commit
  ├── triggerAfterCommit()        ← AFTER_COMMIT 리스너 실행
  ├── triggerAfterCompletion()    ← AFTER_COMPLETION 리스너 실행
  └── cleanupAfterCompletion()    ← 커넥션 반납, TX 리소스 정리
```

**리스너가 실행되는 시점에 커넥션이 아직 반납되지 않았다.**
`cleanupAfterCompletion()`은 리스너가 모두 실행된 이후에 호출된다.
이 사실이 이후에 나올 여러 함정의 근본 원인이 된다.

<!-- 그림: commit() 흐름 타임라인 -->

---

## 함정 1, 2 — @Transactional 하나면 될 것 같았다

`@EventListener`의 문제를 발견하고 나면, 자연스럽게 `@TransactionalEventListener`로 교체한다.

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
void onOrderCreated(OrderCreatedEvent event) {
    pointRepository.save(new Point(event.userId(), event.amount()));
}
```

커밋 이후에 실행되니까 이제 괜찮을 것 같다. 그런데 실행하면 예외가 터진다.

```
jakarta.persistence.TransactionRequiredException:
No EntityManager with actual transaction available for current thread
```

### 왜 실패하는가

직관과 다른 사실부터 확인하고 시작하는 게 좋다.
`AFTER_COMMIT` 시점에 Spring의 `isActualTransactionActive()`를 찍어보면 이렇다.

```java
@Test
void AFTER_COMMIT_시점에_Spring_TX_플래그는_아직_true지만_JPA_세션은_이미_커밋됐다() {
    orderService.createOrder("user-tx-state-check", 50_000L);

    // Spring TX 플래그: 아직 true
    assertThat(afterCommitDbSaveListener.isTxActiveInHandler()).isTrue();

    // 그럼에도 flush()에서 TransactionRequiredException 발생
    assertThat(afterCommitDbSaveListener.getCapturedException())
            .isInstanceOf(TransactionRequiredException.class);
}
```

**Spring의 TX 플래그는 아직 `true`다.** 그런데도 예외가 발생한다.

커밋 흐름을 다시 보면 이유가 보인다.

```
AbstractPlatformTransactionManager.commit()
  ├── doCommit()                  ← 실제 DB commit
  ├── triggerAfterCommit()        ← AFTER_COMMIT 리스너 실행 ← 지금 여기
  ├── triggerAfterCompletion()
  └── cleanupAfterCompletion()    ← 여기서 TX 플래그가 false로 바뀜
```

`cleanupAfterCompletion()`이 아직 실행되지 않았으니 Spring TX 플래그는 `true`다.
하지만 `doCommit()`은 이미 끝났다.

**Spring TX 플래그와 JPA 세션 상태는 별개로 움직인다.**

<!-- 그림: Spring TX 플래그 vs JPA 세션 상태 타임라인 -->

`pointRepository.save()`는 내부적으로 `persist()` → `flush()` 순서로 동작한다.

- `persist()` — 1차 캐시에 객체를 올리는 작업. TX가 없어도 문제없다.
- `flush()` — 1차 캐시를 실제 DB에 SQL로 내보내는 작업. JPA 세션이 이미 커밋된 상태임을 감지하고 예외를 던진다.

```java
@Test
void AFTER_COMMIT_리스너에서_DB_저장하면_TransactionRequiredException_발생() {
    orderService.createOrder("user-no-tx", 50_000L);

    assertThat(afterCommitDbSaveListener.getCapturedException())
            .isInstanceOf(TransactionRequiredException.class);
    assertThat(pointRepository.findByUserId("user-no-tx")).isEmpty();
}
```

주문은 커밋됐지만 포인트 저장은 실패한다. `@EventListener` 문제와 반대 방향의 불일치가 생긴 것이다.

### 두 번째 시도 — REQUIRES_NEW를 붙이면 되지 않나?

"새 트랜잭션을 강제로 열면 되지 않나?" 자연스러운 생각이다.
같은 클래스 안에 메서드를 추가했다.

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
void onOrderCreated(OrderCreatedEvent event) {
    this.savePoint(event.userId(), event.amount()); // 같은 클래스 내 호출
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
void savePoint(String userId, long amount) {
    pointRepository.save(new Point(userId, amount));
}
```

`REQUIRES_NEW`를 붙였으니 새 트랜잭션이 열릴 것 같다. 하지만 결과는 동일하다. 같은 예외가 발생한다.

### 왜 또 실패하는가 — self-invocation

Spring의 `@Transactional`은 프록시 기반으로 동작한다.
외부에서 호출할 때는 반드시 프록시를 거치기 때문에 `@Transactional`이 동작한다.

```
외부 → 프록시.savePoint()
          ├── 트랜잭션 시작 (REQUIRES_NEW 적용)
          └── 실제객체.savePoint() 호출
```

하지만 `this.savePoint()`는 프록시를 거치지 않는다.

```
외부 → 프록시.onOrderCreated()
          └── 실제객체.onOrderCreated()
                └── this.savePoint()
                          ↑
                    this = 실제객체 (프록시 아님)
                    → @Transactional(REQUIRES_NEW) 무시됨
```

<!-- 그림: 프록시 경로 vs self-invocation 경로 비교 -->

`this`는 Spring이 만든 프록시가 아닌 실제 객체다.
`@Transactional`이 완전히 무시되어 함정 1과 동일한 상황이 된다.
이것을 **self-invocation**이라 한다.

### 세 번째 시도 — 별도 빈으로 분리

self-invocation을 피하려면 반드시 **별도 빈**으로 분리해야 한다.
외부 빈을 주입받아 호출하면 Spring 컨테이너가 프록시를 통해 호출하므로 `@Transactional`이 정상 동작한다.

```java
@Component
class PointSaveService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePoint(String userId, long amount) {
        pointRepository.save(new Point(userId, amount));
    }
}

@TransactionalEventListener(phase = AFTER_COMMIT)
void onOrderCreated(OrderCreatedEvent event) {
    pointSaveService.savePoint(event.userId(), event.amount()); // 별도 빈 호출
}
```

```
외부 → 프록시(OrderService).onOrderCreated()
          └── 실제객체.onOrderCreated()
                └── 프록시(PointSaveService).savePoint()  ← Spring 프록시를 거침
                          ├── REQUIRES_NEW로 새 TX 생성
                          └── 실제객체.savePoint() 호출
```

```java
@Test
void AFTER_COMMIT_리스너에서_별도_빈의_REQUIRES_NEW로_새_TX를_열면_DB_저장_가능() {
    orderService.createOrder("user-trap-new", 50_000L);

    assertThat(pointRepository.findByUserId("user-trap-new")).isPresent();
}
```

이제 포인트가 정상적으로 저장된다. 드디어 해결된 것 같다.
하지만 `REQUIRES_NEW` 자체에도 숨겨진 함정이 있다. 다음 섹션에서 살펴본다.

---

## 함정 3 — REQUIRES_NEW는 "독립적"이지 않다

별도 빈으로 분리하고 `REQUIRES_NEW`를 사용하면 문제가 해결된 것처럼 보인다.
하지만 여기서 많은 개발자들이 `REQUIRES_NEW`에 대한 중요한 오해를 갖게 된다.

> "REQUIRES_NEW는 독립적인 트랜잭션을 생성하니까, 서로 영향을 주지 않는다."

과연 그럴까?

### "독립적"이라는 단어의 함정

독립적이라는 말은 **다른 것으로부터 영향을 받지도, 주지도 않는 것**을 의미한다.
이 기준으로 `REQUIRES_NEW`를 바라보면 실제로는 독립적이지 않다.

`REQUIRES_NEW`가 호출되면 Spring 내부에서 이런 일이 일어난다.

```
Thread A
  TX 1 실행 중
    └── REQUIRES_NEW 호출
          TX 1 suspend (Thread-local에서 꺼내 잠시 보관)
          Thread-local ← TX 2 세팅 (새 커넥션)
          TX 2 실행
          TX 2 완료
          Thread-local ← TX 1 복원
  TX 1 재개
```

`REQUIRES_NEW`는 **같은 스레드에서 순차적으로 동작**한다.
TX 1을 잠시 멈추고(suspend), TX 2를 실행한 뒤, 다시 TX 1을 재개(resume)하는 구조다.

결국 `REQUIRES_NEW`는 독립적인 것이 아니라 **별도의 트랜잭션을 생성**하는 것이다.

| 구분 | 독립적? |
|---|---|
| DB 커밋/롤백 | ✅ 별도로 동작 |
| 예외 전파 | ❌ 콜스택 공유 |
| 스레드 | ❌ 동일 스레드 |
| 커넥션 풀 | ❌ 동시 점유 |

### 예외 전파는 트랜잭션과 무관하다

`REQUIRES_NEW`로 별도 트랜잭션을 생성했더라도, **Java의 예외 전파는 트랜잭션과 완전히 별개**다.
예외는 트랜잭션 경계가 아닌 JVM 콜스택을 따라 전파된다.

```
ParentService.createOrder()    ← TX 1 시작
  └── ChildService.save()      ← TX 2 시작 (REQUIRES_NEW)
        └── 예외 발생!
            TX 2 롤백 ✅
            예외 전파 ↑
  ← 예외 받음, 처리 안 함
  TX 1도 롤백 ❌
```

TX 2는 TX 1과 무관하게 롤백됐다.
하지만 예외가 ParentService까지 전파됐고, ParentService가 잡지 않으니 TX 1도 롤백된 것이다.

```java
assertThatThrownBy(() -> parentService.createWithoutCatch(userId))
        .isInstanceOf(RuntimeException.class);

assertThat(orderRepository.count()).isEqualTo(0); // Parent TX도 롤백됨!
assertThat(pointRepository.count()).isEqualTo(0); // Child TX 롤백
```

예외를 catch하면 어떻게 될까?

```java
@Transactional
public void createWithCatch(String userId) {
    orderRepository.save(new Order(userId));
    try {
        pointSaveService.saveWithException(userId);
    } catch (RuntimeException e) {
        // 예외를 잡았으므로 Parent TX는 정상 커밋
    }
}
```

```java
assertThat(orderRepository.count()).isEqualTo(1); // Parent TX: 정상 커밋
assertThat(pointRepository.count()).isEqualTo(0); // Child TX: 롤백
```

예외를 catch해야 비로소 TX 1과 TX 2가 진정으로 독립적으로 동작한다.
`REQUIRES_NEW`만으로는 충분하지 않다. **예외 처리까지 함께 고려해야 한다.**

### AFTER_COMMIT에서의 불일치

이 문제는 `AFTER_COMMIT` 리스너에서도 동일하게 발생한다.

```
주문 TX → 커밋 완료 ✅
  └── AFTER_COMMIT 리스너 실행
        └── pointSaveService.savePoint() (REQUIRES_NEW)
              └── 예외 발생!
                  포인트 TX 롤백 ✅
                  예외 전파 ↑
        ← 리스너가 예외를 받음
```

주문은 이미 커밋됐으니 롤백할 수 없다.
포인트는 예외로 롤백됐다.
결과적으로 **주문은 됐는데 포인트는 없는 불일치 상태**가 된다.

```java
assertThat(orderRepository.count()).isEqualTo(1); // 주문: 저장됨
assertThat(pointRepository.count()).isEqualTo(0); // 포인트: 롤백 → 불일치!
```

---

## 함정 4 — REQUIRES_NEW는 커넥션을 2개 점유한다

`REQUIRES_NEW`가 예외 전파 문제를 제대로 다루더라도, 또 다른 위험이 남아 있다.
바로 **커넥션 풀 고갈**이다.

### 왜 커넥션이 2개인가

앞서 본 커밋 흐름을 다시 보자.

```
AbstractPlatformTransactionManager.commit()
  ├── doCommit()
  ├── triggerAfterCommit()     ← AFTER_COMMIT 리스너 실행
  ├── triggerAfterCompletion()
  └── cleanupAfterCompletion() ← 커넥션 반납
```

`AFTER_COMMIT` 리스너는 `cleanupAfterCompletion()` **이전**에 실행된다.
즉, 리스너가 실행되는 시점에 **원래 TX의 커넥션이 아직 반납되지 않은 상태**다.

여기서 `REQUIRES_NEW`를 열면 어떻게 될까?

```
[스레드 A] 커넥션 1 점유 중 (아직 반납 전)
              ↓ AFTER_COMMIT 리스너 실행
              ↓ REQUIRES_NEW 요청
[스레드 A] 커넥션 풀에서 커넥션 2 추가 획득
```

스레드 하나가 **커넥션을 2개 동시에 점유**하는 상황이 생긴다.

<!-- 그림: 커넥션 점유 타임라인 -->

### 실제 수치로 시뮬레이션해보면

HikariCP 기본 풀 사이즈는 10이다.

```
동시 요청 5개
  → 각 스레드: 커넥션 1 (주문 TX) + 커넥션 2 (REQUIRES_NEW)
  → 5 × 2 = 커넥션 10개 전부 소진
  → 6번째 요청: 커넥션 대기 → connectionTimeout(30초) 후 예외
```

풀 사이즈가 10인데 **동시 요청 5개**만 들어와도 한계에 도달한다.
평상시 모니터링에서는 절대 보이지 않다가, 특정 이벤트 순간에만 터지는 장애다.

> **위험 임계치 = 풀 사이즈 / 2**
> HikariCP 기본값(10) 기준, 동시 요청 5개부터 위험하다.

---

## 대안 비교 — 그럼 뭘 써야 하나

### @Async — 확률은 낮지만 근본적으로 같은 문제

`@Async`를 쓰면 별도 스레드에서 실행되니까 커넥션 문제가 해결될 것 같다.

```
[스레드 A] triggerAfterCommit() → @Async 호출 → cleanupAfterCompletion() → 커넥션 반납
[스레드 B]                                                               → 커넥션 새로 획득 → DB 저장
```

하지만 `@Async`는 실행 타이밍을 보장하지 않는다.
스레드 B가 스레드 A의 `cleanupAfterCompletion()` 이전에 스케줄링되면,
두 스레드가 동시에 커넥션을 점유하는 순간이 생긴다.
`REQUIRES_NEW`보다 확률은 낮지만, 근본적으로 같은 문제다.

### Outbox 패턴 — 커넥션 안전, 도메인 분리

Outbox 패턴은 이벤트를 **같은 트랜잭션 안에서 DB에 기록**해두고, 별도 프로세스가 처리하는 방식이다.

```java
@Transactional
public void createOrder(String userId) {
    orderRepository.save(order);
    outboxRepository.save(new OutboxEvent("ORDER_CREATED", event)); // 같은 TX
}
// 커밋 → 커넥션 반납

// 별도 스케줄러
outboxRepository.findUnprocessed()
    .forEach(event -> pointService.process(event)); // 완전히 독립된 커넥션
```

커넥션 겹침 자체가 없고, 주문 도메인과 포인트 도메인이 완전히 분리된다.

단, 트레이드오프가 있다.

- **결과적 일관성** — 포인트 적립이 즉시가 아니라 스케줄러 주기만큼 늦음
- **멱등성 처리 필요** — 같은 이벤트를 두 번 처리할 수 있음
- **인프라 복잡도** — 스케줄러 또는 Kafka 같은 별도 파이프라인 필요

### 선택 기준

| 방법 | 커넥션 안전성 | 즉시성 | 복잡도 |
|---|---|---|---|
| `REQUIRES_NEW` | ⚠️ 동시 2개 점유 | ✅ 즉시 | 낮음 |
| `@Async` | ⚠️ 낮은 확률로 위험 | ✅ 즉시 | 낮음 |
| Outbox 패턴 | ✅ 안전 | 🔄 결과적 일관성 | 높음 |

> "포인트 적립이 주문과 동시에 일어나야 하는 비즈니스인가, 결과적으로 일어나도 되는 비즈니스인가?"

이 질문에 답할 수 있다면 선택지가 좁혀진다.

---

## 마치며 — 하나의 원칙이 설계 전체를 관통한다

이 글에서 마주친 함정들은 각각 달라 보이지만, 사실 하나의 원칙으로 모두 설명된다.

> **`@TransactionalEventListener`는 트랜잭션이 끝난 시점에 실행된다. 하지만 '끝났다'는 것이 '모든 것이 정리됐다'를 의미하지 않는다.**

이 원칙을 알고 나면 각 함정의 이유가 자연스럽게 따라온다.

- `@EventListener`를 쓰면 안 되는 이유 → 트랜잭션 커밋 여부와 무관하게 즉시 실행되니까
- `AFTER_COMMIT`에서 저장이 실패하는 이유 → Spring TX 플래그는 아직 true지만 JPA 세션은 이미 커밋 완료 상태니까
- self-invocation이 안 되는 이유 → Spring의 프록시와 JVM의 콜스택은 별개로 동작하니까
- `REQUIRES_NEW`가 독립적이지 않은 이유 → TX 경계만 나눌 뿐, 예외 전파는 JVM 콜스택이 담당하니까
- `REQUIRES_NEW`가 커넥션 풀을 고갈시키는 이유 → 커밋은 끝났지만 커넥션 반납은 아직이니까

| 함정 | 원인 | 결과 |
|---|---|---|
| `@EventListener` 사용 | TX 커밋 여부와 무관하게 즉시 실행 | TX 롤백돼도 이벤트 처리됨 |
| `AFTER_COMMIT`에서 TX 없이 저장 | JPA 세션이 이미 커밋 완료 | `TransactionRequiredException` |
| self-invocation | 프록시 우회 | `@Transactional` 무시 |
| `REQUIRES_NEW` = 독립적 오해 | 예외는 콜스택을 탐 | Parent TX도 롤백 |
| `REQUIRES_NEW` 커넥션 위험 | 커넥션 반납 전 추가 점유 | 풀 사이즈 / 2 지점부터 고갈 |