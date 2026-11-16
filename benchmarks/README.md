# Reproducible workloads

Run `docker run --rm -i -e RATE=20 -e DURATION=10s grafana/k6 run - < benchmarks/browse.js` after Compose starts. Set
MODE=search for full-text search. Increase RATE to 1000, 10000, 100000, 1000000 only in an isolated provisioned
environment. MAX_VUS bounds the load generator; record dropped iterations as offered traffic that did not reach the
service. Local admission rejects >600 API calls/minute/identity/IP; do not interpret these responses as catalog
capacity.

Checkout/flash-sale fixture format:
`[{"quoteId":"uuid","key":"unique-key","cookie":"JSESSIONID=...","csrf":"session-csrf-token"}]`. Generate each quote
through authenticated cart/address/quote APIs. Quotes expire after five minutes. Mount fixture and script into k6, set
FIXTURES to the mounted path. Use distinct customer identities for realistic concurrency. Set all offers to the last
unit to exercise contention. 409 is a stock/quote conflict, 429 is bounded admission, neither means purchase succeeded.
Poll orders/payment ledger separately to measure confirmed purchases. A request-acceptance metric is not completion
rate.

Test matrix: browse 1k/10k/100k/1m RPS; search 1k/10k/100k; checkout 100/1k/10k orders/s; flash sale 10k/100k/1m
attempts. These are unverified target workloads, not measured support. Allocate fixtures, generator CPU/network, stock,
distinct identities, and database capacity before each run. Never aim these at production or a paid provider.

Record k6 offered/achieved rates, dropped iterations, p50/p95/p99, status distribution; Prometheus DB pool
pending/active, JVM CPU/heap/GC, request latency, saga/refund failure counters; PostgreSQL lock waits and inventory
minimum; database counts of unique provider operation keys versus orders. Derive confirmation completion latency from
orders and workflow timestamps. Kafka lag is not applicable in this implementation. Save raw output and hardware,
dataset, image digest, git revision, and test settings. No hardware throughput is assumed.
