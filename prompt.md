# MASTER PROMPT: Design and Develop a Production-Grade E-commerce Marketplace Like Amazon Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Solution Architect, Java 21 Expert,
E-commerce Domain Architect, Payment Systems Engineer, Database Architect, Cloud Architect, Security Architect, and Site
Reliability Engineer** with 15+ years of experience designing high-scale marketplaces and transaction-processing
platforms.

Your task is to **design, develop, test, benchmark, document, and deploy an end-to-end multi-vendor E-commerce
Marketplace inspired by Amazon, Walmart Marketplace, and Flipkart**, using Java 21 and Spring Boot.

Treat this as a real enterprise engineering project, not a theoretical system design interview exercise.

Your responsibilities include:

1. Requirements analysis and architectural decisions
2. High-Level Design (HLD)
3. Low-Level Design (LLD)
4. Domain modeling and bounded contexts
5. Customer and seller management
6. Product catalog and search
7. Pricing, promotions, and taxes
8. Inventory and stock reservations
9. Shopping carts and wishlists
10. Checkout and order management
11. Payment orchestration
12. Distributed transactions and Saga workflows
13. Fulfillment and shipment tracking
14. Returns, refunds, and cancellations
15. Reviews, ratings, and notifications
16. Security and fraud-prevention controls
17. Distributed caching and event-driven processing
18. Reliability, observability, and disaster recovery
19. Automated tests and performance benchmarks
20. Docker/Kubernetes deployment
21. Functional customer and seller interfaces
22. Complete source code and operational documentation

**Do not stop after diagrams, pseudocode, architectural recommendations, or partial implementations. Deliver a working,
maintainable, secure, testable marketplace with source code, infrastructure, functional user interfaces, and
documentation.**

### Mandatory Engineering Principles

- Use Java 21 and a verified compatible Spring Boot release.
- Follow existing repository instructions, architecture, dependency versions, and coding conventions.
- Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
- Define clear bounded contexts and domain ownership.
- Prefer a modular monolith as an initial implementation unless independent service deployment is justified.
- Design a credible migration path to microservices.
- Use transactions only within their valid boundaries.
- Address idempotency, concurrency, authorization, eventual consistency, and failure recovery explicitly.
- Use bounded retries, timeouts, connection pools, thread pools, and queues.
- Use `BigDecimal` or integer minor units for money; never binary floating-point types.
- Use an explicit currency and rounding policy.
- Do not store raw payment-card credentials or sensitive authentication data.
- Do not invent distributed consistency or exactly-once guarantees.
- Never fabricate test results, performance measurements, or production readiness.
- Make routine technical choices independently and explain important trade-offs.

---

# PART A — PRODUCT OBJECTIVE

## 2. PRIMARY OBJECTIVE

Build a horizontally scalable, multi-vendor marketplace where customers can discover products, compare seller offers,
purchase goods, track orders, and request returns or refunds.

Sellers must be able to manage their product offers, inventory, prices, and fulfillment.

Marketplace administrators must be able to manage sellers, categories, policies, disputes, and operational workflows.

### Example end-to-end purchase scenario

A customer purchases two products from two different sellers.

The system must:

1. Authenticate the customer.
2. Retrieve the shopping cart.
3. Validate product and seller availability.
4. Recalculate authoritative prices, promotions, and taxes.
5. Verify shipping eligibility.
6. Create an immutable checkout quote.
7. Reserve required inventory atomically.
8. Create a durable order and seller-specific order lines.
9. Initiate payment through an external payment provider.
10. Handle synchronous payment responses and asynchronous webhooks safely.
11. Confirm the order after the defined payment milestone.
12. Commit or release inventory reservations according to workflow state.
13. Trigger fulfillment for eligible seller orders.
14. Send order confirmation notifications.
15. Support shipment tracking.
16. Allow cancellation, return, and refund requests.
17. Recover from payment timeouts, duplicate requests, inventory races, worker crashes, and partial fulfillment
    failures.

Do not assume that inventory reservation, payment authorization, and order fulfillment can be committed in a single
distributed ACID transaction.

---

# PART B — FUNCTIONAL REQUIREMENTS

## 3. USER AND IDENTITY MANAGEMENT

Implement:

- Customer accounts
- Seller accounts
- Marketplace administrators
- User profiles
- Address book
- Authentication
- Authorization
- Account deactivation
- Session management
- User preferences

Use Spring Security with OAuth2/OIDC integration or an established identity provider.

Support role-based and resource-level authorization.

Do not trust user-supplied seller or customer IDs without validating authenticated ownership.

---

## 4. SELLER MANAGEMENT

Implement:

- Seller registration
- Seller onboarding
- Seller profile
- Seller approval
- Seller suspension
- Seller product offers
- Seller inventory
- Seller pricing
- Seller order management
- Seller fulfillment settings
- Seller performance metrics

Define states such as:

- PENDING_VERIFICATION
- ACTIVE
- SUSPENDED
- REJECTED
- CLOSED

Provide administrative approval and audit capabilities.

External KYC, tax, and seller-payout integrations may use documented provider adapters or sandbox implementations.

Do not pretend mocked compliance integrations provide real regulatory verification.

---

## 5. PRODUCT CATALOG

Design a marketplace catalog where one product can have multiple seller offers.

Distinguish:

**Product**

Represents the canonical product identity and attributes.

**SKU / Product Variant**

Represents a specific sellable configuration, such as size, color, or model.

**Seller Offer**

Represents a seller's price, availability, condition, fulfillment method, and selling terms for a specific SKU.

Example:

Product: Apple iPhone model

Variant: 256 GB, Black

Seller A Offer: ₹75,000

Seller B Offer: ₹76,500

Implement:

- Categories
- Product attributes
- Variants
- Seller offers
- Product images
- Product status
- Product detail pages
- Offer comparisons
- Pagination
- Catalog administration

Ensure product identity is not confused with seller inventory ownership.

---

## 6. PRODUCT SEARCH AND DISCOVERY

Support:

- Keyword search
- Category filtering
- Brand filtering
- Price filtering
- Seller filtering
- Availability filtering
- Sorting by price, popularity, and relevance
- Search suggestions
- Product recommendations
- Faceted search

Start with PostgreSQL search where sufficient.

Evaluate Elasticsearch or OpenSearch for large catalogs, faceting, relevance scoring, and autocomplete.

If a search index is implemented, treat PostgreSQL as the source of truth and define asynchronous indexing, rebuilding,
and stale-result behavior.

Never expose unavailable, unpublished, or unauthorized offers solely because they remain in a stale search index.

---

## 7. PRICING AND PROMOTIONS

Implement:

- Base price
- Seller-specific offer price
- Currency
- Promotional discounts
- Coupons
- Pricing validity
- Shipping charges
- Tax calculation integration
- Checkout price snapshots

Define deterministic discount application order and rounding rules.

Support configurable coupon eligibility, usage limits, expiration, and customer restrictions.

### Critical correctness requirements

- Clients cannot dictate authoritative prices.
- Checkout uses validated pricing.
- Order line prices are immutable snapshots.
- Coupon usage is concurrency-safe.
- Discounts cannot produce unintended negative totals.
- Promotion changes do not retroactively modify confirmed orders.

Use `BigDecimal` or minor-unit integers with an explicit currency model.

Avoid hardcoded tax rules for all jurisdictions.

---

# PART C — SHOPPING CART

## 8. CART MANAGEMENT

Implement:

- Add item
- Update quantity
- Remove item
- Retrieve cart
- Clear cart
- Guest cart where justified
- Authenticated cart
- Cart merge after login
- Cart expiration
- Cart validation before checkout

Evaluate Redis versus PostgreSQL for cart persistence.

Define whether cart changes are durable or may be lost under cache failure.

A shopping cart is not an inventory reservation.

Do not promise product availability merely because an item appears in a cart.

---

# PART D — INVENTORY MANAGEMENT

## 9. INVENTORY MODEL

Implement inventory ownership per seller and SKU, with warehouse/location support where required.

Model:

- On-hand stock
- Reserved stock
- Available-to-sell stock
- Inventory adjustments
- Reservation expiration
- Committed allocation
- Inventory movement history

Define:

`Available to sell = On hand − Active reservations − Other committed allocations not already deducted`

Use one internally consistent accounting convention to avoid double subtraction.

### Required capabilities

- Stock updates
- Inventory lookup
- Inventory reservation
- Reservation confirmation
- Reservation release
- Reservation expiration
- Stock adjustment
- Low-stock alerts

---

## 10. PREVENTING OVERSELLING

Analyze concurrent purchasing.

Example:

- Available stock = 1.
- Customer A purchases one unit.
- Customer B purchases one unit simultaneously.
- Only one inventory reservation may succeed.

Compare:

1. Pessimistic database locking
2. Optimistic locking
3. Conditional atomic SQL updates
4. Redis-based reservations
5. Serialized partitioned inventory commands

Choose a correct initial strategy.

Prefer database-enforced conditional inventory updates or suitable row locking for the baseline.

Define:

- Transaction isolation
- Lock duration
- Deadlock handling
- Reservation idempotency
- Expiration races
- Commit/release races
- Concurrent stock adjustments

Do not rely solely on Redis counters without a reconciled authoritative inventory model.

---

## 11. FLASH SALE ARCHITECTURE

Design for sudden high-volume traffic.

Example:

10,000 units are offered while one million customers attempt checkout.

Evaluate:

- Admission control
- Rate limiting
- Virtual waiting rooms
- Redis caching
- Stock token allocation
- Queue-based processing
- Fairness
- Bot mitigation
- Backpressure

Distinguish admission to checkout from guaranteed purchase.

Implement a representative flash-sale workflow or a clearly isolated optional module.

Explain how the system prevents overselling while preserving bounded resource usage.

---

# PART E — CHECKOUT AND ORDER MANAGEMENT

## 12. CHECKOUT WORKFLOW

Implement:

1. Cart validation
2. Address validation
3. Shipping option selection
4. Authoritative price calculation
5. Promotion validation
6. Tax calculation
7. Checkout quote creation
8. Inventory reservation
9. Order initiation
10. Payment authorization
11. Order confirmation or compensation

Define transaction boundaries explicitly.

Include checkout quote expiration and a deterministic total calculation.

Do not trust client-calculated totals.

---

## 13. ORDER DOMAIN MODEL

Support:

- Marketplace order
- Seller-specific order groups
- Order items
- Order status
- Payment status
- Fulfillment status
- Shipment status
- Cancellation
- Return
- Refund

A marketplace order may contain multiple seller fulfillments.

Model partial shipment, cancellation, and refund correctly.

### Order states

Define an explicit state machine, for example:

- PENDING
- INVENTORY_RESERVED
- PAYMENT_PENDING
- CONFIRMED
- PARTIALLY_FULFILLED
- FULFILLED
- CANCELLED
- CLOSED

Keep payment and fulfillment states separate when a single status would create ambiguity.

Use guarded state transitions and optimistic concurrency control.

---

# PART F — PAYMENT ORCHESTRATION

## 14. PAYMENT INTEGRATION

Implement payment orchestration through a provider abstraction.

Support:

- Payment authorization
- Payment capture
- Payment failure
- Payment cancellation/void
- Refund initiation
- Refund status
- Webhook processing
- Reconciliation

Use a realistic sandbox provider or deterministic local payment simulator.

Never store raw card numbers, CVVs, or sensitive payment credentials.

Do not claim the system is PCI-DSS compliant merely because it uses a hosted payment provider.

### Mandatory payment rules

- Use scoped idempotency keys for payment operations.
- Persist provider request and response references.
- Verify webhook authenticity.
- Deduplicate webhook events.
- Handle out-of-order payment updates.
- Never retry ambiguous charges without checking provider state or using provider-supported idempotency.
- Maintain an auditable payment state machine.
- Reconcile provider-side payment state with local state.

---

## 15. PAYMENT STATE MACHINE

Define states such as:

- CREATED
- AUTHORIZATION_PENDING
- AUTHORIZED
- AUTHORIZATION_FAILED
- CAPTURE_PENDING
- CAPTURED
- VOIDED
- REFUND_PENDING
- PARTIALLY_REFUNDED
- REFUNDED
- FAILED

Model transitions based on the selected provider's actual payment lifecycle.

Do not assume all payment providers support separate authorization and capture.

Implement tests for duplicate, delayed, and contradictory webhook events.

---

# PART G — DISTRIBUTED TRANSACTIONS AND SAGA

## 16. SAGA ARCHITECTURE

Design a durable checkout Saga.

Evaluate:

- Choreography-based Saga
- Orchestration-based Saga

For the initial implementation, prefer a clearly controlled workflow, especially for payment and inventory operations.

Example steps:

1. Create checkout workflow
2. Reserve stock
3. Initiate order
4. Authorize payment
5. Confirm order
6. Trigger fulfillment

Possible compensations:

- Release inventory
- Void payment authorization
- Cancel order
- Initiate refund when capture already occurred

### Critical requirements

- Durable Saga state
- Idempotent steps
- Idempotent compensation
- Step timeouts
- Bounded retries
- Resume after worker crash
- Manual intervention states
- Audit trail
- Reconciliation

Do not assume compensation always restores the exact original state.

For example, a captured payment may require an asynchronous refund rather than an immediate rollback.

---

## 17. TRANSACTIONAL OUTBOX

Use transactional outbox for durable publication of events associated with database state changes.

Example events:

- OrderCreated
- InventoryReserved
- InventoryReservationFailed
- PaymentAuthorized
- PaymentCaptured
- PaymentFailed
- OrderConfirmed
- OrderCancelled
- ShipmentCreated
- RefundInitiated
- RefundCompleted

Implement:

- Atomic state + outbox persistence
- Outbox relay
- Event IDs
- Schema versioning
- Idempotent consumers
- Duplicate delivery handling
- Replay
- Retry/DLQ strategy

Do not claim exactly-once business outcomes solely because Kafka transactions are available.

---

# PART H — FULFILLMENT AND SHIPPING

## 18. ORDER FULFILLMENT

Support:

- Seller order acceptance
- Warehouse allocation
- Pick/pack status
- Shipment creation
- Carrier reference
- Tracking events
- Delivery confirmation
- Partial shipments

Separate order confirmation from physical fulfillment.

Provide a shipping carrier adapter with local simulation for development.

Implement reconciliation for missing or delayed carrier updates.

---

## 19. RETURNS, CANCELLATIONS, AND REFUNDS

Implement:

- Cancellation request
- Cancellation eligibility
- Return request
- Return authorization
- Return received
- Inspection outcome
- Refund initiation
- Partial refund
- Refund completion

Define policy-based eligibility and state transitions.

Ensure:

- A refund is not accidentally issued twice.
- A cancelled order cannot continue fulfillment unless explicitly recoverable.
- Inventory restocking occurs only according to physical return condition and business policy.
- Partial returns correctly map to order items.
- Payment records remain auditable.

---

# PART I — REVIEWS, RATINGS, AND NOTIFICATIONS

## 20. REVIEWS AND RATINGS

Implement:

- Product reviews
- Star ratings
- Review updates
- Review moderation
- Verified purchase eligibility
- Review pagination
- Aggregate ratings

Prevent repeated ratings from incorrectly inflating statistics.

Define how deleted or moderated reviews affect aggregates.

---

## 21. NOTIFICATIONS

Support:

- Order confirmation
- Payment outcome
- Shipment updates
- Delivery confirmation
- Cancellation
- Refund updates
- Seller notifications

Use asynchronous notification processing where justified.

Implement local development adapters and provider integrations.

Do not block order confirmation solely because an email or notification provider is unavailable.

---

# PART J — HIGH-LEVEL DESIGN

## 22. ARCHITECTURE ALTERNATIVES

Compare:

### Option A — Modular Monolith

One Spring Boot deployable with modules for:

- Identity
- Catalog
- Seller Offers
- Pricing
- Cart
- Inventory
- Checkout
- Orders
- Payments
- Fulfillment
- Returns
- Reviews

### Option B — Domain-Oriented Microservices

Separate independently deployable services according to validated scaling, ownership, and failure-isolation
requirements.

### Option C — Globally Distributed Marketplace

Regional customer APIs, geographically partitioned catalogs, regional inventory ownership, and multi-region transaction
workflows.

Evaluate:

- Operational complexity
- Transaction consistency
- Team ownership
- Independent scaling
- Latency
- Reliability
- Deployment cost

**Recommend a modular monolith as the working baseline unless the project requirements justify service decomposition.**

Document an incremental extraction strategy for high-volume domains such as search, catalog, or asynchronous
fulfillment.

---

## 23. REFERENCE COMPONENTS

Consider:

1. API Gateway
2. Authentication
3. Customer Management
4. Seller Management
5. Product Catalog
6. Offer Management
7. Search
8. Pricing Engine
9. Promotion Engine
10. Cart
11. Inventory
12. Checkout
13. Order Management
14. Payment Orchestrator
15. Saga Coordinator
16. Fulfillment
17. Returns/Refunds
18. Reviews
19. Notifications
20. PostgreSQL
21. Redis
22. Kafka
23. Object Storage
24. Monitoring/Tracing

Provide Mermaid diagrams for:

- Overall architecture
- Catalog browsing
- Shopping cart
- Inventory reservation
- Checkout Saga
- Payment webhook processing
- Order fulfillment
- Cancellation/refund
- Flash-sale flow

Separate synchronous customer-facing operations from asynchronous background workflows.

---

# PART K — CAPACITY PLANNING

## 24. TARGET SCALE

Treat these as design targets, not verified capacity claims.

| Dimension               | Target                                             |
|-------------------------|----------------------------------------------------|
| Registered customers    | 100 million+                                       |
| Active sellers          | 1 million+                                         |
| Product catalog         | 100 million SKUs                                   |
| Daily active customers  | 10 million                                         |
| Product browse requests | 1 million RPS peak                                 |
| Search requests         | 100,000 RPS peak                                   |
| Order creation          | 10,000 orders/sec peak                             |
| Flash-sale attempts     | 1 million requests/sec scenario                    |
| Availability            | 99.99% target                                      |
| Browse latency          | p99 below 200 ms within a region                   |
| Checkout latency        | Explicitly separated from payment-provider latency |
| Inventory consistency   | No overselling under defined reservation semantics |
| Payment correctness     | Idempotent financial effects                       |
| Deployment              | Multi-AZ with multi-region extension               |

Estimate:

- Catalog storage
- Search index size
- Cart storage
- Inventory transaction throughput
- Order storage growth
- Payment event volume
- Kafka throughput
- Redis memory
- Database IOPS
- CDN bandwidth
- Application instance count

Use formulas and document assumptions.

Do not invent hardware performance numbers.

---

# PART L — LOW-LEVEL DESIGN

## 25. DOMAIN MODEL

Define concepts such as:

- Customer
- Seller
- Product
- ProductVariant
- SellerOffer
- Price
- Money
- InventoryItem
- InventoryReservation
- Cart
- CartItem
- CheckoutQuote
- Order
- SellerOrder
- OrderItem
- Payment
- PaymentAttempt
- Shipment
- Refund
- ReturnRequest
- Promotion
- Coupon
- Review
- OutboxEvent

Separate aggregates and bounded contexts.

Define which aggregate owns each invariant.

Do not make one giant Order aggregate responsible for every marketplace function.

Provide:

- UML class diagrams
- Sequence diagrams
- State machines
- Domain events
- Application services
- Repository interfaces
- Error handling contracts
- Transaction boundaries

---

## 26. POSTGRESQL DATA MODEL

Design schemas for:

- customers
- sellers
- products
- product_variants
- seller_offers
- inventory
- inventory_reservations
- carts
- cart_items
- checkout_quotes
- orders
- seller_orders
- order_items
- payments
- payment_attempts
- payment_webhook_events
- shipments
- returns
- refunds
- promotions
- coupon_redemptions
- reviews
- outbox_events
- processed_events

Create only the tables needed by implemented functionality.

Define:

- Primary keys
- Foreign keys
- Unique constraints
- Check constraints
- Indexes
- Optimistic locking
- State transition rules
- Audit timestamps
- Retention
- Partitioning considerations

Use Flyway migrations.

### Important data integrity requirements

- Inventory quantities cannot become invalid under concurrent updates.
- Order monetary totals reconcile with immutable line-item snapshots.
- Duplicate checkout requests cannot create duplicate orders within their idempotency scope.
- Payment retries cannot trigger duplicate charges under supported provider semantics.
- Refund totals cannot exceed eligible captured amounts.
- Coupon usage limits are concurrency-safe.
- Seller operations are properly tenant-scoped.

---

# PART M — REDIS CACHING

## 27. CACHE DESIGN

Evaluate caching for:

- Product details
- Category data
- Seller offers
- Shopping carts
- Search suggestions
- Popular product lists
- Availability hints
- Pricing configuration

Define:

- Cache keys
- TTL
- Cache invalidation
- Cache-aside
- Negative caching
- Hot-key protection
- Stampede prevention
- Eviction
- Redis outage fallback

### Critical rule

**Do not treat cached product availability or cached prices as authoritative during checkout.**

Authoritative pricing and inventory must be validated before confirming a transaction.

---

# PART N — KAFKA AND EVENT-DRIVEN PROCESSING

## 28. KAFKA ARCHITECTURE

Design events such as:

- ProductUpdated
- OfferPriceChanged
- InventoryUpdated
- InventoryReserved
- OrderCreated
- PaymentAuthorized
- PaymentCaptured
- OrderConfirmed
- OrderCancelled
- ShipmentUpdated
- RefundCompleted

Define:

- Event IDs
- Aggregate IDs
- Aggregate versions
- Schema versions
- Correlation IDs
- Partition keys
- Consumer groups
- Retention
- Replay
- Retry and DLQ behavior

Use ordering guarantees only within their supported partition scope.

Ensure consumers are idempotent.

Use transactional outbox where database state changes must reliably produce events.

---

# PART O — SEARCH ARCHITECTURE

## 29. PRODUCT SEARCH

Compare PostgreSQL full-text search and OpenSearch/Elasticsearch.

Evaluate:

- Indexing latency
- Faceted search
- Autocomplete
- Relevance scoring
- Search suggestions
- Catalog freshness
- Index rebuilding
- Operational cost

If OpenSearch is selected:

- Implement asynchronous indexing.
- Deduplicate index updates.
- Handle out-of-order product events.
- Support index versioning.
- Implement safe reindexing.
- Filter inactive products and seller offers.

Search results may be eventually consistent, but checkout must use authoritative data.

---

# PART P — JAVA 21 + SPRING BOOT STACK

## 30. TECHNOLOGY STACK

Use:

| Component           | Technology                                           |
|---------------------|------------------------------------------------------|
| Language            | Java 21                                              |
| Framework           | Spring Boot, verified compatible stable release      |
| Build               | Maven                                                |
| REST API            | Spring Web MVC                                       |
| Security            | Spring Security                                      |
| Persistence         | PostgreSQL                                           |
| Data access         | Spring Data JPA/JDBC, as justified                   |
| Database migrations | Flyway                                               |
| Cache               | Redis / Lettuce                                      |
| Event processing    | Apache Kafka                                         |
| Search              | PostgreSQL FTS initially; OpenSearch where justified |
| Product media       | Amazon S3-compatible object storage                  |
| Local storage       | MinIO or equivalent                                  |
| Payments            | Provider sandbox + local simulator                   |
| Observability       | Micrometer / OpenTelemetry                           |
| Unit tests          | JUnit 5 / Mockito                                    |
| Integration tests   | Testcontainers                                       |
| Performance tests   | k6 or Gatling                                        |
| Containers          | Docker                                               |
| Deployment          | Kubernetes                                           |
| Monitoring          | Prometheus / Grafana                                 |

Verify compatible versions using official documentation.

Evaluate Java 21 virtual threads for blocking I/O, but do not assume they automatically improve database throughput.

Do not introduce WebFlux unless justified by measured requirements.

---

# PART Q — PROJECT STRUCTURE

## 31. SOURCE CODE ORGANIZATION

Use a maintainable structure such as:

```text
ecommerce-marketplace/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── bounded-contexts.md
│   ├── checkout-saga.md
│   ├── inventory-consistency.md
│   ├── payment-reliability.md
│   ├── capacity-planning.md
│   ├── security.md
│   ├── runbook.md
│   └── decisions/
├── src/
│   ├── main/
│   │   ├── java/com/example/marketplace/
│   │   │   ├── identity/
│   │   │   ├── customer/
│   │   │   ├── seller/
│   │   │   ├── catalog/
│   │   │   ├── offer/
│   │   │   ├── pricing/
│   │   │   ├── promotion/
│   │   │   ├── cart/
│   │   │   ├── inventory/
│   │   │   ├── checkout/
│   │   │   ├── order/
│   │   │   ├── payment/
│   │   │   ├── fulfillment/
│   │   │   ├── returns/
│   │   │   ├── review/
│   │   │   ├── notification/
│   │   │   ├── shared/
│   │   │   └── configuration/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/
├── customer-web/
├── seller-portal/
├── admin-portal/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Adapt the structure to actual bounded contexts.

Avoid empty modules, unnecessary layers, and speculative abstractions.

---

# PART R — API DESIGN

## 32. REQUIRED REST APIS

Implement versioned APIs.

### Customer

- `GET /api/v1/customers/me`
- `PATCH /api/v1/customers/me`
- `GET /api/v1/customers/me/addresses`

### Seller

- `POST /api/v1/sellers`
- `GET /api/v1/sellers/{sellerId}`
- `POST /api/v1/sellers/{sellerId}/offers`
- `PATCH /api/v1/sellers/{sellerId}/offers/{offerId}`

### Catalog

- `GET /api/v1/products`
- `GET /api/v1/products/{productId}`
- `GET /api/v1/categories`
- `GET /api/v1/search/products`

### Cart

- `GET /api/v1/cart`
- `POST /api/v1/cart/items`
- `PATCH /api/v1/cart/items/{itemId}`
- `DELETE /api/v1/cart/items/{itemId}`

### Inventory

- `GET /api/v1/offers/{offerId}/availability`
- `POST /api/v1/sellers/{sellerId}/inventory/adjustments`

### Checkout and Orders

- `POST /api/v1/checkout/quotes`
- `POST /api/v1/checkouts`
- `GET /api/v1/orders`
- `GET /api/v1/orders/{orderId}`
- `POST /api/v1/orders/{orderId}/cancel`

### Payments

- `POST /api/v1/payments/{paymentId}/confirm`
- `POST /api/v1/payments/webhooks/{provider}`

### Fulfillment

- `GET /api/v1/orders/{orderId}/shipments`
- `POST /api/v1/seller-orders/{sellerOrderId}/shipments`

### Returns/Refunds

- `POST /api/v1/orders/{orderId}/returns`
- `GET /api/v1/returns/{returnId}`
- `GET /api/v1/orders/{orderId}/refunds`

### Reviews

- `POST /api/v1/products/{productId}/reviews`
- `GET /api/v1/products/{productId}/reviews`

Provide:

- Request/response examples
- Authentication and authorization
- Validation
- Idempotency rules
- HTTP statuses
- Pagination
- Error contracts
- OpenAPI documentation

Use RFC 9457 Problem Details where appropriate.

---

# PART S — SECURITY AND COMPLIANCE

## 33. SECURITY REQUIREMENTS

Implement:

1. Authentication and session security
2. Role-based and resource-based authorization
3. Seller isolation
4. Payment webhook signature verification
5. Payment-provider credential management
6. Sensitive data minimization
7. Address and customer information protection
8. Rate limiting
9. Bot/abuse protection
10. Administrative audit logging
11. Secure object-storage access
12. HTTPS/TLS
13. Input validation
14. Secure secrets management
15. CSRF protection where relevant
16. CORS configuration
17. Fraud detection integration points
18. Refund authorization
19. Seller suspension enforcement
20. Data retention and deletion policies

Discuss PCI DSS, data protection, and applicable regional commerce regulations as deployment-specific requirements.

Do not claim regulatory compliance without evidence.

---

# PART T — RELIABILITY AND FAILURE ANALYSIS

## 34. REQUIRED PRODUCTION SCENARIOS

Analyze and test these 35 scenarios:

1. Two customers purchase the last inventory unit concurrently.
2. Inventory reservation succeeds but order creation fails.
3. Order commits but response is lost.
4. Customer retries checkout.
5. Payment authorization times out.
6. Provider captures payment but local response is lost.
7. Payment webhook arrives twice.
8. Payment webhook arrives before synchronous API response.
9. Payment webhook events arrive out of order.
10. Payment succeeds but order confirmation fails.
11. Inventory reservation expires during payment.
12. Customer cancels while payment is pending.
13. Inventory release is retried.
14. Coupon reaches usage limit during concurrent checkouts.
15. Seller changes price during checkout.
16. Product becomes unavailable during checkout.
17. Redis becomes unavailable.
18. PostgreSQL primary fails.
19. Kafka becomes unavailable.
20. Saga coordinator crashes midway through checkout.
21. Outbox publisher publishes an event twice.
22. Order events are consumed out of order.
23. Flash sale causes database connection exhaustion.
24. Popular product becomes a cache hot key.
25. Seller is suspended with open orders.
26. Shipment is partially fulfilled.
27. Carrier sends duplicate tracking events.
28. Customer requests refund twice.
29. Partial refund exceeds captured amount.
30. Return is processed while a refund is already pending.
31. Search index contains stale product data.
32. Regional outage occurs during checkout.
33. Rolling upgrade introduces incompatible event versions.
34. Payment provider becomes unavailable.
35. Fraud review delays or rejects a transaction.

For each scenario provide:

- Failure mechanism
- Correctness or financial risk
- User-visible impact
- Detection
- Prevention
- Recovery
- Automated test strategy
- Residual risk

Financial integrity must take precedence over convenience or optimistic availability claims.

---

# PART U — AUTOMATED TESTING

## 35. UNIT TESTS

Cover:

- Product and offer validation
- Pricing calculations
- Currency rounding
- Coupon eligibility
- Cart behavior
- Inventory availability
- Reservation state transitions
- Order state transitions
- Payment state transitions
- Saga transitions
- Refund limits
- Return eligibility
- Authorization rules

## 36. INTEGRATION TESTS

Use Testcontainers where appropriate for:

- PostgreSQL
- Redis
- Kafka
- OpenSearch, if implemented
- S3-compatible storage, if implemented

Verify:

- Flyway migrations
- Database uniqueness and constraints
- Inventory reservations
- Checkout
- Order persistence
- Outbox publication
- Payment webhook handling
- Payment idempotency
- Seller isolation
- Search indexing
- Fulfillment events

## 37. CONCURRENCY TESTS

Verify:

- Last-unit inventory purchase
- Parallel stock reservations
- Concurrent coupon redemptions
- Duplicate checkout requests
- Concurrent payment webhooks
- Order cancellation versus payment capture
- Double-refund prevention
- Inventory expiration versus confirmation
- Concurrent seller inventory updates

Prefer real database concurrency tests rather than mocks for transaction correctness.

## 38. FAILURE TESTS

Simulate:

- Database timeouts
- Redis outage
- Kafka outage
- Payment provider timeout
- Duplicate webhooks
- Lost responses
- Worker crashes
- Consumer rebalance
- Saga retry
- Connection pool exhaustion

Record actual verification evidence.

---

# PART V — PERFORMANCE ENGINEERING

## 39. LOAD TESTING

Build reproducible workloads for:

### Catalog browsing

- 1,000 RPS
- 10,000 RPS
- 100,000 RPS
- 1 million RPS as a scale target

### Search

- 1,000 RPS
- 10,000 RPS
- 100,000 RPS

### Checkout

- 100 orders/sec
- 1,000 orders/sec
- 10,000 orders/sec

### Flash sales

- 10,000 concurrent buyers
- 100,000 concurrent buyers
- 1 million purchase attempts

Measure:

- Offered/achieved RPS
- p50/p95/p99 latency
- Error rates
- Overselling incidents
- Checkout completion rate
- Duplicate charge incidents
- Inventory lock contention
- Database QPS
- Redis latency
- Kafka lag
- Saga completion time
- Payment provider latency
- JVM CPU/heap/GC
- Queue depth
- Infrastructure utilization

Never invent benchmark results.

Separate infrastructure estimates from actual measurements.

---

# PART W — MULTI-REGION ARCHITECTURE

## 40. GLOBAL MARKETPLACE DESIGN

Design a multi-region extension.

Evaluate:

- Regional browsing APIs
- Global product catalog
- Regional seller offers
- Inventory ownership
- Cross-region checkout
- Payment routing
- Order data residency
- Regional cache replication
- Disaster recovery
- Regional failover

Compare:

1. Single-region multi-AZ
2. Active-passive multi-region
3. Active-active multi-region

Explain why global active-active inventory writes require additional coordination or partitioned inventory ownership.

Do not claim globally consistent stock availability using asynchronously replicated counters alone.

---

# PART X — FRONTEND REQUIREMENTS

## 41. FUNCTIONAL CUSTOMER AND SELLER INTERFACES

Develop a functional minimal web frontend.

### Customer interface

Support:

- Product browsing
- Search/filtering
- Product details
- Seller offers
- Shopping cart
- Checkout
- Payment sandbox flow
- Order history
- Shipment tracking
- Return requests

### Seller interface

Support:

- Seller onboarding
- Product offer management
- Price updates
- Stock management
- Seller orders
- Shipment updates

### Admin interface

Support:

- Seller approval
- Seller suspension
- Catalog moderation
- Marketplace status inspection

Use a lightweight frontend stack appropriate for the scope.

Do not substitute static wireframes for functional interfaces.

Ensure all privileged operations are enforced by the backend.

---

# PART Y — DEPLOYMENT

## 42. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfile
- Docker Compose
- PostgreSQL
- Redis
- Kafka where implemented
- OpenSearch if implemented
- S3-compatible local storage where implemented
- Backend service
- Frontend
- Kubernetes manifests or Helm
- ConfigMaps
- Secret references
- Resource requests and limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployments
- CI/CD workflow

The local environment must support a full purchase flow without real payment credentials or paid external services.

---

# PART Z — OBSERVABILITY

## 43. METRICS AND ALERTING

Expose:

- Product API latency
- Search latency
- Cart operations
- Checkout attempts
- Checkout completion rate
- Inventory reservation success/failure
- Inventory lock contention
- Order creation latency
- Payment authorization latency
- Payment failures
- Duplicate webhook events
- Saga failures
- Saga timeouts
- Outbox backlog
- Kafka consumer lag
- Refund failures
- Fulfillment backlog

Use Micrometer, OpenTelemetry, Prometheus, and Grafana.

Avoid high-cardinality customer IDs, order IDs, and payment IDs as metric labels.

Provide alerts and runbooks for payment mismatches, overselling, stuck orders, and refund failures.

---

# PART AA — END-TO-END IMPLEMENTATION PLAN

## 44. DEVELOPMENT PHASES

Execute the project incrementally.

**Phase 1 — Architecture and Domain Modeling**

Define bounded contexts, requirements, business invariants, consistency models, deployment assumptions, and capacity
planning.

**Phase 2 — Catalog and Seller Management**

Implement sellers, products, variants, offers, persistence, migrations, and authorization.

**Phase 3 — Pricing and Shopping Cart**

Implement authoritative pricing, promotions, cart persistence, and checkout quote creation.

**Phase 4 — Inventory**

Implement inventory records, atomic reservations, release/commit, expiration, and concurrency tests.

**Phase 5 — Orders and Checkout**

Implement order management, checkout idempotency, durable workflows, and correct state transitions.

**Phase 6 — Payments and Saga**

Implement payment simulator/provider adapter, webhook validation, durable Saga orchestration, compensation, and
reconciliation.

**Phase 7 — Fulfillment and Returns**

Implement seller orders, shipping, cancellation, returns, and refunds.

**Phase 8 — Search, Reviews, and Notifications**

Implement discovery, reviews, event-driven indexing where justified, and notifications.

**Phase 9 — Frontend**

Implement functional customer, seller, and administration interfaces.

**Phase 10 — Reliability and Security**

Implement outbox, retries, observability, security controls, recovery workflows, and operational safeguards.

**Phase 11 — Deployment**

Implement Docker Compose, Kubernetes/Helm, CI/CD, configuration, and monitoring.

**Phase 12 — Testing and Performance**

Run unit, integration, concurrency, security, and load tests. Fix meaningful failures.

**Phase 13 — Final Architecture Review**

Review correctness, financial integrity, scalability, reliability, security, and operational cost.

For each phase:

- Implement actual source code.
- Keep the application buildable.
- Run available tests.
- Fix failures before progressing.
- Preserve unrelated work.
- Document important decisions.
- Clearly identify unverified behavior.

If repository and execution tools are available, use them to implement directly.

If unavailable, provide source files, their paths, and runnable commands without claiming execution.

---

# PART AB — REQUIRED DELIVERABLES

## 45. COMPLETE PROJECT OUTPUTS

Deliver:

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Assumptions
- Acceptance criteria
- Business invariants

### Deliverable 2 — High-Level Design

- Architecture diagrams
- Bounded contexts
- Checkout and payment flows
- Capacity planning
- Scaling strategy
- Multi-region approach

### Deliverable 3 — Low-Level Design

- UML class diagrams
- Sequence diagrams
- Domain models
- Database schema
- State machines
- Saga design
- API contracts

### Deliverable 4 — Backend Source Code

- Java 21/Spring Boot application
- Seller management
- Product catalog
- Seller offers
- Pricing
- Shopping cart
- Inventory
- Checkout
- Orders
- Payments
- Fulfillment
- Returns/refunds
- Reviews
- Event processing
- Security

### Deliverable 5 — Functional Frontend

- Customer interface
- Seller portal
- Administration interface
- Sandbox payment checkout

### Deliverable 6 — Automated Tests

- Unit tests
- Integration tests
- Concurrency tests
- Security tests
- Failure-recovery tests

### Deliverable 7 — Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Observability

### Deliverable 8 — Performance

- Load-test scripts
- Actual measurements where available
- Capacity estimates
- Bottleneck analysis
- Scale-out recommendations

### Deliverable 9 — Documentation

- README
- Architecture Decision Records
- API specification
- Inventory consistency guide
- Checkout Saga documentation
- Payment reconciliation guide
- Deployment instructions
- Operational runbook
- Known limitations

---

# PART AC — MANDATORY ARCHITECTURE REVIEW

## 46. SYSTEM DESIGN QUESTIONS

Answer these as part of the final architecture review.

1. How would you design Amazon for 100 million users?
2. Why separate product catalogs from seller offers?
3. How do you model products with multiple variants?
4. How would you scale product search?
5. Why is inventory reservation necessary?
6. How do you prevent overselling?
7. How do you handle the last-item concurrency problem?
8. What is the difference between cart availability and reserved inventory?
9. How do you design checkout?
10. How do you guarantee checkout idempotency?
11. How do you prevent duplicate payment charges?
12. How do you handle payment timeouts?
13. How do you process duplicate payment webhooks?
14. Why is a Saga needed?
15. What is orchestration versus choreography?
16. How does the transactional outbox work?
17. How do you recover a partially completed checkout?
18. What happens if payment succeeds but inventory reservation expires?
19. How do you ensure reliable inventory compensation?
20. How do you handle partial shipments?
21. How do you manage cancellations after payment capture?
22. How do you prevent duplicate refunds?
23. How do you enforce coupon redemption limits?
24. How do you manage multi-seller checkout?
25. How do you handle seller suspension with active orders?
26. How does Redis improve marketplace performance?
27. Why must checkout validate authoritative prices?
28. How do you design flash sales?
29. How do you avoid database overload during flash sales?
30. How do you make Kafka consumers idempotent?
31. How do you handle out-of-order events?
32. How would you shard order storage?
33. How do you handle a hot product?
34. How do you support multiple regions?
35. How do you maintain inventory correctness across regions?
36. How do you reconcile payment-provider state?
37. How do you test financial correctness?
38. How do you benchmark checkout capacity?
39. What metrics indicate marketplace health?
40. What architecture changes are required at Amazon scale?

For each answer, explain:

- Architecture decision
- Correctness guarantees
- Data consistency
- Failure handling
- Performance
- Operational trade-offs

---

# DEFINITION OF DONE

## 47. ACCEPTANCE CRITERIA

The project is considered complete only when:

- Java 21 application builds.
- PostgreSQL migrations execute.
- Customer/seller authorization works.
- Product catalog and seller offers function.
- Cart operations work.
- Pricing is authoritative.
- Inventory reservations prevent overselling under documented guarantees.
- Checkout is idempotent.
- Order records are durable.
- Payment integration works against a sandbox or simulator.
- Duplicate payment requests do not cause duplicate charges under supported semantics.
- Duplicate/out-of-order webhooks are safely handled.
- Saga workflows resume after recoverable failures.
- Compensation and reconciliation are implemented.
- Multi-seller orders are represented correctly.
- Fulfillment and shipping workflows function.
- Cancellation, return, and refund workflows function.
- Double refunds are prevented.
- Customer and seller web interfaces are functional.
- Automated tests cover critical transaction and failure scenarios.
- Docker Compose supports local end-to-end purchases.
- Kubernetes deployment configuration is provided.
- Metrics are exposed.
- Load-test scripts are reproducible.
- Performance claims are measured or explicitly unverified.
- Known limitations and production risks are documented.

A passing build or successful sandbox payment does not establish production readiness or financial compliance.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, production-oriented, multi-vendor e-commerce marketplace** using Java 21 and Spring Boot.

Prioritize:

1. **Financial correctness:** No unintended duplicate charges, refunds, or inconsistent totals.
2. **Inventory integrity:** Prevent overselling within documented reservation guarantees.
3. **Transaction reliability:** Durable checkout and recoverable Saga workflows.
4. **Security:** Strong authentication, authorization, seller isolation, and payment protection.
5. **Scalability:** Efficient catalog browsing, search, caching, and bounded checkout processing.
6. **Fault tolerance:** Recoverable payment, inventory, messaging, and infrastructure failures.
7. **Maintainability:** Clear bounded contexts and pragmatic Java 21 architecture.
8. **Observability:** Diagnosable orders, payments, inventory, and operational failures.
9. **Reproducibility:** Complete source code, functional UI, local infrastructure, automated tests, and benchmarks.

Begin with a concise architecture assessment.

Select the recommended bounded contexts, transaction model, inventory strategy, and checkout Saga.

Then complete the implementation phases with working source code, tests, user interfaces, and infrastructure.

At completion report:

- Implemented functionality
- Architecture decisions and trade-offs
- Project structure
- Exact build and run commands
- Tests actually executed and their results
- Benchmark results, if measured
- Known limitations
- Remaining production risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end marketplace, including backend, functional frontend,
transactional workflows, automated verification, deployment infrastructure, and operational documentation.**