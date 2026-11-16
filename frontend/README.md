# Market Lane Next.js frontend

A separate Next.js App Router / React / TypeScript application for the existing Spring Boot APIs. Backend source,
schema, security settings and static UI are unchanged.

## Run

Node.js 20.9+ and npm are required. Start the existing backend with the root README instructions first (Compose exposes
port 8097).

```sh
cd frontend
cp .env.example .env.local
npm ci
npm run dev
```

Open http://127.0.0.1:3000. Set BACKEND_URL in .env.local to the server-side backend origin if using a different port.
The browser calls only the Next.js origin. Do not include a trailing API path in BACKEND_URL.

Local login uses the existing customer, customer2, seller, seller2 and admin accounts and the backend's LOCAL_PASSWORD.
No credentials are bundled or stored in browser storage. Registration, password recovery and wishlist APIs do not exist
in this backend and are not simulated.

## Implemented flows

- Responsive catalog, debounced search suggestions, categories, brand and INR price filters, server sorting and catalog
  pagination.
- Product details in a keyboard-dismissable native dialog, offer comparison, availability, reviews, review submission
  for delivered purchases and recommendations.
- Durable authenticated bag, quantity changes, removal, clearing and address creation.
- Backend-authoritative checkout quote with discount/shipping/tax breakdown, expiry countdown and a stable checkout
  idempotency key across retries. Cart/address/coupon edits invalidate the displayed quote.
- Orders, payment status and hosted test payment continuation, shipment tracking, cancellation, partial return requests
  and refund statuses.
- Profile editing, notifications, sign-out and revocation of all sessions.
- Role-based seller desk: onboarding, offer creation/pricing, stock adjustments, partial shipments, test shipping label
  purchases, downloads, dispatch, refresh and abort.
- Admin: seller approval/suspension, category/product/variant creation, moderation, customer deactivation, simulated
  delivery, return inspection, refund retries and operational reporting.
- Loading/empty/error feedback, inline labels, visible focus, reduced-motion support, mobile layout and prevention of
  concurrent submissions.

Orders use 20-item server pages, reviews use 20-item pages, and catalog uses 24-item server pages. Operational lists
follow the API's existing limits. Product artwork is decorative category artwork; the backend has no product image API.
No invented ratings, purchases or catalog data are shown.

## Integration and deployment

/api/[...path] forwards to the existing /api/* routes. /auth/[...path] forwards the login/logout/OAuth paths. The server
forwards session cookies, CSRF headers and idempotency keys, preserves Set-Cookie, disables caching, times out API
requests and converts API login redirects into 401 errors. CSRF tokens are fetched before mutations. The proxy rejects
writes with an Origin host that differs from the incoming Host; retain the public Host when placing Next behind a
reverse proxy.

Local form login is the primary standalone configuration. For external OIDC, deploy a shared public ingress that routes
/oauth2/** and /login/oauth2/** to Spring and frontend routes to Next; configure the existing identity-provider redirect
URI for that public ingress. Configure HTTPS for production secure cookies. Do not assume the standalone local proxy
alone completes external OIDC callback configuration.

Payment URLs and label URLs allow only HTTP(S). Provider authentication, payment webhooks, resource ownership, inventory
checks and final monetary calculations remain backend responsibilities. A successful UI action does not imply payment
has completed; refresh order status after the worker or hosted payment finishes.

## Verification

```sh
npm run build
npm run typecheck
npm test
```

Build before running tests. The proxy and link regression tests launch the actual built Next server and a temporary mock
API on ports 3198/3199. They verify SSR delivery, query and cookie forwarding, CSRF cookies, mutation bodies,
idempotency, origin checks, authentication redirects and authentication path restrictions.

Verified against the running Compose backend on port 8097 and Next.js on port 3000:

- Production build and TypeScript compilation pass.
- All 13 proxy/link regression tests pass.
- All 30 live HTTP checks pass through the real Next proxy: three-role form login, authorization, catalog moderation,
  seller price/stock, durable cart, authoritative quote, checkout replay, payment confirmation, partial
  shipment/delivery, review, return inspection, partial refund, shipping-label purchase/download/dispatch, cancellation
  refund, operational reports, notifications, sessions and logout.
- All 3 isolated Playwright browser tests pass against the real backend, covering customer checkout, seller fulfillment
  and label download, admin delivery/return inspection, refunds, mobile sign-out, invalid-login recovery and session
  expiration. See [browser verification and screenshots](docs/browser-verification.md) for exact coverage and
  reproduction commands.
- No backend source code was changed.

Reproduce the live checks with both services running:

```sh
python3 frontend/tests/live-flows.py # from repository root
```

The script reads the local sandbox password from the root .env (or LOCAL_PASSWORD), uses customer2/seller/admin, refuses
a populated customer2 bag, and creates uniquely named sandbox products, offers, orders and returns. It keeps its test
records for review; do not run against production. The last successful run is recorded
in [tests/live-results.json](tests/live-results.json). The browser uses the local /auth/login form; hosted OIDC/Stripe
and external carrier accounts are outside this local simulator verification.

Frontend fixes from live review: authenticated relative label downloads, closing product dialogs and clearing stale
checkout state on session expiration, increasing existing bag quantities rather than resetting them, synchronous
duplicate-submit protection, visible dialog errors, and resetting seller shipment inputs as remaining quantities change.

## Requirements review

See [the prompt.md coverage review](docs/requirements-review.md) for newly exposed backend flows, unsupported
requirements, verification limits, and sandbox shipping-profile cleanup.
