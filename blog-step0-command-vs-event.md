# Command와 Event — "해라"와 "됐다"의 차이

## 시작하기 전에

트랜잭션으로 잘 돌아가는 코드가 있다. 주문이 들어오면 재고를 차감하고, 쿠폰을 적용하고, 결제를 요청하고, 포인트를 적립하고, 알림을 보낸다. 전부 하나의 메서드 안에서 순서대로 처리한다.

이게 왜 문제가 될까?

당장은 문제가 없다. 서버가 하나고 DB가 하나면 트랜잭션으로 묶으면 그만이다. 문제는 서비스가 쪼개질 때 생긴다. 주문 서버와 포인트 서버가 분리되면, 두 DB를 하나의 트랜잭션으로 묶을 수가 없다.

```
주문 서버 DB → 커밋 성공
포인트 서버 DB → 네트워크 끊김 → 실패
```

그때 "이건 반드시 같이 성공해야 하는가, 아니면 나중에 따로 처리해도 되는가"를 구분하는 기준이 필요해진다. 그 기준이 **Command와 Event**다.

> **여기서 드는 의문** — Command/Event 패턴은 Kafka 같은 메시징 시스템을 쓰기 위한 준비인가?
>
> 꼭 그렇지는 않다. Command/Event 구분은 기술보다 앞선 설계 결정이다. Kafka든, RabbitMQ든, 단순 DB 이벤트든 — 무엇을 같이 묶고 무엇을 분리할지 판단하는 기준이 먼저다. 기술은 그 다음 문제다.

---

## "해라"와 "됐다"

주문 생성 플로우를 말투에 주목해서 읽어보자.

```
"재고를 차감해라"
"쿠폰을 사용해라"
"결제를 요청해라"
"주문을 저장해라"
"포인트를 적립해라"
"알림을 발송해라"
```

전부 **"해라"**다. 아직 안 일어난 일이고, 실패할 수 있다.

주문 저장이 완료된 후에는 말투가 바뀐다.

```
"주문이 생성되었다"
```

**"됐다"**다. 이미 일어난 사실이고, 되돌릴 수 없다.

- **Command** = "해라" — 아직 일어나지 않은 의도. 실패할 수 있다.
- **Event** = "됐다" — 이미 확정된 사실. 발행 자체는 항상 성공한다.

---

## 코드로 보는 차이

### Command

```java
// "쿠폰을 발급해라" — 아직 일어나지 않은 일
public record IssueCouponCommand(
        String commandId,
        String userId,
        String couponType,
        Instant requestedAt   // "요청한 시각" — 아직 실행 전
) {
    public IssueCouponCommand(String userId, String couponType) {
        this(UUID.randomUUID().toString(), userId, couponType, Instant.now());
    }
}
```

Command를 처리하는 핸들러는 실패할 수 있다.

```java
public Coupon handle(IssueCouponCommand command) {
    int remaining = stock.getOrDefault(command.couponType(), 0);
    if (remaining <= 0) {
        throw new IllegalStateException("재고 소진: " + command.couponType());
    }
    stock.put(command.couponType(), remaining - 1);
    return new Coupon(command.userId(), command.couponType());
}
```

재고가 없으면 예외가 던져진다. **Command는 실패할 수 있고, 발신자가 그 결과를 받아서 처리해야 한다.**

### Event

```java
// "주문이 생성되었다" — 이미 확정된 사실
public record OrderCreatedEvent(
        String eventId,
        String orderId,
        String userId,
        long amount,
        Instant occurredAt    // "사실이 확정된 시각" — 이미 일어난 후
) {
    public static OrderCreatedEvent of(String orderId, String userId, long amount) {
        return new OrderCreatedEvent(
                UUID.randomUUID().toString(), orderId, userId, amount, Instant.now()
        );
    }
}
```

`.of()`는 항상 성공한다. **Event는 이미 일어난 사실의 기록이기 때문에, 발행 자체가 실패하지 않는다.** 나중에 리스너가 처리에 실패하더라도 그건 리스너의 문제이지 발행자의 문제가 아니다.

### 타임스탬프의 의미 차이

둘 다 타임스탬프가 있지만 의미가 다르다.

| | Command | Event |
|---|---|---|
| 필드명 | `requestedAt` | `occurredAt` |
| 의미 | 언제 요청했는가 | 언제 확정됐는가 |
| 용도 | 타임아웃, 감사 추적 | 순서 판단의 기준 |

Event를 DB에 저장하거나 Kafka에서 재처리할 때, "이 이벤트가 저 이벤트보다 먼저 일어났는가?"를 `occurredAt`으로 판단한다. Command의 `requestedAt`과는 목적이 다르다.

---

## 무엇을 Command로, 무엇을 Event로?

주문 플로우로 돌아가보자.

```
1. 재고 차감
2. 쿠폰 사용
3. 결제 요청
4. 주문 저장
5. 포인트 적립
6. 알림 발송
```

판단 기준은 하나다: **"주문이 생성됐다"는 사실이 확정되기 전에 반드시 성공해야 하는가?**

- **1~4 → Command 영역** — 실패하면 주문 자체가 성립하지 않는다.
- **5~6 → Event 영역** — 주문이 확정된 후에 처리해도 된다.

> **여기서 드는 의문** — 쿠폰 사용(2번)은 정말 반드시 먼저여야 할까? 쿠폰이 없어도 주문은 성립하지 않나?
>
> 쿠폰 적용 여부가 결제 금액을 바꾸기 때문에 먼저 처리해야 한다. "쿠폰이 있냐 없냐"의 문제가 아니라, "쿠폰을 적용한 금액으로 결제할 것인가"가 주문의 핵심 조건이기 때문이다. 비즈니스 로직이 의존 관계를 결정한다.

---

## Event 처리가 실패하면?

포인트 적립(5번)이 실패했다고 주문을 취소해야 할까?

그렇지 않다. **나중에 다시 시도하면 된다.**

```
Command 실패 → 전체 롤백 (주문 자체가 안 됨)
Event 처리 실패 → 주문은 유지, 재시도로 해결
```

> **여기서 드는 의문** — Event 방식이 아니라 Command 방식으로 포인트와 알림을 처리했다면 어떻게 됐을까?
>
> ```
> 주문 저장 완료
> → "포인트 적립해라" 호출 (Command)
> → "알림 보내라" 호출 (Command)
> ```
>
> 포인트 서비스가 죽어있으면 호출 자체가 실패한다. 주문을 취소해야 하나? 무시해야 하나? 호출하는 쪽이 모든 수신자를 알고 있어야 하고, 수신자가 늘어날수록 코드도 계속 바뀌어야 한다.
>
> Event 방식은 다르다.
>
> ```
> 주문 저장 완료
> → "주문이 생성됐다" 발행 (Event)
> → 포인트 서비스가 알아서 들음
> → 알림 서비스가 알아서 들음
> ```
>
> 발행자는 누가 듣는지 몰라도 된다. 수신자가 늘어나도 발행자 코드는 바뀌지 않는다.

이 "나중에 다시 시도"를 안전하게 구현하는 것이 **Outbox 패턴**이고, 중복 수신 문제를 처리하는 것이 **Idempotent Consumer**다. 둘 다 Event 기반 설계에서 반드시 만나게 되는 패턴이다.

---

## Before Commit vs After Commit

Event를 언제 발행해야 할까?

트랜잭션이 커밋되기 **전**에 발행하면 위험하다. DB는 롤백됐는데 Event는 이미 나간 상황이 생길 수 있다. 그래서 커밋이 확정된 **후**에 발행하는 것이 원칙이다.

Spring에서는 `@TransactionalEventListener`가 이 역할을 한다.

> **여기서 드는 의문** — Before commit이어도 `@Aspect`나 `@Transactional`로 관심사를 분리할 수 있지 않나?
>
> 맞다. 코드를 분리하는 방법은 여러 가지다. `@Transactional`도, `@Aspect`도 핵심 로직과 부가 로직을 코드 레벨에서 분리해준다. 같은 트랜잭션 안에서도 분리는 가능하다.
>
> 하지만 핵심은 **어떻게 분리할지**가 아니라 **이 로직이 트랜잭션 성공에 의존해야 하는가**를 먼저 판단하는 것이다.
>
> - 의존해야 한다 → Before commit (같은 트랜잭션)
> - 의존 안 해도 된다 → After commit (Event 발행)
>
> 코드 분리 방법보다 본질적인 판단이 먼저다.

---

## 정리

| | Command | Event |
|---|---|---|
| 의미 | 아직 일어나지 않은 의도 | 이미 확정된 사실 |
| 실패 가능성 | 있다 | 발행 자체는 없다 |
| 발신자 | 결과를 받아야 한다 | 누가 듣는지 몰라도 된다 |
| 실패 시 대응 | 롤백 | 재시도 |
| 타임스탬프 의미 | 요청 시각 | 확정 시각 |

Command와 Event를 구분하는 것은 Kafka나 특정 기술을 쓰기 위한 준비가 아니다. **무엇이 핵심 트랜잭션이고 무엇이 부가 처리인지를 설계 단계에서 명확히 하는 것**이다. 기술은 그 다음 문제다.

---

## 다음 단계

- **Step 1** — 동기 처리와 비동기 처리의 차이
- **Step 3** — Event Store: 이벤트를 DB에 안전하게 저장하기
- **Step 6** — Outbox 패턴: "나중에 재시도"를 안전하게 구현하기
- **Step 7** — Idempotent Consumer: 중복 수신 처리