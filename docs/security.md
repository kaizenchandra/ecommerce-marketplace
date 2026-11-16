# Security boundaries

Browser OIDC login, JDBC shared sessions, JWT audience validation, application-specific signed roles, session revocation
and deactivation enforcement are implemented. See [production-auth-and-stripe.md](production-auth-and-stripe.md) for
exact configuration and tested contracts. Local profile uses required-password development fixtures and must not be
exposed as production identity. Browser sessions keep tokens server-side, use HttpOnly cookies, CSRF for mutations,
SameSite=Lax for OIDC redirects and Secure cookies except local HTTP. OIDC session access ends at ID-token expiry;
refresh-token extension/global IdP logout are not implemented.

Session state and tokens reside in PostgreSQL, making database confidentiality/integrity critical. Encrypt disks/backups
and restrict the session tables to the application/session cleanup identities. Java session serialization assumes a
trusted DB and compatible serialized classes across rolling upgrades. Purge expired sessions with Spring Session's
scheduled cleanup. Use dedicated migration/application DB identities and maintain application/IdP secret rotation. JDBC
sessions increase DB traffic; benchmark it before scaling. IdP authority changes affect new logins; revoke
sessions/deactivate accounts for immediate application access removal.

Stripe test Checkout collects cards on Stripe-hosted pages. Only test keys are accepted. Webhooks authenticate raw
bytes, deduplicate durable inbox entries, and process asynchronously. Credentials belong in an untracked environment or
external secret store. No PAN/CVV is modeled or logged. Inbound body limits and rate/admission controls supplement
authentication; public deployment still requires proxy/WAF slow-request/body limits, TLS and distributed abuse
protection.

Seller mutations check role, authenticated ownership and ACTIVE status. Customer resources use authenticated subject
predicates; admin namespace requires ADMIN. Product/catalog reads are public GET-only; verified reviews require
delivered purchase. CSP disallows inline/external scripts and framing; UI inserts untrusted text via textContent. Only a
validated HTTPS checkout.stripe.com redirect is stored as a payment action URL. CORS is not enabled; browser UI is
same-origin. No customer/order/payment identifiers appear in metric labels.

Kubernetes uses non-root/read-only containers and externally provisioned Secret references. No
cluster/TLS/network-policy/PITR verification was performed. The base manifest uses local simulator; OIDC+Stripe overlay
changes the authentication/payment path but remains test-mode commerce. Product taxes, real regulatory seller
verification, fraud, settlements and full retention/deletion processes remain incomplete. PCI DSS, privacy and regional
commerce compliance require separate evidence; none is claimed.
