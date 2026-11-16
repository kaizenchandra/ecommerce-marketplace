# Kafka, shipping/tax providers and partial quantities

Implemented packages: `events`, `delivery`, `pricing`, and quantity-aware `fulfillment`. Flyway V4 upgrades existing
whole-line shipments/returns, adds quantity counters and request keys, preserves provider quote references/line tax, and
adds outbox retry/DLT metadata. Existing records are backfilled; no historical financial snapshots are recomputed.

## Run with actual Kafka

```sh
# From ecommerce-marketplace; keep the existing untracked .env.
docker compose -f compose.yml -f compose.kafka.yml up -d --build
export LOCAL_PASSWORD='the configured sandbox password'
python3 scripts/smoke.py
```

The overlay runs Apache Kafka Native 3.9.1 in single-node KRaft mode on the private Compose network. A one-shot root
init container changes only the new broker volume's ownership to the broker's non-root UID 1000. No broker port is
exposed on the host. This local broker is not an HA/security configuration. Use managed/operator-run multi-broker Kafka
with TLS/SASL, ACLs, replication, min.insync.replicas, retention and tested failover in production. Configure bootstrap
servers via KAFKA_BOOTSTRAP_SERVERS, transport via MARKETPLACE_EVENTS_TRANSPORT=kafka, and topic replication via
MARKETPLACE_EVENTS_REPLICATION. Spring Kafka security properties can be supplied through environment/configuration; do
not put credentials in source.

Local transport remains the default without the overlay. The selected backend uses the same consumer deduplication
identity across transport modes. Do not interpret already-published historical local events as newly delivered Kafka
events; publication markers are retained across switching.

## Event contract and recovery

State changes insert an outbox row in their existing SQL transaction. An independently scheduled, single-thread bounded
outbox worker claims up to 16 rows with SKIP LOCKED and 30-second leases, then publishes **outside** database
transactions. The payment/fulfillment scheduler is separate, so broker outages do not block financial reconciliation.
Producer metadata wait is 3 seconds and delivery timeout 5 seconds; acknowledgement is required before publication is
recorded. Eight unsuccessful leased attempts move a row to MANUAL_REVIEW; admin POST
`/api/v1/admin/events/outbox/{id}/retry` retains its original event ID.

Envelope fields: eventId, schemaVersion=1, aggregateId, aggregateVersion, correlationId, eventType, occurredAt, payload.
Aggregate UUID is the Kafka partition key. Aggregate version is the order's revision, not a unique per-event sequence;
multiple events can share a revision. Leased relays/retries can reorder publication, so consumers cannot assume global
or per-aggregate business-event ordering from the relay. Kafka orders records as appended within a partition. The
notification consumer safely appends every unique event and stores max observed aggregateVersion without regressing.
Future mutable projections must define gap/version policies separately.

The producer enables Kafka idempotence and acks=all, but the DB publication marker is not a distributed Kafka/DB
transaction. A crash after acknowledgement before local marking publishes duplicates. Consumer notification,
processed_events inbox and version high-water mark commit in **one PostgreSQL transaction**; the Kafka record offset is
acknowledged only after listener completion. A crash after local commit before offset commit redelivers and the inbox
prevents another notification.

The consumer retries twice with 250ms backoff, then publishes poison/infrastructure-failed records to
`marketplace.events.v1.DLT`. Recovery requires successful DLT send before advancing the original record. The DLT
consumer records payload/topic/partition/offset idempotently in event_failures. If its database is unavailable it keeps
retrying instead of silently losing quarantine data. GET `/api/v1/admin/events/failures` lists failures; POST
`/api/v1/admin/events/failures/{id}/replay` republishes the original envelope and audits the action. Repair the
reader/provider/database before replay. Unknown schema cannot be made valid just by replaying it. Keep inbox IDs for at
least the full replay/redelivery retention window.

No exactly-once transport or globally ordered business workflow is claimed. Events do not authorize payment/order state
changes; source PostgreSQL/provider state remains authoritative. Payloads contain order/event identifiers, not customer
addresses or payment secrets.

## Shippo rates and tracking

```sh
# Configure SHIPPO_TEST_TOKEN=shippo_test_... and STRIPE_TAX_TEST_KEY=sk_test_... in .env.
docker compose -f compose.yml -f compose.kafka.yml -f compose.logistics.yml up -d --build
```

The adapter calls real REST endpoints `POST https://api.goshippo.com/shipments/` to request rates and
`GET /tracks/{carrier}/{tracking}/` for reconciliation. It only accepts Shippo test credentials. Rate responses must be
test-mode and INR; no FX or free fallback is invented. The cheapest eligible INR rate is selected per seller.
Unsupported origin/destination/currency or empty rates reject quotation. The rate/tracking port is now complemented by
the dedicated label-purchasing/dispatch workflow described in labels-and-tax-reporting.md. It still does not arrange
pickups or prove package contents. Seller tracking fraud/label ownership and carrier exceptions still need operational
controls.

An ACTIVE owned seller configures `PUT /api/v1/sellers/{sellerId}/shipping-profile`:

```json
{
  "address":{"name":"Example seller","street1":"1 Origin Road","city":"Pune","state":"MH","zip":"411001","country":"IN"},
  "lengthCm":30,"widthCm":20,"heightCm":20,"maxWeightGrams":10000
}
```

Admin-created variant attributes must contain weightGrams (positive integer grams) and taxCode (the merchant's correct
Stripe tax classification). Example shape, **not a recommended universal tax class**:
`{"weightGrams":"500","taxCode":"txcd_<configured category>"}`. Weight is multiplied by cart quantity and summed per
seller. The configured single parcel dimensions/max weight are an explicit packing constraint; exceeding it rejects
quotation rather than silently quoting a different package. Packing optimization and multi-package rate calculation
remain separate work. Configure carrier accounts/service coverage and supported INR rates in Shippo; this
account/location interoperability was not verified without credentials.

Seller records real carrier token/tracking number when creating each partial shipment. The polling worker claims bounded
due shipments, performs network reads without order locks, and applies delivery under the order lock. Matching
carrier/tracking references and a valid provider delivery timestamp are required. Repeated DELIVERED updates do not
double-increment quantities or extend the return window. Failed network calls retry with leases; eight failures require
manual review. Admin POST `/api/v1/admin/shipments/{id}/retry-tracking` requeues after diagnosis. Simulated admin
delivery is disabled when Shippo is selected.

## Stripe Tax calculation

`POST https://api.stripe.com/v1/tax/calculations` receives authoritative discounted line amounts, seller-origin and
customer-destination address, product tax codes and shipping amount, currency=inr and exclusive-tax behavior. The
adapter accepts test keys only and validates returned line references, amounts, currency, nonnegative tax, pagination
completeness, exclusive/inclusive totals and expiry. Provider references are saved on the immutable quote. Provider
registration/nexus/merchant liability and supported jurisdictions determine tax outcomes; there is no hardcoded global
rate. The separate durable reporting worker now records provider tax sales/reversals; it does not file/remit taxes.

The marketplace is still INR-only with Indian address records. Optional state is stored separately and copied into the
address snapshot. This is not a claim that every carrier/account/tax jurisdiction supports those locations. Provider
rejections fail closed. The merchant must establish marketplace/seller tax liability and configure the tax
account/classifications. Unsupported policy or jurisdiction is not interpreted as tax-exempt.

Quote flow: short snapshot transaction -> seller-group rate and tax calls -> short validation/persistence transaction. A
fingerprint detects cart, offer/product metadata, coupon or origin/packing changes while providers run. No
cart/inventory locks survive network calls; a changed snapshot returns 409. Eight quote slots bound per-instance
external concurrency. A 15-second quote work budget is checked between/after provider calls (an in-flight request can
run until its own timeout); per-call connect/read defaults are 2/5 seconds. Provider timeout/unavailability returns
sanitized 503, leaves durable cart intact and creates no quote/order/reservation. Quotes expire at the earliest
provider-tax deadline or five minutes. Offline defaults remain explicitly free-shipping/tax-exempt test fixtures.

Order total = subtotal - discount + shipping + total tax. Line net and goods tax are immutable; total tax also includes
shipping tax. Stripe payment Checkout receives this already-computed total without calculating taxes again. Do not use
the old `sum(line.net)=order.total` audit when external fees/taxes are enabled: reconcile line nets plus line goods
taxes plus quoted shipping and shipping tax.

## Partial shipment and return API

```http
POST /api/v1/seller-orders/{sellerOrderId}/shipments
Idempotency-Key: stable-shipment-request
X-CSRF-TOKEN: <browser token>
Content-Type: application/json

{"itemId":"uuid","quantity":2,"carrier":"usps","tracking":"provider-tracking-number"}
```

Each shipment carries one order line and a positive quantity; multiple shipments may split that line. The order lock
serializes shipment, delivery, cancellation and return decisions. Conditional quantity updates/checks enforce
`0 <= returned <= delivered <= shipped <= purchased`. Order FULFILLED requires **all units** delivered, not merely one
shipment per line. Cancellation still rejects after any shipment; per-unit cancellation of the remainder is not
implemented.

```http
POST /api/v1/orders/{orderId}/returns
Idempotency-Key: stable-return-request
X-CSRF-TOKEN: <browser token>
Content-Type: application/json

{"itemId":"uuid","shipmentId":"uuid","quantity":1,"reason":"Wrong size"}
```

Return eligibility is tied to a delivered shipment's physical quantity and its 30-day delivery window. Both pending and
processed requests consume returnable units. Rejected inspections also consume the presented units under the current
one-presentation policy; an operator appeal/reset policy is not implemented. Return keys scope to order, shipment keys
to seller order; reusing a key with different payload returns 409. Legacy clients omitting quantity/key retain one
full-remaining-shipment request per item; all new partial requests require a key. Providing shipmentId is recommended
and used by the UI.

Refund allocation is deterministic cumulative integer rounding: for each component (discounted line net and line goods
tax) and Q purchased units, allocate floor(component * cumulative returned units / Q), subtract its prior cumulative
allocation, then sum the components. This conserves A and cannot exceed the captured refundable budget. Full unshipped
cancellation refunds the whole captured order including shipping/tax. Returns refund returned goods+their goods tax;
shipping and shipping tax are nonrefundable under this baseline policy. Inspection restocks only the requested accepted
quantity once, then queues the stable refund operation. Physical receipt is not rolled back just because financial
refund remains pending.

## Executed verification

50-test Java21 verification includes: actual PostgreSQL/Kafka publication and acknowledgement-loss replay, duplicate
consumption, consumer DB rollback -> DLT -> repair/replay, unsupported schema, non-regressing version watermark and
paused broker recovery; actual HTTP Shippo/Stripe Tax contract requests with outage, timeout,
inconsistent-total/foreign-currency rejection, delayed-carrier recovery, tax-inclusive partial refunds and cart mutation
during slow provider calculation; concurrent overshipment/return races and partial refunds conserving a non-divisible
discounted amount. A populated V3 whole-line shipment/delivery/refund upgrades to V4 with all historical money and
quantity preserved. Original OIDC/session/Stripe payment tests also pass.

Compose was run with actual Kafka and V4 applied to the existing sandbox DB. Real HTTP purchase/cancellation/refund
passed; its four lifecycle events were acknowledged and each generated one notification through Kafka. Shippo/Stripe Tax
account credentials were not configured, so their live account behavior remains unverified. Browser visual QA, live
label-account execution, multi-AZ Kafka, tax filing/remittance and production load testing are not implied by these
results.

Official
contracts: [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html), [Shippo shipment rate request](https://docs.goshippo.com/shipments/shipments.md), [Shippo tracking SDK reference](https://github.com/goshippo/shippo-java-sdk/blob/main/docs/sdks/trackingstatus/README.md), [Stripe Tax calculations](https://docs.stripe.com/api/tax/calculations/create).
