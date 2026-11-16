# Production authentication and Stripe test Checkout

Implemented code, not a proposed integration. `production` enables browser OIDC authorization-code login and a separate
stateless signed-JWT API chain. `stripe` selects the real HTTP Stripe Checkout adapter. `local` keeps development
form-login users; `local,stripe` is a convenient hosted-payment sandbox. Never enable `local` in a public
production-auth deployment.

## Identity and shared sessions

Spring Session JDBC persists the security context, OIDC principal, authorization request/state and CSRF token in
PostgreSQL (Flyway V3). Replicas share sessions; sticky routing was removed from the Kubernetes service. The default
idle timeout is 30 minutes. OIDC sessions also stop authenticating at ID-token expiration; login through the IdP again
(SSO can avoid a credential prompt). Automatic refresh-token extension is intentionally not implemented. Login rotates
the session ID. Cookies are named JSESSIONID, HttpOnly, Secure by default and SameSite=Lax so the top-level OIDC
callback can carry the state session. Local HTTP defaults Secure=false. Terminate HTTPS in front of production and use
an explicit OIDC redirect URI instead of trusting arbitrary forwarded headers.

Required configuration:

```text
SPRING_PROFILES_ACTIVE=production,stripe
OIDC_ISSUER_URI=https://identity.example.com/realms/marketplace
OIDC_CLIENT_ID=marketplace
OIDC_CLIENT_SECRET=<secret manager>
OIDC_AUDIENCE=marketplace
OIDC_ROLES_CLIENT=marketplace
MARKETPLACE_PUBLIC_URL=https://marketplace.example.com
OIDC_REDIRECT_URI=https://marketplace.example.com/login/oauth2/code/marketplace
```

Register a confidential authorization-code client with the exact redirect URI above. `/login` begins the OIDC redirect.
The browser keeps tokens server-side in the shared session; JavaScript never stores access tokens. Subject `sub` is the
stable customer/seller owner identity. Existing local fixture seller subjects are not real IdP users: onboard the actual
OIDC seller separately.

Trusted roles: issuer-signed ID tokens for login and validated access JWTs for APIs must contain
`resource_access.marketplace.roles` with CUSTOMER/SELLER/ADMIN (change the client segment with OIDC_ROLES_CLIENT). Other
realm/client roles and userinfo-provided roles do not grant privileges. Every authenticated subject receives CUSTOMER,
while SELLER/ADMIN require that application's signed claim. For Keycloak configure the client-role mapper to include
this claim in both ID and access tokens, plus an access-token audience mapper for marketplace. Issuer metadata/JWKS,
signatures, timestamps and audience are validated. The OIDC client library validates state/nonce and ID-token client
audience.

Requests carrying `Authorization: Bearer ...` enter a stateless JWT-only chain with CSRF disabled and no session
security-context fallback. A bad bearer token cannot inherit a valid browser cookie. Session requests retain CSRF,
including logout and revocation. GET `/api/v1/sessions` lists times (never session IDs); DELETE `/api/v1/sessions`
revokes all browser sessions for that subject. Admin POST `/api/v1/admin/customers/{subject}/deactivate` disables the
customer and deletes their sessions. A per-request account check also blocks already-issued JWTs and stale in-flight
browser sessions for disabled customers. Reactivation/session issuance policy remains an administrative/IdP
responsibility. Application logout does not globally terminate the IdP's SSO session; use IdP controls when global
sign-out is required.

## Stripe sandbox

Stripe HTTP calls use a 2-second connect timeout, 5-second read timeout, no redirect following and explicit API version
`2026-10-28.endive`. The adapter accepts only sk_test_ keys and rejects responses/events marked livemode=true. It cannot
execute live-money payments. The production-auth profile is production-style authentication; the commerce environment
remains a test sandbox.

Configure STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET in an untracked .env or secret manager; do not paste keys into
source/docs/chat. To use local login with real Stripe test Checkout:

```sh
# In ecommerce-marketplace; configure test secrets in .env first.
docker compose -f compose.yml -f compose.stripe.yml up -d --build
# Use the official Stripe CLI, authenticated to your sandbox:
stripe listen --forward-to localhost:8097/api/v1/payments/webhooks/stripe
```

Use the CLI's displayed whsec_ signing secret as STRIPE_WEBHOOK_SECRET and restart app after updating configuration. A
registered public HTTPS webhook endpoint has its own secret; it is not interchangeable with the CLI secret. Subscribe to
checkout.session.completed/expired/async_payment_succeeded/async_payment_failed. Read the owned order's
payment.checkout_url in the UI and continue on Stripe's hosted page. Use Stripe's published test payment details there;
the marketplace never receives raw card data. Return redirects do not confirm an order. Webhooks/polling query Stripe
and only confirm when payment_status is paid (or no_payment_required for a free order).

For externally operated OIDC and HTTPS, use `docker compose -f compose.yml -f compose.production.yml up -d --build`. Set
all environment variables above plus DB and Stripe secrets. Kubernetes can apply `configmap-oidc-stripe.yaml` after
provisioning marketplace-secrets with DB_PASSWORD, OIDC_CLIENT_SECRET, STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET; then
restart the deployment. This overlay is provided but not cluster-verified. Existing base manifest still selects local
simulator by default.

## Recovery contract

`stripe_operations` is committed before each creation/refund POST. Keys are stable checkout:<order UUID>,
refund:<business operation key>, expire:<order UUID>. Creation parameters do not change on retry. An unknown response is
recovered by the same provider key; once a provider ID is known, subsequent retries retrieve it instead. Unknown POSTs
older than 23 hours are never blindly repeated, because provider idempotency retention is finite. Operators must
reconcile; a signed matching webhook can recover a missing session reference. Do not delete an ambiguous operation row
or choose a fresh key.

Pending hosted checkout is an expected state: it does not consume the eight failure attempts. Workers poll it at bounded
intervals. Order expiry/cancellation releases stock and expires unpaid Stripe checkout. A payment already paid
concurrently results in an asynchronous idempotent refund. Reconciliation no longer reads simulator tables; it claims
due payment records with SKIP LOCKED and asks the selected provider. It also detects late captures after cancellation.
An unresolved remote expiration remains retryable instead of being declared successful.

Stripe webhooks verify raw-byte HMAC signatures and a five-minute timestamp window, reject live-mode events, bind
session ID/order/currency/amount to the durable intended operation, deduplicate provider/event IDs, and commit an inbox
record before acknowledgement. No provider network call occurs in the webhook transaction. A leased worker processes
inbox records; crashes/timeouts leave them retryable, eight failures cause MANUAL_REVIEW. Admin GET
`/api/v1/admin/payment-events` and POST `/api/v1/admin/payment-events/{provider}/{eventId}/retry` inspect/replay the
original event; changes are audited. Ordinary polling recovers from missing webhooks too.

Refund rows reserve their amounts before provider calls. A provider refund reference is saved even while the provider
says pending; later attempts retrieve that refund. Local COMPLETED is written only for succeeded. Failed/unknown refunds
keep their budget and require resolution, never a replacement refund key. Externally initiated refunds, disputes,
chargebacks, payouts and settlement still need additional reconciliation workflows.

## Verification boundaries

The integration suite runs actual PostgreSQL and two real application instances, plus a signed HTTP OIDC
issuer/JWKS/token service and HTTP Stripe contract server. Tests cover login/state/audience/expiry/role isolation, CSRF,
fixation protection, node shutdown/session continuity/revocation, lost create/refund responses with real HTTP read
timeouts, pending refunds, delayed webhook processing, stale-key protection and a real PostgreSQL trigger that aborts
local confirmation after provider success.

No Stripe credentials or external IdP were configured during this implementation. These are executable
wire-contract/failure tests, not certification against a real Stripe account or customer IdP. A real hosted sandbox
payment remains required after credentials are supplied; no production readiness/compliance claim follows from passing
these tests.

Official contracts
checked: [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html), [Boot 3.5 Spring Session](https://docs.spring.io/spring-boot/3.5/reference/web/spring-session.html), [Stripe Checkout creation](https://docs.stripe.com/api/checkout/sessions/create), [idempotency](https://docs.stripe.com/api/idempotent_requests), [refunds](https://docs.stripe.com/api/refunds/create), [webhook handling](https://docs.stripe.com/events/manage-webhook-endpoints).
