# Synchronous REST resilience lab

## Purpose and boundary

This local-only lab measures failure propagation in the existing synchronous
REST topology. It does not introduce a message broker, retry, outbox, Saga, or
business-policy change.

The first protected dependency is this read-only call:

```text
Insight MarketClient
  GET /internal/api/v1/markets/{marketId}/basic-info
  → Market MarketInternalController
```

It is selected because it has no side effect and is already used by Insight's
public-data/reference flows. The profile-only probe invokes exactly this client
path without adding a production API:

```text
GET http://localhost:8083/internal/lab/resilience/market/{marketId}
```

The probe and Market fault filter are active only with the `resilience-lab`
profile. They are not present in the default Docker deployment.

## Current synchronous call map

| Caller | Callee | Method / endpoint family | Side effect | Timeout / retry today | Failure risk |
| --- | --- | --- | --- | --- | --- |
| Insight | Market | `GET /internal/api/v1/markets/{id}/basic-info`, `insight-summary`, `insight-predictions` | Read | basic-info: 500 ms connect, 1000 ms read; other existing `RestTemplate` calls retain their current defaults; no retry | A slow/down Market blocks or fails the calling Insight request. |
| Insight | Member-Point | `POST /internal/api/v1/points/spend`, `POST /points/earn`, `POST /members/batch` | Mixed | existing `RestTemplate` defaults; no automatic retry | Spend/refund remain distributed write risk; member batch failure blocks report enrichment. |
| Insight | Battle | `GET /api/v1/battles/...` comment/detail/vote routes | Read | existing `RestTemplate` defaults; no automatic retry | Battle failure blocks the dependent Insight read/report path. |
| Market | Member-Point | `POST /points/spend`, `GET /points/transactions`, `POST /points/settlements`, `POST /points/refunds` | Mixed | OpenFeign: 3000 ms connect, 5000 ms read; no lab retry | Point calls remain synchronous distributed-transaction risk. |
| Market | Insight | `POST /internal/api/v1/reputations/prediction` | Write | OpenFeign: 3000 ms connect, 5000 ms read; no lab retry | Reputation update failure affects Market's follow-up task. |
| Battle | Member-Point | `POST /internal/api/v1/points/earn`, `POST /points/spend` | Write | existing `RestTemplate` defaults; earn failure is handled by the existing retry queue | Excluded because Battle Outbox is the next separate task. |
| Battle | Insight | `POST /internal/api/v1/insights/battles/{battleId}/report` | Write | existing `RestTemplate` defaults; no lab retry | Report-generation dependency is outside this first experiment. |

## Start the lab

Use the base compose and the explicit lab override. The default compose must be
used for normal development and deployment.

```powershell
docker compose -f docker-compose.yml -f infra/docker-compose.resilience-lab.yml up -d --build market-service insight-reputation-service
```

The lab assumes `marketId=1` exists. Use any existing Market ID instead when
running the probe.

For a **before** batch, disable only the Resilience4j wrapper; keep the same
Market delay, Insight HTTP timeout, request count, and concurrency:

```powershell
$env:RESILIENCE_MARKET_CLIENT_ENABLED = 'false'
$env:RESILIENCE_LAB_MARKET_DELAY_MS = '3000'
docker compose -f docker-compose.yml -f infra/docker-compose.resilience-lab.yml up -d --force-recreate market-service insight-reputation-service
pwsh scripts/resilience/Invoke-InsightMarketLab.ps1 -RequestCount 20 -Concurrency 8
```

For the matching **after** batch, change only the wrapper switch:

```powershell
$env:RESILIENCE_MARKET_CLIENT_ENABLED = 'true'
docker compose -f docker-compose.yml -f infra/docker-compose.resilience-lab.yml up -d --force-recreate insight-reputation-service
pwsh scripts/resilience/Invoke-InsightMarketLab.ps1 -RequestCount 20 -Concurrency 8
```

The script writes per-request status/latency CSV and a summary JSON under
`scripts/resilience/results/`. Compare average, P95, P99, success/failure, and
the number of fail-fast responses using identical conditions.

## Fault injection scenarios

| Scenario | Injection | Expected observation |
| --- | --- | --- |
| Downstream down | `docker stop market-service` | Before: requests wait until HTTP failure. After enough failures: the circuit opens and later calls fail without Market I/O. Restart with `docker start market-service`. |
| Slow downstream | `RESILIENCE_LAB_MARKET_DELAY_MS=3000` | The 1000 ms Insight read timeout makes the dependency failure bounded. With the bulkhead enabled, only two calls may occupy the downstream slot; excess calls fail immediately. |
| Downstream 5xx | `RESILIENCE_LAB_MARKET_STATUS_CODE=500` | HTTP 500 is converted to the existing unavailable error. Repeated calls can open the circuit without any retry. |

Set one of the two Market injection variables, recreate `market-service`, and
reset it to `0` afterwards. The filter only matches the selected internal
basic-info GET route; normal profiles have no injected delay or status.

## Resilience4j configuration

### Profile boundary

The default, Docker, and local profiles keep `todongsan.resilience.market-client.enabled`
disabled unless explicitly overridden. The aggressive `marketClient` circuit-breaker
and bulkhead values live only in `application-resilience-lab.yml`; the lab compose
explicitly enables both the profile and `RESILIENCE_MARKET_CLIENT_ENABLED=true`.

They are observation parameters, not production tuning. A production decision must
be based on traffic, latency distribution, downstream SLO, caller thread capacity,
instance count, and observed failure rate.

`marketClient` uses:

| Mechanism | Lab value | Why |
| --- | --- | --- |
| HTTP timeout | 500 ms connect, 1000 ms read | Bounds one slow remote call. This is a client I/O decision, not a circuit policy. |
| Circuit breaker | count window 4, minimum calls 4, 50% failure rate, open 3 s, half-open 2 probes | Small values make CLOSED → OPEN → HALF_OPEN → CLOSED observable in a short local experiment. They are not production tuning. |
| Semaphore bulkhead | 2 concurrent calls, zero wait | A slow Market call cannot consume unlimited Insight request threads; excess calls fail fast. |
| Retry | none | No write/read retry behavior is changed in this experiment. |

The decorator order keeps a `BulkheadFullException` outside the circuit breaker,
so local saturation does not incorrectly count as a Market failure.

### Failure classification validation

`MarketClientCircuitBreakerFailurePredicate` records only known downstream
availability failures: `EXTERNAL_SERVICE_TIMEOUT`,
`EXTERNAL_SERVICE_UNAVAILABLE`, `EXTERNAL_SERVICE_ERROR`, and
`EXTERNAL_SERVICE_BAD_RESPONSE`. `RESOURCE_NOT_FOUND`, validation failures, and
other known business `CustomException` outcomes do not affect circuit health.
Unknown exceptions remain failures so an unexpected client, serialization, or
interaction error is not silently treated as healthy.

| Outcome | Circuit failure |
| --- | --- |
| `RESOURCE_NOT_FOUND` / normal business 4xx | No |
| Validation failure | No |
| Connection or read timeout | Yes |
| Downstream 5xx / invalid response | Yes |
| Unknown exception | Yes |
| `BulkheadFullException` | No |

Timeout answers *when a single request is abandoned*. Circuit breaker answers
*when a repeatedly failing dependency is temporarily skipped*. Bulkhead answers
*how much caller capacity one dependency may consume*.

## Verify circuit state and metrics

With delay or 500 active, make at least four failing calls. Insight logs one
state-transition event when the circuit moves to OPEN. Subsequent probe calls
produce a fail-fast unavailable response and increment the not-permitted metric
instead of causing Market I/O. Clear the injection, wait more than three seconds,
then send two successful probes to observe HALF_OPEN and CLOSED.

The Docker profile exposes Actuator metrics. Query the metric name list first:

```powershell
Invoke-RestMethod http://localhost:8083/actuator/metrics
Invoke-RestMethod http://localhost:8083/actuator/metrics/resilience4j.circuitbreaker.calls
Invoke-RestMethod http://localhost:8083/actuator/metrics/resilience4j.circuitbreaker.state
Invoke-RestMethod http://localhost:8083/actuator/metrics/resilience4j.bulkhead.available.concurrent.calls
```

Depending on the Resilience4j/Micrometer version, relevant metric names include
`resilience4j.circuitbreaker.calls`, `resilience4j.circuitbreaker.state`,
`resilience4j.circuitbreaker.failure.rate`,
`resilience4j.bulkhead.available.concurrent.calls`, and
`resilience4j.bulkhead.max.allowed.concurrent.calls`. The Actuator metric list
is the source of truth for the running version.

## Limits and next work

This lab intentionally does not protect Market-to-Point or Battle-to-Point write
calls with retry. Those remain synchronous distributed-transaction risks. The
next planned change is Battle vote transaction → transactional outbox → message
broker → Member-Point consumer → idempotency; it is not implemented here.
