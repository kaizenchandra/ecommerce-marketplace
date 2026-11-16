# Known limitations and production gaps

This implementation does not satisfy the full master definition of production completion. It delivers a working
multi-seller sandbox with critical financial/inventory boundaries exercised, not a complete Amazon replacement. The gaps
below must remain visible.

* Stripe-hosted test Checkout, signed durable webhooks and asynchronous refund recovery are implemented alongside the
  offline simulator. Stripe account credentials were unavailable, so actual account-level sandbox payment remains
  unverified. Live-money keys, separate authorization/void, chargebacks, fraud review, payouts, KYC and settlement
  reconciliation remain unsupported. No compliance certification.
* Shippo test rate/tracking and Stripe Tax calculation adapters now validate authoritative INR shipping/tax snapshots.
  Credentials/account registrations, supported geographic coverage and carrier rates were not live-account verified.
  Offline defaults remain tax-exempt/free-shipping fixtures. Provider tax-sale/full-and-partial-reversal recording is
  implemented with durable recovery and catch-up. Filing/remittance, complex promotion stacking and FX conversion remain
  unsupported.
* Minimal authenticated customer identities, profiles and addresses exist. No customer self-registration/password reset,
  guest cart merge, wishlist, user preference editor or user self-service IdP provisioning. Production browser OIDC,
  audience-validated JWT APIs, shared sessions, all-session revocation and account deactivation are implemented.
  Automatic refresh-token extension and IdP global/backchannel logout remain.
* Package boundaries are lightweight; controllers contain SQL/application orchestration. No enforced Spring
  Modulith/ArchUnit boundaries or complete hexagonal repository interfaces. Database admin can alter immutable
  snapshots; least-privilege/immutability DB guards remain.
* Partial-quantity shipments/returns, quantity concurrency controls, tax-inclusive goods refunds and carrier
  polling/retry are implemented. Label purchasing, quantity allocation, download refresh and physical dispatch are
  implemented. No per-unit cancellation after shipping, seller pick/pack states, carrier pickup orchestration or
  optimized multi-parcel packing. Carrier references are seller-supplied; fraud/ownership verification needs additional
  controls.
* PostgreSQL FTS, basic filters/sorting, suggestions, category/brand facets and deterministic same-category
  recommendations exist. No personalization/popularity rank, rich relevance tuning, object storage/media upload,
  Elasticsearch/OpenSearch indexing, safe external reindex or 100m-SKU validation. Image URL schema exists but UI uses
  text cards.
* Redis caches categories only, with TTL/fallback. No hot-key single-flight, negative caching or distributed admission.
  Kafka transport, publication retry/manual state, transactional consumer inbox, DLT/replay and version watermark are
  implemented. Local direct delivery remains optional. Notifications still target the database inbox, not email/SMS.
  Kafka ordering is partition-local; leased relay publication can reorder business events.
* Workflow leases/attempt caps/manual retries and late-capture reconciliation work. Stripe-adapter HTTP
  timeout/lost-response, durable webhook retry and DB-confirmation rollback recovery are tested. PostgreSQL failover,
  abrupt process-kill/lease takeover, abrupt node/process loss, external carrier/account/fraud and multi-region drills
  remain unverified. Paused real-broker recovery, consumer rollback/DLT/replay and Shippo/Stripe Tax HTTP failures have
  been tested. Pending/manual reservations can exceed nominal expiry until resolved. Simulator shares the database
  failure domain.
* Shared JDBC sessions work across replicas, with node-shutdown continuity and revocation tested against actual
  PostgreSQL. Session serialization compatibility during rolling upgrades and external IdP integration need deployment
  verification. Kubernetes manifests require external PostgreSQL/Redis/secrets, TLS/network policy and validation in a
  real cluster. No cloud deployment occurred.
* Admission bounds checkout concurrency and known Content-Length; proxy/WAF body size/slow request limits are required.
  Per-instance request map is not adversarial distributed rate limiting. No waiting room, fairness, bot mitigation or
  admission tokens. Readiness covers PostgreSQL but cannot prove external provider or complete workflow health.
* Micrometer HTTP/JVM/pool metrics plus selected workflow/backlog metrics and Grafana/alert artifacts exist. Payment
  timing, all inventory/coupon/domain counters and full end-to-end trace propagation are incomplete. Monitoring stack
  and alert delivery are not deployed.
* Data retention/deletion jobs, partitioning, PITR automation, tested DR RPO/RTO, production secrets rotation, seller
  performance metrics/disputes and comprehensive policy administration remain.
* Automated tests cover critical paths listed in verification.md. They do not cover every scenario in
  failure-analysis.md. Small local performance data is not a capacity/SLO claim. Target throughput, 99.99% uptime and
  global operation are unverified.

* Shippo label creation has no assumed idempotency guarantee: after the one application purchase attempt, missing
  results require metadata search or a verified provider transaction ID. Bounded scans can miss high-volume/late-indexed
  results; no replacement POST is issued. Labels paid/ambiguous can block cancellation until proven failed/refunded and
  released. Creating carrier postage-refund requests remains outside this implementation.
* Stripe reporting uses separate exact net/tax components for new returns. Historical partial-tax refunds without
  reliable components remain financially unchanged and need manual tax reconciliation. Reporting is provider transaction
  recording, not tax filing or a compliance certification.
