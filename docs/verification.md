# Verification evidence — 2026-11-14

Environment: macOS arm64, Java 21.0.13.0.3, Maven 3.10.0, Docker 29.8.2 (OrbStack). Spring Boot 3.5.16. Actual
disposable PostgreSQL container reported PostgreSQL 17.12. Repository root has no Git metadata, so no commit revision is
claimed. Source and artifacts are under ecommerce-marketplace; related sibling projects were preserved. Kubernetes was
not deployed.

## Executed commands

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DOCKER_HOST=unix:///Users/MWGBH/.orbstack/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mvn -q -f ecommerce-marketplace/pom.xml -Pintegration verify

docker compose -f ecommerce-marketplace/compose.yml \
  --env-file ecommerce-marketplace/.env up -d --build

# With LOCAL_PASSWORD set to the configured sandbox password:
python3 ecommerce-marketplace/scripts/smoke.py

node --check ecommerce-marketplace/src/main/resources/static/app.js
python3 -m py_compile ecommerce-marketplace/scripts/smoke.py
```

Original baseline verification passed 24 tests. Authentication/payment continuation verification passed: **36 tests, 0
failures, 0 errors, 0 skipped** (21 original PostgreSQL integration tests, 8 Stripe HTTP recovery tests, 4 production
authentication tests, 3 money unit tests). Reports are preserved in `evidence/*Test.txt`. Unit tests verify exact money
conversion, bounds/rounding and cumulative discount allocation across exhaustive small totals. Integration tests use
MockMvc + Spring Security + actual PostgreSQL transactions and Flyway, not mocked transaction correctness. They cover
multi-seller confirmation/outbox, duplicate and concurrent duplicate checkout, last-unit contention, whole-transaction
rollback, coupon limit races, changed offer/seller suspension, lost capture response, late capture after cancellation,
repeated refund recovery, signed duplicate/contradictory webhooks, CSRF/ownership, partial shipment/full-line
returns/restock, refund limits including pending reservations, uncaptured refund rejection, expiry before/during
capture, provider decline, cancel versus capture and manual workflow retry.

No test was silently skipped due to missing Docker. `mvn test` by default excludes integration-tagged tests;
`-Pintegration verify` includes them. Failures were not replaced with mocked passing tests. Compiler/runtime produced
Byte Buddy dynamic-agent warnings from test tooling, not application failures.

## Actual Compose HTTP flow

Real localhost form login and JSESSIONID + fresh CSRF token, two distinct seller offers, owned address, quote, checkout,
duplicate same-key replay, asynchronous CONFIRMED order, matching sum of immutable line nets, unshipped cancellation and
completed refund all passed. One recorded run: order `55176fc1-0af4-4d98-9712-0c18bb56d572`, INR 379800 minor units, 2
seller groups, one COMPLETED refund. The smoke script is repeatable and replenishes stock by cancellation. It uses
sandbox credentials only.

Redis failure check: stopped only ecommerce-marketplace-redis-1, called GET /api/v1/categories, received 2 categories
from PostgreSQL in 267.8ms, then restarted Redis. This is a single actual fallback observation, not an outage latency
guarantee. Browser automation could not connect to its host, so visual/interactive browser QA is explicitly unverified.
JavaScript syntax and real HTTP behavior were checked; they do not prove browser UI rendering.

## Small measured catalog workload

Command:

```sh
docker run --rm -i -e RATE=20 -e DURATION=10s \
  grafana/k6@sha256:3d8e5c7f1d53ec59408c2ffbd9943602a0985ffcbd4a2430ec00f3ffca89796e \
  run --summary-export=/dev/stdout - < ecommerce-marketplace/benchmarks/browse.js
```

Dataset: 2 products, 2 variants, 3 offers, 2 sellers. Local Docker networking from load container via
host.docker.internal; app configured 2 CPU limit, 768MiB memory limit, DB pool 16. Shared developer machine also ran
other projects; no isolated hardware characterization. Ten seconds, offered 20 iterations/s, 201 completed HTTP
requests, achieved ~20.09/s, zero HTTP failures, 402/402 checks succeeded. Observed request p50 ~5.60ms, p95 ~14.53ms,
p99 ~55.54ms, max ~153.39ms. Raw output and machine-readable summary are in benchmarks/results/browse-final.log and
browse-summary.json.

This is only a browse smoke workload. It is not a maximum-capacity benchmark, production latency SLO, high-volume
checkout result or financial/overselling stress claim. No 1k/10k/100k/1m-RPS or 10k-orders/s result was measured. k6
workloads for those target scenarios are supplied with explicit prerequisites. No Kafka lag, cloud IOPS, cross-region
RPO/RTO, real provider timing or availability percentage is fabricated.

## Remaining verification

Real provider/network timeout and settlement, Kubernetes rollout, browser visual/accessibility, PostgreSQL failover,
process-kill/lease takeover, distributed session/rate limiting, full 35-scenario chaos testing and all target-scale
workloads remain. See failure-analysis.md, implementation-status.md and limitations.md. Passing build plus sandbox flow
does not establish production readiness or compliance.

## Authentication / Stripe continuation verification

Executed `mvn -q -f ecommerce-marketplace/pom.xml -Pintegration verify` with JDK21, Docker/OrbStack environment as
above: **36 tests passed; no failures/errors/skips**. Evidence reports include ProductionAuthenticationIntegrationTest
and StripeRecoveryIntegrationTest. The tests start a real signed OIDC metadata/JWKS/authorization/token/userinfo HTTP
service, real PostgreSQL, and two real embedded-Tomcat marketplace instances. Login state/nonce, session rotation,
cross-instance use followed by node shutdown, CSRF/session revocation, session expiry, JWT issuer/audience/expiry and no
invalid-bearer cookie fallback are exercised. Userinfo-injected ADMIN roles cannot grant privileges.

Stripe tests exercise the actual RestClient HTTP adapter against a local contract server, including real read timeouts
after the remote side persisted a checkout/refund, pending refund GET retries, provider-side checkout expiry, finite
idempotency retention fail-closed behavior and durable signed webhook retries after HTTP 503. A PostgreSQL trigger
aborts confirmation after provider success; retry confirms the same payment without another checkout session. These are
contract/failure tests, not calls to a real Stripe account. STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET and external OIDC
issuer were not configured; real external account/IdP interoperability was not verified.

Rebuilt Docker Compose applied V3 to the existing V2 sandbox database successfully, preserving existing data. The real
HTTP session/CSRF two-seller purchase -> confirmation -> cancellation -> completed-refund smoke passed again. Local
simulator remains the selected running provider at http://localhost:8097. No new capacity benchmark was run, and the
earlier benchmark remains a historical baseline.

## Kafka / shipping-tax / quantity continuation

Final JDK21 `mvn -q -f ecommerce-marketplace/pom.xml -Pintegration verify` passed **50 tests: 0 failures, 0 errors, 0
skipped**. Reports are in evidence: MarketplaceIntegrationTest (25), RulesTest (3), StripeRecoveryIntegrationTest (8),
ProductionAuthenticationIntegrationTest (4), KafkaTransportIntegrationTest (4), ShippingTaxIntegrationTest (5),
QuantityMigrationIntegrationTest (1). Tests use actual PostgreSQL17 and Apache Kafka Native3.9.1 containers; Spring's
managed Kafka client is3.9.2. A real broker pause/unpause verifies outbox retention/recovery. Consumer DB trigger
failure verifies rollback, DLT persistence, repair and original-ID replay.

Additional HTTP provider tests validate Shippo rate decimal parsing/INR filtering, Stripe Tax line/total identities,
503/timeout behavior, quoted tax preservation through payment, delayed tracking retry and a partial goods+tax refund. A
latch holds the tax provider while a concurrent API mutation changes the cart; the cart mutation succeeds without
waiting on a database lock and final quotation rejects the changed snapshot. Partial quantity races assert no
overshipment/over-return; full discounted goods refunds conserve a non-divisible integer total.

Docker Compose Kafka overlay was actually built/run. Flyway V4 upgraded the existing sandbox database without deleting
prior orders. A localhost session/CSRF multi-seller purchase -> confirmation -> cancellation -> completed-refund smoke
passed, and SQL verified OrderCreated/OrderConfirmed/OrderCancelled/RefundCompleted were all published and each reached
notifications through Kafka. The running stack remains at localhost:8097 with local payment/shipping/tax fixtures and
real Kafka transport.

Shippo and Stripe Tax keys were absent; no live-account requests, paid label purchases or tax filings were performed.
Their verification is a local HTTP wire-contract/failure suite. No new load benchmark or cloud/Kubernetes/HA claim is
made. See kafka-shipping-tax-and-quantities.md for exact provider configuration and semantics.

The final source-matching Docker rebuild succeeded after a transient Maven artifact-resolution failure; Dockerfile now
uses a persistent BuildKit Maven cache. The final-image Kafka-enabled HTTP smoke passed again (recorded in
evidence/http-smoke-kafka.json).

## Label purchasing / tax transaction continuation

Final JDK21 Maven integration verification passed **67 tests, zero failures/errors/skips**:
MarketplaceIntegrationTest25, RulesTest3, StripeRecoveryIntegrationTest8, ProductionAuthenticationIntegrationTest4,
KafkaTransportIntegrationTest4, QuantityMigrationIntegrationTest1, ShippingTaxIntegrationTest22. Reports are preserved
in evidence. New coverage includes single-attempt Shippo purchasing after remote success/read timeout, overlapping
expired leases, quantity/cost/ownership/key conflicts, download/dispatch idempotency, local commit failure after
purchase, provider-refunded-label release, tax sale/reversal read timeouts with stable keys, known-ID GET recovery after
DB failure, reversal dependency ordering, completed-refund gating, full shipping reversal, ten tiny partial refunds
conserving separate net/tax, aged ambiguity fail-closed, verified provider attachment and historical
catch-up/legacy-component handling. Lease decisions now use database time after a test exposed a JVM/database clock
race.

Docker Compose/Kafka rebuilt successfully and applied additive V5/V6 to the existing sandbox database.
scripts/smoke-labels.py passed via actual HTTP form sessions/CSRF: one explicit non-postage simulator label, one
physical shipment despite dispatch retry, delivered2 units and2 partial COMPLETED refunds totaling599800 minor units.
The original two-seller purchase/cancel/refund smoke passed again. Machine-readable outputs are
evidence/http-smoke-labels.json and evidence/http-smoke-label-regression.json. These are offline-provider flows, not
purchased real postage. Shippo/Stripe account credentials were not configured; actual external sandbox execution remains
unverified.

Automatic approval review rejected an attempted append to already-tested V5. The rejected shell command did not execute.
The catch-up columns/indexes were implemented in additive V6 instead; no checksum override or repair was used. No work
remains blocked by that review.
