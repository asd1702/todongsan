# ERROR_POLICY.md

> 동네대전 프로젝트의 공통 에러 응답 정책을 정의한다.  
> 이 문서는 모든 서비스에서 공통으로 사용하는 에러 응답 JSON, HTTP Status Code 사용 원칙, ErrorCode 네이밍 규칙, 공통 에러 코드를 다룬다.  
> 도메인별 비즈니스 에러 코드는 각 도메인 API 명세서 또는 도메인별 문서에서 별도로 정의한다.

---

## 1. 공통 에러 응답 JSON

모든 API 에러 응답은 아래 공통 포맷을 따른다.

```json
{
  "success": false,
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값 검증에 실패했습니다.",
  "data": null,
  "timestamp": "2026-05-29T15:30:00"
}
```

### 1-1. 필드 설명

| 필드 | 타입 | 설명 |
|---|---|---|
| `success` | boolean | 요청 성공 여부. 에러 응답에서는 항상 `false` |
| `errorCode` | string | 클라이언트와 서버가 에러를 식별하기 위한 코드 |
| `message` | string | 사용자 또는 개발자가 이해할 수 있는 에러 메시지 |
| `data` | object / null | 에러 응답에서는 기본적으로 `null` |
| `timestamp` | string | 에러 발생 시각. `yyyy-MM-dd'T'HH:mm:ss` 형식 사용 |

### 1-2. Validation 에러 응답

`@Valid`, `@Validated` 검증 실패처럼 여러 필드에서 에러가 발생할 수 있는 경우, `data`에 상세 검증 결과를 포함할 수 있다.

```json
{
  "success": false,
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값 검증에 실패했습니다.",
  "data": {
    "errors": [
      {
        "field": "title",
        "reason": "제목은 필수입니다."
      },
      {
        "field": "amount",
        "reason": "금액은 0보다 커야 합니다."
      }
    ]
  },
  "timestamp": "2026-05-29T15:30:00"
}
```

---

## 2. HTTP Status Code 사용 원칙

HTTP Status Code는 에러의 큰 분류를 나타내고, `errorCode`는 구체적인 원인을 나타낸다.

### 2-1. 400 Bad Request

클라이언트가 잘못된 요청을 보낸 경우 사용한다.

사용 예시:

- 요청 JSON 형식 오류
- 필수 파라미터 누락
- 요청 값 검증 실패
- 잘못된 Query Parameter 또는 Path Variable
- 필수 Header 누락

대표 ErrorCode:

- `INVALID_REQUEST`
- `VALIDATION_FAILED`
- `MISSING_REQUIRED_PARAMETER`
- `MISSING_REQUIRED_HEADER`

---

### 2-2. 401 Unauthorized

인증되지 않은 사용자가 인증이 필요한 API에 접근한 경우 사용한다.

사용 예시:

- JWT 토큰 없음
- JWT 토큰 만료
- JWT 토큰 형식 오류
- 인증 정보 파싱 실패

대표 ErrorCode:

- `UNAUTHORIZED`
- `TOKEN_EXPIRED`
- `INVALID_TOKEN`

---

### 2-3. 403 Forbidden

인증은 되었지만 해당 리소스나 행위에 대한 권한이 없는 경우 사용한다.

사용 예시:

- 다른 사용자의 리소스 수정 시도
- 관리자 전용 API에 일반 사용자가 접근
- 서비스 내부 호출 권한 없음

대표 ErrorCode:

- `FORBIDDEN`

---

### 2-4. 404 Not Found

요청한 리소스를 찾을 수 없는 경우 사용한다.

사용 예시:

- 존재하지 않는 API 경로
- 존재하지 않는 리소스 ID 조회
- 삭제되었거나 비활성화된 리소스 접근

대표 ErrorCode:

- `RESOURCE_NOT_FOUND`
- `API_NOT_FOUND`

> 단, `BATTLE_NOT_FOUND`, `MARKET_NOT_FOUND`, `MEMBER_NOT_FOUND`처럼 도메인 의미가 강한 에러는 각 도메인 에러 코드로 정의할 수 있다.

---

### 2-5. 405 Method Not Allowed

지원하지 않는 HTTP Method로 요청한 경우 사용한다.

사용 예시:

- `GET`만 지원하는 API에 `POST` 요청
- `POST`만 지원하는 API에 `DELETE` 요청

대표 ErrorCode:

- `METHOD_NOT_ALLOWED`

---

### 2-6. 409 Conflict

요청이 현재 서버 상태 또는 기존 처리 이력과 충돌하는 경우 사용한다.

사용 예시:

- 동일한 멱등성 키로 서로 다른 요청 내용이 들어온 경우
- 중복 생성 요청
- 이미 처리된 요청과 충돌하는 요청

대표 ErrorCode:

- `IDEMPOTENCY_KEY_CONFLICT`
- `DUPLICATE_RESOURCE`

> `ALREADY_VOTED`, `ALREADY_PREDICTED`처럼 특정 도메인의 비즈니스 충돌은 각 도메인 에러 코드로 정의한다.

---

### 2-7. 415 Unsupported Media Type

지원하지 않는 Content-Type으로 요청한 경우 사용한다.

사용 예시:

- JSON API에 `Content-Type: text/plain`으로 요청
- 파일 업로드가 아닌 API에 multipart 요청

대표 ErrorCode:

- `UNSUPPORTED_MEDIA_TYPE`

---

### 2-8. 500 Internal Server Error

서버 내부에서 예측하지 못한 오류가 발생한 경우 사용한다.

사용 예시:

- NullPointerException
- 예상하지 못한 RuntimeException
- 서버 내부 로직 오류
- DB 처리 중 예측하지 못한 오류

대표 ErrorCode:

- `INTERNAL_ERROR`
- `DATABASE_ERROR`

---

### 2-9. 502 Bad Gateway

서비스 간 REST 호출에서 상대 서비스가 비정상 응답을 반환한 경우 사용한다.

사용 예시:

- 상대 서비스가 5xx 응답 반환
- 상대 서비스 응답이 공통 `ApiResponse` 포맷과 다름
- Gateway 또는 중간 서비스에서 하위 서비스 응답 처리 실패

대표 ErrorCode:

- `EXTERNAL_SERVICE_ERROR`
- `EXTERNAL_SERVICE_BAD_RESPONSE`

---

### 2-10. 503 Service Unavailable

상대 서비스 또는 서버 자원을 일시적으로 사용할 수 없는 경우 사용한다.

사용 예시:

- 상대 서비스 연결 실패
- Connection Refused
- 서비스 인스턴스 중단
- 일시적인 서버 과부하

대표 ErrorCode:

- `EXTERNAL_SERVICE_UNAVAILABLE`
- `SERVICE_UNAVAILABLE`

---

### 2-11. 504 Gateway Timeout

서비스 간 REST 호출에서 상대 서비스가 제한 시간 안에 응답하지 않은 경우 사용한다.

사용 예시:

- Member-Point 서비스 포인트 차감 요청 타임아웃
- Insight-Reputation 서비스 호출 타임아웃
- Gateway에서 하위 서비스 응답 대기 시간 초과

대표 ErrorCode:

- `EXTERNAL_SERVICE_TIMEOUT`

---

## 3. ErrorCode 네이밍 규칙

### 3-1. 기본 원칙

ErrorCode는 대문자 스네이크 케이스를 사용한다.

```text
{DOMAIN}_{REASON}
{DOMAIN}_{ACTION}_{REASON}
```

공통 에러는 도메인명을 붙이지 않고 범용적인 이름을 사용한다.

```text
VALIDATION_FAILED
UNAUTHORIZED
FORBIDDEN
RESOURCE_NOT_FOUND
INTERNAL_ERROR
EXTERNAL_SERVICE_TIMEOUT
```

도메인 에러는 도메인명을 prefix로 사용한다.

```text
POINT_INSUFFICIENT
BATTLE_ALREADY_VOTED
MARKET_CLOSED
MARKET_ALREADY_PREDICTED
```

---

### 3-2. 공통 에러 네이밍

공통 에러는 모든 서비스에서 재사용 가능한 이름으로 정의한다.

| 패턴 | 설명 | 예시 |
|---|---|---|
| `{REASON}` | 가장 일반적인 공통 에러 | `UNAUTHORIZED`, `FORBIDDEN` |
| `{TARGET}_NOT_FOUND` | 공통 리소스 없음 | `RESOURCE_NOT_FOUND`, `API_NOT_FOUND` |
| `{TARGET}_REQUIRED` | 필수 값 누락 | `IDEMPOTENCY_KEY_REQUIRED` |
| `{TARGET}_CONFLICT` | 요청 충돌 | `IDEMPOTENCY_KEY_CONFLICT` |
| `EXTERNAL_SERVICE_{REASON}` | 서비스 간 호출 실패 | `EXTERNAL_SERVICE_TIMEOUT` |

---

### 3-3. 도메인 에러 네이밍

도메인 에러는 각 도메인 문서에서 정의한다.

| 도메인 | 예시 |
|---|---|
| Member | `MEMBER_NOT_FOUND`, `MEMBER_ALREADY_EXISTS` |
| Point | `POINT_INSUFFICIENT`, `POINT_HISTORY_NOT_FOUND`, `POINT_SPEND_FENCED` (409) |
| Battle | `BATTLE_CLOSED`, `BATTLE_ALREADY_VOTED` |
| Market | `MARKET_CLOSED`, `MARKET_ALREADY_PREDICTED`, `MARKET_INVALID_FEE_RATE` |
| Insight-Reputation | `INSIGHT_REPORT_NOT_FOUND`, `REPUTATION_SCORE_INVALID` |

---

### 3-4. 네이밍 주의사항

다음 규칙을 지킨다.

- 같은 의미의 에러 코드를 여러 이름으로 만들지 않는다.
- HTTP Status Code를 ErrorCode 이름에 직접 넣지 않는다.
  - 나쁜 예: `BAD_REQUEST_ERROR`, `HTTP_500_ERROR`
- 너무 추상적인 이름을 피한다.
  - 나쁜 예: `ERROR`, `FAILED`, `INVALID`
- 클라이언트가 후속 처리를 구분해야 하는 경우 별도 ErrorCode로 분리한다.
  - 예: `TOKEN_EXPIRED`와 `INVALID_TOKEN`은 클라이언트 처리 방식이 다를 수 있으므로 분리한다.

---

## 4. 공통 에러 코드

공통 에러 코드는 특정 도메인 규칙과 무관하게 모든 서비스에서 발생 가능한 에러만 정의한다.

### 4-1. 요청 형식 관련 에러

| ErrorCode | HTTP Status | 설명 | Retry |
|---|---:|---|---:|
| `INVALID_REQUEST` | 400 | 요청 형식이 올바르지 않음 | X |
| `VALIDATION_FAILED` | 400 | 요청 값 검증 실패 | X |
| `MISSING_REQUIRED_PARAMETER` | 400 | 필수 Query Parameter 또는 Path Variable 누락 | X |
| `MISSING_REQUIRED_HEADER` | 400 | 필수 Header 누락 | X |
| `METHOD_ARGUMENT_TYPE_MISMATCH` | 400 | 요청 파라미터 타입 불일치 | X |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 Content-Type | X |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 HTTP Method | X |

---

### 4-2. 인증/인가 관련 에러

| ErrorCode | HTTP Status | 설명 | Retry |
|---|---:|---|---:|
| `UNAUTHORIZED` | 401 | 인증 정보 없음 | X |
| `TOKEN_EXPIRED` | 401 | JWT 토큰 만료 | X |
| `INVALID_TOKEN` | 401 | JWT 토큰 형식 오류 또는 검증 실패 | X |
| `FORBIDDEN` | 403 | 접근 권한 없음 | X |

---

### 4-3. 리소스 관련 공통 에러

| ErrorCode | HTTP Status | 설명 | Retry |
|---|---:|---|---:|
| `RESOURCE_NOT_FOUND` | 404 | 요청한 리소스를 찾을 수 없음 | X |
| `API_NOT_FOUND` | 404 | 존재하지 않는 API 경로 | X |
| `DUPLICATE_RESOURCE` | 409 | 이미 존재하는 리소스 생성 시도 | X |

---

### 4-4. 멱등성 관련 공통 에러

| ErrorCode | HTTP Status | 설명 | Retry |
|---|---:|---|---:|
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 멱등성 키가 필요한 API에서 Header 누락 | X |
| `IDEMPOTENCY_KEY_INVALID` | 400 | 멱등성 키 형식이 올바르지 않음 | X |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 동일한 멱등성 키로 다른 요청 내용이 들어옴 | X |

---

### 4-5. 서비스 간 REST 호출 에러

| ErrorCode | HTTP Status | 설명 | Retry |
|---|---:|---|---:|
| `EXTERNAL_SERVICE_TIMEOUT` | 504 | 상대 서비스 응답 시간 초과 | O |
| `EXTERNAL_SERVICE_UNAVAILABLE` | 503 | 상대 서비스 연결 실패 또는 사용 불가 | O |
| `EXTERNAL_SERVICE_ERROR` | 502 | 상대 서비스가 5xx 에러 반환 | O |
| `EXTERNAL_SERVICE_BAD_RESPONSE` | 502 | 상대 서비스 응답 포맷이 올바르지 않음 | △ |
| `SERVICE_UNAVAILABLE` | 503 | 현재 서비스가 일시적으로 요청을 처리할 수 없음 | O |

> `EXTERNAL_SERVICE_BAD_RESPONSE`는 재시도해도 해결되지 않을 가능성이 있으므로 기본적으로 운영 로그를 남기고, 상황에 따라 재시도 여부를 결정한다.

---

### 4-6. 서버 내부 에러

| ErrorCode | HTTP Status | 설명 | Retry |
|---|---:|---|---:|
| `INTERNAL_ERROR` | 500 | 예상하지 못한 서버 내부 오류 | O |
| `DATABASE_ERROR` | 500 | DB 처리 중 오류 발생 | O |
| `DATABASE_TIMEOUT` | 500 | DB 응답 시간 초과 | O |

---

## 5. 공통 에러와 도메인 에러의 구분

공통 에러에는 모든 서비스에서 공통으로 발생 가능한 기술적, 인증/인가, 요청 형식, 통신 오류만 포함한다.

공통 에러에 포함한다.

- 요청 형식 오류
- 요청 값 검증 실패
- 인증/인가 오류
- 공통 리소스 조회 실패
- 멱등성 키 오류
- 서비스 간 REST 호출 오류
- 서버 내부 오류
- DB 오류

공통 에러에 포함하지 않는다.

- 포인트 부족
- 이미 투표함
- 이미 예측 참여함
- 마켓 마감
- 배틀 종료
- 정산 조건 불충족
- 댓글 작성 제한
- 리포트 열람 권한 부족 등 특정 도메인 정책에 의존하는 에러

도메인 비즈니스 에러는 각 도메인별 `ErrorCode` 또는 도메인 API 문서에서 정의한다.

---

## 6. Java ErrorCode 예시

```java
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode {

    // 요청 형식
    INVALID_REQUEST("INVALID_REQUEST", "잘못된 요청입니다.", HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED("VALIDATION_FAILED", "요청 값 검증에 실패했습니다.", HttpStatus.BAD_REQUEST),
    MISSING_REQUIRED_PARAMETER("MISSING_REQUIRED_PARAMETER", "필수 요청 값이 누락되었습니다.", HttpStatus.BAD_REQUEST),
    MISSING_REQUIRED_HEADER("MISSING_REQUIRED_HEADER", "필수 헤더가 누락되었습니다.", HttpStatus.BAD_REQUEST),
    METHOD_ARGUMENT_TYPE_MISMATCH("METHOD_ARGUMENT_TYPE_MISMATCH", "요청 값의 타입이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    // 인증/인가
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("TOKEN_EXPIRED", "토큰이 만료되었습니다.", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN("INVALID_TOKEN", "유효하지 않은 토큰입니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),

    // 리소스
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    API_NOT_FOUND("API_NOT_FOUND", "존재하지 않는 API 경로입니다.", HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE("DUPLICATE_RESOURCE", "이미 존재하는 리소스입니다.", HttpStatus.CONFLICT),

    // 멱등성
    IDEMPOTENCY_KEY_REQUIRED("IDEMPOTENCY_KEY_REQUIRED", "멱등성 키가 필요합니다.", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_INVALID("IDEMPOTENCY_KEY_INVALID", "멱등성 키 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_CONFLICT("IDEMPOTENCY_KEY_CONFLICT", "동일한 멱등성 키로 다른 요청이 들어왔습니다.", HttpStatus.CONFLICT),

    // 서비스 간 통신
    EXTERNAL_SERVICE_TIMEOUT("EXTERNAL_SERVICE_TIMEOUT", "외부 서비스 응답 시간이 초과되었습니다.", HttpStatus.GATEWAY_TIMEOUT),
    EXTERNAL_SERVICE_UNAVAILABLE("EXTERNAL_SERVICE_UNAVAILABLE", "외부 서비스를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    EXTERNAL_SERVICE_ERROR("EXTERNAL_SERVICE_ERROR", "외부 서비스 호출 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY),
    EXTERNAL_SERVICE_BAD_RESPONSE("EXTERNAL_SERVICE_BAD_RESPONSE", "외부 서비스 응답 형식이 올바르지 않습니다.", HttpStatus.BAD_GATEWAY),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "현재 서비스를 일시적으로 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),

    // 서버 내부
    INTERNAL_ERROR("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_ERROR("DATABASE_ERROR", "데이터베이스 처리 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_TIMEOUT("DATABASE_TIMEOUT", "데이터베이스 응답 시간이 초과되었습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
```

---

## 7. Retry 기본 원칙

공통 에러 중 재시도 가능 여부는 아래 원칙을 따른다.

### 7-1. 재시도하지 않는 에러

클라이언트 요청 자체가 잘못되었거나, 인증/인가 또는 비즈니스 조건이 맞지 않는 에러는 재시도하지 않는다.

- 400 Bad Request
- 401 Unauthorized
- 403 Forbidden
- 404 Not Found
- 405 Method Not Allowed
- 409 Conflict
- 415 Unsupported Media Type

### 7-2. 재시도 가능한 에러

일시적인 서버 장애나 서비스 간 통신 장애는 재시도 가능하다.

- 500 Internal Server Error
- 502 Bad Gateway
- 503 Service Unavailable
- 504 Gateway Timeout

단, 500 계열 에러라도 동일 요청을 재시도할 경우 중복 처리가 발생할 수 있는 API는 반드시 `Idempotency-Key`를 사용해야 한다.

### 8. 도메인 별 ErrorCode 추가 규칙

새로운 ErrorCode를 추가하기 전에는 반드시 다음을 확인한다.

1. ERROR_POLICY.md의 공통 ErrorCode
2. 해당 도메인의 ERROR_CODE.md
3. 다른 도메인에서 동일한 의미로 사용 중인 ErrorCode

이미 동일하거나 유사한 의미의 ErrorCode가 존재하는 경우 새로운 ErrorCode를 생성하지 않는다.

ErrorCode는 비즈니스 의미를 기준으로 정의하며, 동일한 상황에 대해 여러 이름을 사용하지 않는다.

예시:

- `ALREADY_VOTED`
- `DUPLICATE_VOTE`
- `VOTE_ALREADY_EXISTS`

위 세 코드는 모두 동일한 의미이므로 하나만 사용한다.

도메인 에러는 반드시 `{DOMAIN}_{REASON}` 또는 `{DOMAIN}_{ACTION}_{REASON}` 형식을 따른다.

예시:

- `BATTLE_ALREADY_VOTED`
- `MARKET_ALREADY_PREDICTED`
- `POINT_INSUFFICIENT`

공통 ErrorCode로 표현 가능한 경우 도메인 ErrorCode를 새로 생성하지 않는다.
