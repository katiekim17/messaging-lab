# messaging-lab 작업 현황

## 프로젝트 개요
메시징 시스템 진화 과정을 단계별로 체험하는 학습 테스트 프로젝트.
Step 0(Command vs Event) ~ Step 6(Idempotent Consumer), 총 57개 테스트.
Java 21, Spring Boot 3.x, Testcontainers(Redis, Kafka).

## 참고한 프로젝트
- `~/resilience4j-lab` — 테스트 구조/패턴 참고 (printTestHeader, 흐름 Javadoc, @Nested 등)

## 커밋 컨벤션
`feat:`, `chore:`, `test:`, `docs:`, `refactor:`, `fix:` (Co-authored 제외)

---

## 완료된 작업

### 1. resilience4j-lab 패턴 적용 (3개 커밋)
- `feat:` MessagingTestBase, KafkaTestBase에 `printTestHeader` 추가 (테스트 실행 시 콘솔 구분선)
- `docs:` Step 2~6 멀티스텝 테스트 11개에 "흐름:" Javadoc 추가
- `refactor:` TransactionalEventListenerTest, VersionComparisonIdempotencyTest에 @Nested 그룹화

### 2. 버그/불일치 수정 (3개 커밋)
- `test:` Step 2에 AsyncEventLossTest 추가 — @Async 이벤트 유실 체험 (테스트 수 56 -> 57 정합)
- `fix:` EventualConsistencyTest 강화 — 즉시 조회 단계 추가, 타이밍 갭 미보장 이유 설명
- `docs:` Step 2 로컬 README에 AsyncEventLossTest 반영

### 3. 누락 개념 보강 (2개 커밋)
- `docs:` Step 4, 5 README에 Fan-Out vs Competing Consumers 개념 명시
- `docs:` Step 4 README에 Backpressure / Slow Consumer 개념 명시

### 4. 퀄리티 체크 수정 (1개 커밋)
- `fix:` EventualConsistencyTest 주석 명확화, AsyncEventLossTest Javadoc 엄밀성 강화
- `fix:` Step 1 로컬 README 누락 테스트 1개 추가

---

## 남은 작업 / 검토 필요 사항

### messaging-lab 범위 내 (판단 필요)
현재 추가 코드 작업은 없음. 아래는 조사 결과 나왔지만 kafka-lab 영역에 더 가깝다고 판단하여 보류한 항목:

- **Event Envelope (correlationId, causationId)** — 분산 트레이싱 영역, kafka-lab에 더 적합
- **Event Schema Evolution (하위 호환성)** — Schema Registry 등 kafka-lab 영역
- **Outbox Relay 순서 보장 문제** — relay 동시성은 Kafka 운영 설계 영역

### 기타 참고
- `@DisplayNameGeneration` 누락 의심 건 — JUnit 5가 superclass에서 탐색하므로 문제 아님 확인 완료
- 전체 테스트 57개, 모든 Step README 테스트 목록과 코드 정합 확인 완료
- AsyncTransactionalPointListener에 추가했던 delayMs 기능은 불필요하여 제거함 (원래 상태 유지)
