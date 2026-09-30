# CONVENTION.md

> 동네대전 프로젝트의 모든 서비스가 따르는 공통 개발 규칙이다.  
> 서비스 간 일관성을 유지하고, LLM 기반 서브 에이전트가 도메인별 개발 시 공통 기준으로 참조한다.

---

## 1. 프로젝트 구조

### 1-1. 전체 모노레포 구조

```
todongsan/
├── README.md
├── CONVENTION.md
├── ERD.md
├── API_SPEC.md
├── docker-compose.yml
├── api-gateway/
├── member-point-service/
├── battle-service/
├── market-service/
├── insight-reputation-service/
└── docs/
    ├── battle/
    │   ├── ERD.md
    │   └── API_SPEC.md
    ├── market/
    │   ├── ERD.md
    │   └── API_SPEC.md
    ├── member-point/
    │   ├── ERD.md
    │   └── API_SPEC.md
    └── insight-reputation/
        ├── ERD.md
        └── API_SPEC.md
```

### 1-2. 서비스별 패키지 구조

각 서비스는 아래 패키지 구조를 따른다.

```
com.todongsan.{service}
├── global
│   ├── config          # Spring 설정 (SecurityConfig, WebConfig 등)
│   ├── exception       # GlobalExceptionHandler, CustomException, ErrorCode
│   ├── response        # ApiResponse<T>
│   └── security        # (필요한 서비스만)
└── {domain}
    ├── controller
    ├── service
    ├── repository
    ├── entity
    └── dto
```

> 도메인이 2개 이상인 서비스는 도메인 단위로 패키지를 분리한다.  
> 예: `member-point-service` → `member/`, `point/`

---

## 2. 공통 응답 포맷

모든 API 응답은 아래 형식을 따른다.

### 2-1. 성공 응답

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {},
  "timestamp": "2026-05-28T10:00:00"
}
```

### 2-2. 에러 응답

```json
{
  "success": false,
  "errorCode": "POINT_INSUFFICIENT",
  "message": "포인트가 부족합니다. 현재 보유: 50P, 필요: 100P",
  "data": null,
  "timestamp": "2026-05-28T10:00:00"
}
```

### 2-3. ApiResponse 구현

```java
@Getter
@Builder
public class ApiResponse<T> {

    private final boolean success;
    private final String errorCode;
    private final String message;
    private final T data;
    private final LocalDateTime timestamp;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return ApiResponse.<Void>builder()
                .success(false)
                .errorCode(errorCode.getCode())
                .message(errorCode.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String detail) {
        return ApiResponse.<Void>builder()
                .success(false)
                .errorCode(errorCode.getCode())
                .message(detail)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
```

---

## 3. 에러 코드

### 3-1. 공통 ErrorCode Enum

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "권한이 없습니다.", HttpStatus.FORBIDDEN),
    NOT_FOUND("NOT_FOUND", "리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INTERNAL_ERROR("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    // Point
    POINT_INSUFFICIENT("POINT_INSUFFICIENT", "포인트가 부족합니다.", HttpStatus.BAD_REQUEST),

    // Battle
    ALREADY_VOTED("ALREADY_VOTED", "이미 투표한 Battle입니다.", HttpStatus.CONFLICT),
    BATTLE_CLOSED("BATTLE_CLOSED", "종료된 Battle입니다.", HttpStatus.BAD_REQUEST),

    // Market
    ALREADY_PREDICTED("ALREADY_PREDICTED", "이미 참여한 Market입니다.", HttpStatus.CONFLICT),
    MARKET_CLOSED("MARKET_CLOSED", "마감된 Market입니다.", HttpStatus.BAD_REQUEST),
    INVALID_SETTLE("INVALID_SETTLE", "정산 조건을 충족하지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
```

### 3-2. 에러 코드 참조표

| 에러 코드 | HTTP 상태 | 설명 |
|---|---:|---|
| UNAUTHORIZED | 401 | JWT 토큰 없음 또는 만료 |
| FORBIDDEN | 403 | 권한 없음 |
| NOT_FOUND | 404 | 리소스 없음 |
| POINT_INSUFFICIENT | 400 | Point 부족 |
| ALREADY_VOTED | 409 | 이미 투표한 Battle |
| ALREADY_PREDICTED | 409 | 이미 참여한 Market |
| MARKET_CLOSED | 400 | 마감된 Market |
| BATTLE_CLOSED | 400 | 종료된 Battle |
| INVALID_SETTLE | 400 | 정산 조건 미충족 |
| INTERNAL_ERROR | 500 | 서버 내부 오류 |

---

## 4. 예외 처리

### 4-1. GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.fail(e.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
```

### 4-2. CustomException

```java
@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
```

---

## 5. 네이밍 규칙

### 5-1. DTO 네이밍

| 구분 | 형식 | 예시 |
|---|---|---|
| 생성 요청 | `{Domain}CreateRequest` | `BattleCreateRequest` |
| 수정 요청 | `{Domain}UpdateRequest` | `MarketUpdateRequest` |
| 일반 요청 | `{Domain}{Action}Request` | `MarketPredictRequest` |
| 단건 응답 | `{Domain}Response` | `BattleResponse` |
| 목록 응답 | `{Domain}ListResponse` | `BattleListResponse` |
| 내부 전달 | `{Domain}{Purpose}Dto` | `PointEarnDto` |

### 5-2. API URL 규칙

- 모든 URL은 `/api/v1/` prefix를 사용한다.
- 리소스명은 복수형 소문자를 사용한다.
- Path variable은 `{id}` 형식을 사용한다.

```
/api/v1/battles
/api/v1/battles/{battleId}
/api/v1/battles/{battleId}/votes
/api/v1/markets
/api/v1/markets/{marketId}/predictions
/api/v1/points/earn
/api/v1/members/{memberId}
```

### 5-3. 클래스 네이밍

| 유형 | 형식 | 예시 |
|---|---|---|
| Controller | `{Domain}Controller` | `BattleController` |
| Service | `{Domain}Service` | `BattleService` |
| ServiceImpl | `{Domain}ServiceImpl` | `BattleServiceImpl` |
| Repository | `{Domain}Repository` | `BattleRepository` |
| Entity | `{Domain}` | `Battle` |

---

## 6. 상태값 (Enum) 통일

### 6-1. Battle 상태

```java
public enum BattleStatus {
    PENDING,    // 검수 대기
    ACTIVE,     // 투표 진행 중
    CLOSED,     // 투표 종료
    CANCELLED   // 강제 취소
}
```

### 6-2. Market 상태

```java
public enum MarketStatus {
    PENDING,        // 검수 대기
    ACTIVE,         // 예측 참여 가능
    CLOSED,         // 예측 마감 (정산 대기)
    DATA_PENDING,   // 공공 데이터 수신 대기
    SETTLED,        // 정산 완료
    VOIDED          // 무효 처리
}
```

### 6-3. 예측 참여 상태

```java
public enum PredictionStatus {
    PENDING,    // Point 차감 대기
    CONFIRMED,  // 참여 확정 (Point 차감 완료)
    FAILED,     // 참여 실패 (Point 차감 실패)
    SETTLED,    // 정산 완료
    REFUNDED    // 환불 완료 (Market 무효 시)
}
```

### 6-4. Point 히스토리 타입

```java
public enum PointHistoryType {
    EARN_SIGNUP,        // 신규 가입 보상
    EARN_VOTE,          // Battle 투표 참여 보상
    EARN_VOTE_WIN,      // Battle 승리 진영 추가 보상
    EARN_COMMENT,       // Battle 댓글 작성 보상
    EARN_BATTLE_APPROVED, // Battle 주제 등록 승인 보상
    SPEND_MARKET,       // Market 예측 참여
    SPEND_INSIGHT,      // Insight 열람 (교차분석, AI 리포트 등)
    SPEND_BATTLE_CREATE, // Battle 주제 생성권
    SPEND_SLOT,         // 관심 지역 슬롯 확장
    SETTLE_MARKET,      // Market 정산 보상
    REFUND_MARKET,      // Market 무효 환불
    BURN                // 시스템 소각
}
```

---

## 7. DB 설계 규칙

### 7-1. PK 전략

- 모든 엔티티의 PK는 `Long` 타입 Auto Increment를 사용한다.
- UUID는 MVP에서 사용하지 않는다.

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

### 7-2. 공통 BaseEntity

모든 엔티티는 `BaseEntity`를 상속한다.

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseEntity {

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

### 7-3. 삭제 정책

- 기본적으로 soft delete를 사용한다.
- 삭제 시 `deletedAt` 컬럼을 사용한다.
- 물리 삭제가 필요한 경우에만 예외로 허용하며, 반드시 주석으로 이유를 명시한다.

```java
@Column
private LocalDateTime deletedAt;

public boolean isDeleted() {
    return deletedAt != null;
}
```

### 7-4. 시간 포맷

- DB: `DATETIME` 타입 사용
- Java: `LocalDateTime`
- API 응답: `yyyy-MM-dd'T'HH:mm:ss` (ISO-8601)
- 모든 시간은 KST(Asia/Seoul) 기준

### 7-5. 소수점 처리

- Point 잔액 및 정산 금액은 소수점 둘째 자리까지 저장한다. (`DECIMAL(10,2)`)
- 정산 계산 시 소수점 셋째 자리 이하는 버림 처리한다.
- 버림 처리로 남는 잔여 Point는 시스템 소각 처리한다.

---

## 8. 서비스 간 REST 연계 원칙

### 8-1. 기본 원칙

- 호출자가 결과를 즉시 알아야 하는 서비스 간 통신은 REST API 직접 호출로 처리한다.
- 호출자가 결과를 기다리지 않는 후속 처리는 Kafka 이벤트로 처리한다. SSOT는 각 서비스 DB이며, 이벤트는 outbox 테이블 + CDC(Debezium)로 발행한다. (세부 원칙: 루트 `AGENTS.md` 섹션 5)
- 모든 서비스 간 REST 요청/응답은 공통 응답 포맷(`ApiResponse<T>`)을 따른다.

### 8-2. 멱등성 키

Point 관련 API는 중복 처리 방지를 위해 멱등성 키를 사용한다.

- 요청 헤더: `Idempotency-Key: {uuid}`
- 동일 키로 재요청 시 첫 번째 응답을 그대로 반환한다.

### 8-3. 서비스 간 주요 호출 방향

| 호출 방향 | 트리거 시점 | 목적 |
|---|---|---|
| Gateway → Member-Point | 인증 요청 | JWT 검증 및 사용자 정보 조회 |
| Battle → Member-Point | 투표/댓글 완료 | Point 지급 |
| Battle → Insight-Reputation | 투표/댓글 완료 | 활동 점수 업데이트 |
| Market → Member-Point | 예측 참여 | Point 차감 |
| Market → Member-Point | 정산 완료 | 정산 보상 Point 지급 |
| Market → Member-Point | 무효 처리 | 참여 Point 환불 |
| Market → Insight-Reputation | 정산 완료 | 예측 정확도 업데이트 |
| Insight-Reputation → Battle | AI 분석 | 투표 원본 데이터 조회 |
| Insight-Reputation → Market | AI 분석 | 예측/정산 데이터 조회 |

### 8-4. Point 지급 실패 처리

Battle → Member-Point Point 지급이 실패한 경우:

- 투표 자체는 유지한다.
- `point_reward_retry_queue`에 실패 건을 적재한다.
- Scheduler가 1분 간격으로 최대 3회 재시도한다.
- 3회 실패 시 관리자 수동 보정 대상으로 기록한다.

---

## 9. Git 협업 전략

### 9-1. 브랜치 전략

```
main
  └── develop
        ├── feature/battle
        ├── feature/market
        ├── feature/member-point
        ├── feature/insight-reputation
        └── feature/frontend
```

### 9-2. 작업 흐름

```
develop에서 feature 브랜치 생성
→ 기능 개발
→ push
→ Pull Request 생성
→ 최소 1명 리뷰 후 develop merge
```

### 9-3. 브랜치 규칙

- `main` 직접 push 금지
- 기능 단위로 commit 유지
- 하루 1회 이상 `develop` 최신화
- 충돌 발생 시 해당 도메인 담당자가 우선 해결
- API 응답 포맷 및 DTO 변경 시 팀원 전체에게 공유

### 9-4. Commit Convention

```
feat: 기능 추가
fix: 버그 수정
refactor: 리팩토링
docs: 문서 수정
test: 테스트 코드
chore: 설정 수정
```

예시:

```
feat: battle 투표 API 추가
fix: JWT 만료 처리 수정
refactor: Point 정산 계산 로직 분리
docs: API_SPEC 업데이트
```

---

## 10. 문서 책임

| 문서 | 작성/유지 책임 |
|---|---|
| `CONVENTION.md` | 팀 전체 합의, Insight-Reputation 담당자 초안 |
| `ERD.md` (루트) | 팀 전체 합의, Insight-Reputation 담당자 초안 |
| `API_SPEC.md` (루트) | 팀 전체 합의, Insight-Reputation 담당자 초안 |
| `docs/battle/` | Battle 담당자 |
| `docs/market/` | Market 담당자 |
| `docs/member-point/` | Member-Point 담당자 |
| `docs/insight-reputation/` | Insight-Reputation 담당자 |

---

## 11. LLM 서브 에이전트 컨텍스트 가이드

각 서비스 LLM 에이전트가 참조해야 하는 문서 목록이다.

| 에이전트 | 필수 참조 문서 |
|---|---|
| 공통 | `README.md`, `CONVENTION.md` |
| Battle | + `ERD.md` (루트), `docs/battle/ERD.md`, `docs/battle/API_SPEC.md` |
| Market | + `ERD.md` (루트), `docs/market/ERD.md`, `docs/market/API_SPEC.md` |
| Member-Point | + `ERD.md` (루트), `docs/member-point/ERD.md`, `docs/member-point/API_SPEC.md` |
| Insight-Reputation | + `ERD.md` (루트), `docs/insight-reputation/ERD.md`, `docs/insight-reputation/API_SPEC.md` |
