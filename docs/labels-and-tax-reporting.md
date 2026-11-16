# Shipping label purchasing and tax transaction reporting

Implemented code: `labels/LabelService`, ShippoLabels/LocalLabels, LabelApi, `reporting/TaxReporting`, StripeTaxReporter
and TaxReportingApi. V5 adds durable operations, immutable packing/recipient snapshots and separate return net/tax
components. V6 adds indexed bounded catch-up for historical captured/refunded payments. Both are additive Flyway
migrations; previously applied migrations are preserved.

The commerce adapters remain **test-mode only**: Shippo shippo_test_ and Stripe sk_test_ credentials are accepted; live
keys/responses are rejected. Label purchasing uses the provider's actual transaction endpoint, and tax reporting uses
actual Stripe Tax transaction/reversal endpoints. Local simulators remain available without external credentials. These
features do not establish regulatory compliance or production certification.

## Buy a label

```http
POST /api/v1/seller-orders/{sellerOrderId}/labels
Idempotency-Key: stable-label-request
X-CSRF-TOKEN: <browser session token>
Content-Type: application/json

{"itemId":"<owned-order-item>","quantity":2,"maxCostMinor":10000}
```

A request accepts a quantity and seller-authorized maximum label price (INR minor units), not an arbitrary external rate
or shipping address. It returns a durable shipping_labels operation immediately. Same seller-order/key with identical
item/quantity/ceiling returns the original operation; changed payload returns409. Order must be confirmed/partially
fulfilled, seller owned and ACTIVE. Shipments plus label reservations cannot exceed purchased quantity, including
concurrent requests. Existing manual-shipment API now excludes units already reserved by label operations.

Quotes copy origin, recipient, parcel dimensions/packing limit and per-unit weight into each quote/order line. A label
request recomputes weight for the **requested partial quantity** from that immutable snapshot; subsequent
catalog/profile changes cannot redirect the package or change its declared weight. Pre-V5 orders without a reliable
shipping snapshot are rejected rather than reconstructed from mutable data. A fresh server-side Shippo rate is obtained
for this package. If it exceeds maxCostMinor, no label purchase is issued. The seller bears any difference from the
customer checkout shipping charge; the customer is not recharged. Carrier billing adjustments after purchase remain an
operational reconciliation concern.

GET `/api/v1/shipping-labels/{id}` and GET `/api/v1/seller/labels` expose owner-scoped state. Seller desk supports
purchase, status, label link refresh and physical dispatch. Label URLs can contain private signed download tokens;
protect them like customer shipment data. The server accepts HTTPS provider download hosts from an explicit allowlist
and never follows arbitrary label URLs itself. Use POST `/shipping-labels/{id}/refresh` to obtain an updated provider
link, not another label purchase. Offline labels serve a plain-text document marked **SIMULATOR — NOT VALID POSTAGE**.

## Label correctness and recovery

States: READY -> ATTEMPTED -> PURCHASED -> DISPATCHED, with ABORTED and MANUAL_REVIEW branches. READY reserves
fulfillment quantity but does not imply postage was bought. A short transaction claims a lease, and another conditional
committed update records purchase_started_at before the external POST. Only that one-shot transition may issue
`POST /transactions/`. Shippo creation has no idempotency-key guarantee in the referenced contract, so **we do not
blindly retry a purchase POST**.

The transaction carries metadata `marketplace-label:<operation UUID>` and the persisted rate ID. A lost response leaves
ATTEMPTED. Recovery retrieves a known transaction ID or searches provider transaction pages by operation metadata and
the original rate, with a creation-time lower bound. Each scan is limited to five pages of100; absence is not proof the
original call failed. After eight attempts the operation requires manual review. If the worker dies between persisting
intent and actually sending the POST, it can remain unresolved even though no label exists: that is the deliberate
availability cost of avoiding an unproven duplicate-post guarantee.

A provider ID is stored before local purchased-state finalization. A crash/DB failure after provider success resumes via
GET. A result must be test-mode and match operation metadata/rate/provider ID. Carrier token is resolved from the
authoritative rate/carrier account, not seller-supplied display text. Label/tracking fields must be complete before
PURCHASED. Pending or terminal-error provider states do not masquerade as successful purchases.

Provider calls occur outside order/quantity transactions. Database-clock lease predicates avoid JVM/database clock skew
in due-work decisions. A lease-token CAS fences stale READY workers before purchase. After ATTEMPTED, overlapping
workers can reconcile, but cannot invoke creation again. Scheduled label/tax work runs on a bounded scheduler separate
from payment and Kafka publication.

### Dispatch and cancellation

POST `/api/v1/shipping-labels/{id}/dispatch` records physical handoff after successful purchase. It atomically converts
label_reserved_quantity into shipped_quantity, creates one shipment tied uniquely to label_id, advances order state and
writes outbox. Repetition returns the existing shipment. Buying postage **never** marks an item physically
shipped/delivered by itself.

Customer cancellation is rejected while label fulfillment reserves units. Seller POST `/shipping-labels/{id}/abort` can
release only operations that never crossed purchase_started_at; it races safely with the one-shot purchase claim. Admin
`/admin/shipping-labels/{id}/abort` provides the same pre-purchase rule for suspended/unavailable sellers.
Ambiguous/purchased quantity cannot be silently released.

Admin POST `/admin/shipping-labels/{id}/release-closed` retrieves the known original provider transaction and releases
only undispatched labels authoritatively ERROR or REFUNDED. It cannot release a SUCCESS/unknown/REFUNDPENDING label or
undo physical dispatch. This is recovery from provider evidence, not a label-refund purchase API: requesting carrier
postage refunds/voids is still handled outside this application. A paid label that cannot yet be voided can temporarily
block customer cancellation and requires operations policy.

### Admin recovery

* GET `/api/v1/admin/shipping-labels` lists requests and last_error.
* POST `/admin/shipping-labels/{id}/retry` requeues without clearing purchase_started_at. An ambiguous operation returns
  to ATTEMPTED, never READY.
* POST `/admin/shipping-labels/{id}/reconcile/{providerId}` retrieves the provider transaction, verifies metadata/rate
  and resumes local completion. Never attach an arbitrary label or choose a new purchase key to repair ambiguity.
* Pre-purchase abort and authoritative closed release are audited. Provider reconciliation/retry actions are audited. Do
  not remove intent markers or manually reset quantity counters.

No exactly-once carrier execution claim is made. The guarantee is one application purchase attempt per original
operation, retained ambiguity and no automatic replacement purchase. Clients must retain the request key; intentionally
using multiple keys for still-available different units creates separate valid requests.

## Tax sale reporting

On recording a provider-confirmed capture, the existing payment transaction also inserts one tax SALE job per seller
order whose immutable quote has a Stripe calculation reference. Each job snapshots calculation, offer references, line
net/goods tax and shipping/shipping tax. Stable reference/key: `tax-sale:<order UUID>:<seller-order UUID>`. Offline
tax-exempt fixtures have no Stripe tax transaction and are skipped.

A worker calls `POST /v1/tax/transactions/create_from_calculation` with that calculation, reference, recorded liability
posting time and expanded line_items. It validates test mode, INR, transaction type/reference, exact original line
references/net/tax, completeness, and shipping values. Confirmed orders do not block on reporting availability.
Pending/failed reporting remains visible/retryable and must be reconciled by operations.

Provider intent is persisted before POST. Known IDs are retrieved, not recreated. Unknown responses retry the **same**
Stripe key and immutable parameters only within23 hours of the original attempt. Beyond that, the job becomes
MANUAL_REVIEW rather than risk a provider-pruned idempotency key. Tax references are unique as required by Stripe. A
retrieved/attached ID must validate against the original job before recovery. A different key or recalculated tax is
never invented to repair an old sale.

The provider ID is committed before local DONE finalization. DB rollback after successful reporting does not lose that
reference; retry uses GET. Attempts are bounded at eight, and dependencies do not consume attempts while waiting.
Persisted Stripe responses retain original tax-line IDs for future precise reversals; Kafka is not needed to make
reporting work durable.

## Refund reporting and exact reversal amounts

Only a **completed customer financial refund** creates reversal jobs, in the same transaction that records refund
completion. A pending refund does not reduce reported tax liability. Each reversal depends on the original SALE reaching
DONE, preserving original transaction/line identities even if the refund finishes before sale reporting recovers.

* Full pre-shipment cancellation: mode=full for each seller's original tax transaction, including its shipping/goods
  tax.
* Partial physical return: mode=partial, exact negative goods-net/goods-tax amounts and original_line_item ID. Shipping
  and tax-on-shipping are not reversed because this baseline's delivered-order refund policy retains them.

Each return now snapshots refund_net and refund_tax separately. For each component, allocate
`floor(component × cumulative presented units / purchased units) - prior floor`. Their sum is the refund amount.
Components remain nonnegative, total refunds/reversals cannot exceed original goods amounts, and all accepted unit
returns sum exactly to the original net and tax. This may move a rounding penny between **new** partial refunds compared
with the old combined-rounding rule; already-recorded refund_amount is never repriced.

V5 fills historical components only where unambiguous (zero-tax or full-line equal-total cases). Legacy partial taxed
refunds without reliable components create a MANUAL_REVIEW reversal instead of guessing a tax split. Their existing
customer refunds remain untouched. Rejected inspection requests continue to consume their presented units under the
established one-presentation policy.

## Catch-up and operations

A bounded V6 catch-up scan finds pre-existing captured/refunded payments and completed refunds not checked for
reporting, locks their orders, inserts missing unique jobs and marks them checked atomically. Repeating the scan does
not duplicate jobs. It does not recalculate old monetary snapshots. Aged/expired provider calculations or unresolved
legacy components require manual handling; no tax liability is silently replaced with zero.

GET `/api/v1/admin/tax-reporting` lists durable jobs and dependencies. POST `/{id}/retry` retains original key,
timestamp and provider-start marker; it cannot extend the safe retry window. POST `/{id}/reconcile/{providerId}`
validates a provider GET before linking a missing reference and resuming completion. Do not delete an ambiguous job to
free a reference. Dashboard and alerts expose manual label/tax work and pending reporting without metric labels
containing business IDs.

This implements provider tax-sale/reversal **recording**, not filing/remittance, tax registration, professional
determination of marketplace liability, or a complete accounting ledger. Merchant account/nexus/classification
configuration remains required. Live-money shipping/tax modes remain disabled.

## Reproduce verification

```sh
mvn -Pintegration verify
# In the default local-provider / Kafka-enabled Compose stack:
export LOCAL_PASSWORD='the configured local password'
python3 scripts/smoke-labels.py
```

The HTTP smoke buys a simulator label, retrieves its explicit non-postage document, dispatches once despite retry,
delivers, processes two partial returns and verifies completed refunds conserve total. Shippo/Stripe HTTP contract tests
inject real read timeouts after remote state persists, overlapping worker lease expiry, local DB finalization failures,
ceiling violations, concurrent quantity allocation, ownership/key conflicts, tax dependency ordering, full cancellation
shipping reversal, ten tiny partial tax refunds, old-key fail-closed behavior, validated provider-ID recovery and
historical catch-up. Actual provider-account execution still requires credentials, which were absent during
implementation.

Official API contracts
consulted: [Shippo create transaction](https://docs.goshippo.com/api-reference/transactions/create-a-shipping-label), [list transactions](https://docs.goshippo.com/api-reference/transactions/list-all-shipping-labels), [refresh/retrieve label](https://docs.goshippo.com/api-reference/transactions/retrieve-a-shipping-label), [Stripe sale from calculation](https://docs.stripe.com/api/tax/transactions/create_from_calculation), [tax reversal](https://docs.stripe.com/api/tax/transactions/create_reversal), [tax transaction retrieval](https://docs.stripe.com/api/tax/transactions/retrieve).
