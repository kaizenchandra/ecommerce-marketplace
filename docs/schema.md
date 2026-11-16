# Schema and integrity guide

Flyway V1 is the executable low-level data model, V2 supplies the sandbox catalog. UUIDs identify business aggregates.
OIDC subject strings own customers/sellers; seller owner is unique. Products own variants; offers reference variant and
seller; inventory is owned by offer. Warehouse is currently a default location label, not a multi-location allocator.

Primary keys, FKs and uniqueness are declared in SQL. Key constraints include unique seller/variant offer,
customer/offer cart item, customer/key checkout, one order/quote, one payment/order, reservation/order+offer, one
coupon/customer redemption, one shipment/item, one return/item, unique refund operation and provider operation keys.
Bigint minor units carry checks; orders link immutable quote+item records; inventory check prevents
negative/over-reserved quantities. Reconciliation verifies cross-row totals because PostgreSQL CHECK cannot aggregate
child tables.

Products have generated English tsvector and GIN index. Offers index variant, orders index customer/created_at;
workflows and outbox have partial due/pending indexes. At large scale add measured indexes for reservation offer lookup,
seller order paging, refund due scans and reconciliation. Do not add speculative indexes without checking write
amplification/EXPLAIN. Current bounded-offset queries are intended for sandbox-sized data.

Reservation transitions: ACTIVE -> COMMITTED or RELEASED only, under order lock. Commit decrements on_hand and reserved;
release decrements reserved only. Order transitions are guarded application operations with `version` increments;
explicit pessimistic locks supply concurrency correctness, not expected-version client headers. Offer/inventory versions
track changes but updates are last-writer-wins after database serialization. Database constraints enforce legal state
values, while transition guards reside in application code.

Quote creation/order initiation/customer review and fulfillment/inspection use local transaction boundaries. Store.event
inserts outbox in the same transaction as each corresponding business transition. Worker leases are committed separately
before provider calls; provider ledger writes are REQUIRES_NEW; finalization uses a fresh order/payment transaction. No
lock remains held over a simulated capture/refund call, except webhook processing which performs only a local provider
lookup inside its transaction. A real adapter must move network reconciliation outside webhook DB locks.

No retention/purge job is implemented. In production define legal financial retention before deleting
payments/orders/audits; partition append-only movements/outbox/attempts by creation time, archive completed orders,
retain inbox IDs across maximum redelivery window, and anonymize customer PII under legal policy. Foreign keys
deliberately restrict accidental deletion of linked order data. Production migration roles should differ from
application roles and disallow arbitrary snapshot updates. Apply expand/contract migrations and test backward-compatible
rolling upgrades.

V3 adds Flyway-managed SPRING_SESSION and SPRING_SESSION_ATTRIBUTES, durable stripe_operations intent/reference records,
provider_webhook_inbox with due index, payments checkout_url/reconcile_after and due-refund indexing. Stripe operations
commit independently before network POSTs, preserving evidence when local workflow finalization rolls back. Shared
session serialization requires compatible application/security classes across rolling deployments.

V4 backfills shipment/return quantities and request keys from prior whole-line records, removes
one-shipment/return-per-line uniqueness, and adds shipped/delivered/returned counters with nested inequality checks. It
adds per-line tax, provider quote metadata, seller packing/origin profiles, optional address state,
aggregate/correlation event metadata, leased outbox delivery state and DLT/version tables. Financial snapshots for old
orders are preserved.

V5 adds immutable per-line shipping_data, label_reserved_quantity, durable shipping_labels and one-shipment-per-label
linkage, simulator provider ledger, separate refund net/tax snapshots and tax_reporting_jobs with sale dependencies. V6
independently adds tax_reporting_checked markers/indexes for bounded historical catch-up. No applied migration was
amended.
