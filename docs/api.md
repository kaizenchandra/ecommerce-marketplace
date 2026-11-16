# API contract

Machine-readable operation/request specification: openapi.json (OpenAPI 3.1). Responses use raw JSON records/arrays with
snake_case database field names. Money values (`price`, `unit_price`, `net`, `total`, `amount`, `refunded`) are
**integer INR minor units**. Offer input `price` accepts an exact decimal string, e.g. `"2999.00"`; it is converted with
no fractional rounding allowed. Monetary calculations never use binary floating point on the backend.

Authentication locally: GET /api/v1/csrf, preserve JSESSIONID, submit POST /login with form fields username/password/_
csrf, then retrieve a fresh CSRF token after login. Every mutation except HMAC webhooks requires X-CSRF-TOKEN. Session
expires after 30 minutes of inactivity. POST /logout with CSRF invalidates session. Development accounts are in README.
Production OIDC browser login, application-specific signed client roles, audience-validated JWTs, shared JDBC sessions
and Stripe test adapter are implemented; see production-auth-and-stripe.md. Bearer-authenticated mutations do not
require CSRF; session-authenticated mutations do.

Example purchase (authenticated session):

```http
POST /api/v1/cart/items
Content-Type: application/json
X-CSRF-TOKEN: <token>

{"offerId":"40000000-0000-0000-0000-000000000001","quantity":1}
```

Create address `POST /customers/me/addresses` with `{"line1":"1 Main Street","city":"Pune","postalCode":"411001"}`.
Create quote `POST /checkout/quotes` with `{"addressId":"<owned-address-uuid>","coupon":"WELCOME10"}`. Response includes
`id`, `subtotal`, `discount`, `shipping`, `tax`, `total`, `currency`, `expires_at`. Checkout:

```http
POST /api/v1/checkouts
Idempotency-Key: customer-generated-stable-key
X-CSRF-TOKEN: <token>
Content-Type: application/json

{"quoteId":"<quote-uuid>"}
```

Response includes order `id`, `status: PAYMENT_PENDING`, `total`, `quote_id`. Poll GET /orders/{id}; its response has
order, immutable items and payment. Background simulator capture confirms the order. Confirm endpoint is a nudge to
worker, not a client assertion that payment succeeded; worker lease can postpone it. Do not send a new checkout key
after timeout. Same customer/key+quote returns original order; same key with different quote gets 409; same quote with
different key conflicts on quote uniqueness.

Catalog GET /products or /search/products accepts q, category, brand, min/max (minor units), sort=
relevance|price|price_desc, page (24 results/page, 0..10000). Orders: 20/page, page 0..9999. Reviews: 20/page. Several
seller/admin lists are capped at 100/200 with no cursor; not a large-scale API. Catalog search returns active, in-stock
offers only; detail comparisons can show zero stock and checkout revalidates.

Seller uses GET /seller/me and its returned id for POST /sellers/{id}/offers, PATCH /sellers/{id}/offers/{offer}, POST
/sellers/{id}/inventory/adjustments. Stock changes accept delta+reason and fail if on_hand would fall below reserved.
Create variant via admin before seller offer. Shipments refer to seller-order id plus owned order item, carrier and
tracking. One shipment per full item makes retries return existing shipment; changed tracking input on duplicate is
ignored. Returns are full-line, once per item, within 30 days of delivered_at. Admin inspection controls acceptance and
restock; refund equals discounted line net.

Only checkout and provider financial operations have explicit payload-aware idempotency keys. Address/offer/admin
creation APIs are not universally idempotent; inspect state before retrying after an ambiguous response. Cancellation,
shipment creation, delivery, return request and inspection are guarded business operations safe to repeat within their
stated semantics.

Domain errors use application/problem+json with status/title/detail. API handlers return 400 validation, 404
unowned/missing resource, 409 invariant/state conflict. CSRF and authentication errors come from Spring Security and may
redirect local sessions to /login; clients must detect redirects and reacquire token. Rate limit returns 429 and
Retry-After:5. An unexpected DB/network error is not proof a checkout failed: query/retry same key. OpenAPI response
bodies are deliberately broad, not generated typed SDK contracts; full response schemas are a remaining improvement.

Current extensions: GET/DELETE /sessions for browser session inspection/revocation; POST
/admin/customers/{subject}/deactivate; GET /admin/payment-events and POST
/admin/payment-events/{provider}/{eventId}/retry. POST /payments/webhooks/stripe accepts raw Stripe JSON with
Stripe-Signature and commits inbox before acknowledgement. /webhooks/local remains simulator-only and rejects requests
when Stripe is selected. Payment responses may include checkout_url for pending Stripe hosted checkout. Do not treat a
return redirect as paid; poll the owned order.

Quantity extension: shipment creation accepts quantity and requires Idempotency-Key for partial requests. Return
creation accepts quantity and shipmentId with the same rule; keys reject changed payloads. Response rows include
shipped_quantity/delivered_quantity/returned_quantity and per-return refund_amount. Address creation accepts an optional
uppercase state code. Seller shipping-profile PUT, tracking retry, event outbox retry and DLT replay endpoints are
described in kafka-shipping-tax-and-quantities.md. Quotes now return authoritative shipping/tax, provider_details and
line goods-tax snapshots. Provider availability/timeouts return503, changed snapshot/state409, quote admission429.
