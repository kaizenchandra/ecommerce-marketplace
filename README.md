# Market Lane — Java 21 multi-vendor marketplace

Working local sandbox with PostgreSQL-backed catalog/variants/seller offers, seller approval, durable carts and
addresses, authoritative INR quotes, atomic stock reservations, multi-seller orders, idempotent checkout, durable
capture/refund simulator, recoverable workflow, signed webhooks, shipments, delivered-item returns, reviews and local
notifications. Customer, seller and admin interfaces are served by the backend.

This is a production-oriented baseline, **not completion of the entire master specification or a
production-ready/compliant platform**. Read [known limitations](docs/limitations.md)
and [verification evidence](docs/verification.md) before deployment. No real payment details or paid services are
required.

## Run locally

Requires Docker Compose. From this directory:

```sh
cp .env.example .env
# Edit .env development passwords/secrets before use.
docker compose up -d --build
```

Open **http://localhost:8097**. Sign in as `customer` / `customer2`, `seller` / `seller2`, or `admin`, using
LOCAL_PASSWORD from .env. Seeded two-seller products have 100 units per offer. Add goods, save an Indian address,
calculate a quote and place an order. Refresh order list after the asynchronous payment simulator runs. Seller desk can
change price/stock and ship whole order lines. Admin can approve/suspend, create products/variants, simulate delivery
and inspect returns. No real charges occur.

```sh
docker compose logs -f app
docker compose down
# down -v intentionally destroys the sandbox database; avoid unless desired.
```

## Build and test

Java **21** and Maven 3.6.3+:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; use your JDK21 path elsewhere
mvn test                         # unit tests
mvn -Pintegration verify         # all tests, requires Docker / Testcontainers
mvn package
```

For OrbStack, if Docker discovery needs it:

```sh
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn -Pintegration verify
```

HTTP purchase/refund smoke against Compose:

```sh
export LOCAL_PASSWORD='the value configured in .env'
python3 scripts/smoke.py
```

Standalone backend requires PostgreSQL migrated automatically and these environment variables:

```sh
export SPRING_PROFILES_ACTIVE=local
export DB_URL=jdbc:postgresql://localhost:5432/marketplace
export DB_USER=marketplace
export DB_PASSWORD='your database password'
export LOCAL_PASSWORD='your local development login password'
export WEBHOOK_SECRET='a development webhook secret at least 32 characters'
java -jar target/ecommerce-marketplace-1.0.0-SNAPSHOT.jar
```

Standalone default port is 8080; set PORT=8097 if desired. Redis caching defaults off standalone; CACHE_ENABLED=true and
REDIS_HOST enable it with DB fallback.

## Architecture and project map

One deployable, short READ COMMITTED SQL transactions, conditional stock update, stable lock ordering, durable payment
Saga and outbox/inbox. Provider calls commit separately from checkout. INR minor units, immutable net line snapshots and
refund budgets enforce monetary consistency. Product identity and SKU variants are distinct from seller offers and
stock.

* `src/main/java/.../catalog`: customer/seller/catalog/reviews and Redis category cache.
* `checkout`: cart, quotes, atomic checkout, leased payment/refund worker and outbox.
* `payment`: provider port, durable local simulator and signed webhook processing.
* `fulfillment`: shipping, cancellations, physical returns and refunds.
* `configuration`, `shared`: security, overload admission, metrics, JDBC/auth helpers and money rules.
* `src/main/resources/db/migration`: PostgreSQL schema/checks/indexes and sandbox seed.
* `src/main/resources/static`: functional customer/seller/admin UI.
* `src/test`: exact-money tests and real PostgreSQL concurrency/integration tests.
* `compose.yml`, `Dockerfile`, `kubernetes`, root `.github/workflows/marketplace.yml`: sandbox deployment and CI.
* `benchmarks`, `monitoring`: reproducible k6 workloads, alerts and Grafana dashboard.

Phase review: [implementation status](docs/implementation-status.md). Database reference: [schema](docs/schema.md).

Design: [requirements](docs/requirements.md), [architecture](docs/architecture.md), [bounded contexts / UML](docs/bounded-contexts.md), [inventory](docs/inventory-consistency.md), [checkout Saga](docs/checkout-saga.md), [payment reliability](docs/payment-reliability.md), [API](docs/api.md) / [OpenAPI](docs/openapi.json), [capacity estimates](docs/capacity-planning.md), [35 failure scenarios](docs/failure-analysis.md), [40-question architecture review](docs/architecture-review.md), [security](docs/security.md), [runbook](docs/runbook.md), [ADR](docs/decisions/0001-baseline.md).

## Deployment boundaries

Kubernetes manifest is an **unverified sandbox overlay**. Create marketplace-secrets externally with DB_PASSWORD,
LOCAL_PASSWORD and WEBHOOK_SECRET; provision external PostgreSQL/Redis and correct ConfigMap endpoints/image. TLS
ingress, secure cookies, network policies, secret management and session architecture need deployment-specific work. The
production profile supports OIDC browser login and JWT APIs with shared PostgreSQL sessions. The stripe profile selects
real hosted Stripe test Checkout; live-money keys are rejected.
See [authentication and Stripe setup](docs/production-auth-and-stripe.md).

Kafka transport, Shippo rate/tracking and Stripe Tax calculation adapters are implemented. OpenSearch, object storage,
tax filing/remittance, KYC and payouts remain outside the current implementation. Label purchasing and provider tax
sale/reversal recording are implemented. Local notifications use durable DB inbox. Stripe webhooks use a separate
durable provider inbox. Large-scale capacity, resilience, compliance and Kubernetes behavior are not claimed. See the
explicit gap list rather than interpreting a passing build as production readiness.

## Authentication and Stripe continuation

Production OIDC login, stateless audience-validated JWT APIs, shared JDBC sessions and session revocation are
implemented. Sticky routing is no longer required. Real Stripe **test-mode** hosted Checkout, signed durable webhook
ingestion, pending-refund polling and provider-independent recovery are implemented. Configure secrets and run
`docker compose -f compose.yml -f compose.stripe.yml up -d --build` for local login + hosted payments, or
`compose.production.yml` for external OIDC + TLS. [Setup and recovery contract](docs/production-auth-and-stripe.md).

The test suite now includes signed HTTP OIDC/JWKS/token exchange across two app instances and actual HTTP
timeout/DB-failure injection around the Stripe adapter. External Stripe/IdP credentials were unavailable, so
account-level sandbox interoperability remains unverified.

## Kafka, tax/shipping and partial quantities

Actual Kafka outbox transport, consumer inbox deduplication, DLT/replay and failure recovery are implemented. Shippo
test rate/tracking and Stripe Tax calculation HTTP adapters feed immutable quote shipping/tax snapshots. Shipments and
returns support partial quantities with scoped request keys, concurrency-safe limits and exact per-unit refunds. The UI
exposes quantity selection and shipping/tax totals.

```sh
docker compose -f compose.yml -f compose.kafka.yml up -d --build
# With SHIPPO_TEST_TOKEN and STRIPE_TAX_TEST_KEY configured:
docker compose -f compose.yml -f compose.kafka.yml -f compose.logistics.yml up -d --build
```

[Provider setup, quantity APIs and recovery details](docs/kafka-shipping-tax-and-quantities.md). Java21 verification
passed **50 tests** including real Kafka/PostgreSQL and HTTP provider failure tests. The Kafka-enabled HTTP
purchase/refund smoke passed. Live Shippo/Stripe Tax account interoperability remains unverified because credentials
were unavailable.

## Label purchasing and tax transaction reporting

Implemented Shippo test label purchases with immutable package snapshots, seller cost ceilings, reserved quantities,
owner-only downloads, link refresh, physical dispatch and manual recovery. Unknown Shippo purchase responses are
reconciled without repeating the non-idempotent purchase POST. Stripe Tax sale and full/partial reversal jobs use stable
keys, stored provider IDs, refund-completion dependencies, exact net/tax components and historical catch-up.

**67 tests passed**; the Docker HTTP simulator label purchase -> dispatch -> delivery -> two partial refunds flow and
the original multi-seller purchase/cancellation flow passed.

```sh
mvn -Pintegration verify
python3 scripts/smoke-labels.py  # LOCAL_PASSWORD configured; local providers selected
```

[Label/tax configuration, API contracts and recovery runbook](docs/labels-and-tax-reporting.md). Live provider keys
remain disabled; external account verification still requires sandbox credentials.

## Next.js frontend

A separate responsive customer, seller and admin frontend is available in [frontend](frontend/README.md). Run the
backend as above, then follow the frontend setup instructions. Backend code and the existing static interface are
unchanged.
