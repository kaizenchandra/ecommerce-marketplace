# Frontend review against prompt.md

Scope: existing backend-supported user-facing behavior. Backend Java, SQL migrations, security configuration,
payment/provider adapters and API contracts were not edited.

## Implemented coverage

| Prompt area             | Frontend coverage                                                                                                                                                                                                  |
|-------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| §3 Identity             | Existing local/OIDC login entry points, profile updates, address creation and address-book listing, active session history, current/all-session sign-out; admin customer deactivation                              |
| §4 Sellers              | Existing onboarding, approval/state changes, price and inventory management, order/label fulfillment; added origin and parcel shipping settings                                                                    |
| §5 Catalog              | Categories, product/variant creation and moderation, product details, offers, SKU attributes, recommendations; added weight/custom JSON attributes and display of existing HTTP(S) image URLs                      |
| §6 Search               | Backend keyword/category/brand/price filtering, suggestions, category/brand facet data, relevance/price sorting and catalog pagination; added current availability check and seller profile inspection             |
| §7 Pricing              | Backend-authoritative INR quotes, coupons, discounts, shipping/tax breakdowns and expiry; no client-authoritative purchase prices                                                                                  |
| §8 Cart                 | Durable authenticated cart, quantity update, removal/clear and increasing quantities on repeated additions                                                                                                         |
| §9 Inventory            | Stock adjustments and on-hand/reserved totals; added a five-unit available-stock warning derived from up to 200 returned seller offers                                                                             |
| §12–15 Checkout/payment | Quotes, idempotent checkout, hosted sandbox payment continuation, separate order/payment statuses and refunds; added owner-authorized provider-status refresh while pending                                        |
| §18 Fulfillment         | Partial shipment, tracking, simulator delivery, labels/download/dispatch; added shipping-profile settings and admin tracking retry control                                                                         |
| §19 Returns/refunds     | Cancellation, partial return request, admin inspection/restock/rejection, refund progress; added owned return-status lookup from account screen                                                                    |
| §20 Reviews             | Verified-purchase submission/update, rating aggregation, moderation; added review pagination controls                                                                                                              |
| §21 Notifications       | Existing durable notification inbox                                                                                                                                                                                |
| §41 Interfaces          | Customer, seller and admin browser workflows, responsive layouts, clear errors, authoritative server guards                                                                                                        |
| Recovery/operations     | Added event-failure queue/replay, outbox retry, payment-event queue/retry, checkout workflow retry, tracking retry, tax retry/reconciliation and shipping-label retry/reconciliation/abort/closed-purchase release |

Order history now uses server pagination (20 orders per page), with list state cleared and pending reads invalidated
during navigation. This also fixes an account-to-orders crash caused by notification rows briefly rendering as orders.

## Backend boundaries

The following requirements cannot be completed from this frontend alone because matching backend capabilities do not
exist:

- Customer self-registration/password recovery, guest-cart merge, wishlists, saved preferences, address
  editing/deletion.
- Seller KYC/payouts, seller-profile editing, full performance analytics and fulfillment acceptance/pick/pack/warehouse
  commands.
- Product image upload/editing, editing an existing variant's attributes/weight, deleting/deactivating an individual
  offer.
- Server-side seller filtering, popularity sorting and selectable availability modes. Catalog results already omit
  unavailable offers.
- Promotion/tax-policy administration or user-selectable shipping options: the quote endpoint accepts address and coupon
  and chooses the configured provider rate.
- Notification read markers/preferences or guaranteed seller-specific notifications.
- Dispute/policy management, external compliance verification and real payment/carrier account setup.

No simulated screens or fake endpoints were added for these gaps. Signed payment webhooks remain provider-to-backend
integrations; the frontend never obtains webhook secrets.

Shipping-profile saves replace settings. There is no shipping-profile read/delete API, so the form cannot prefill or
remove an existing profile. Enabling a profile requires positive weightGrams on all purchasable variants; the UI
explains this. The new variant form supports that attribute, but legacy weights require backend/data migration work.

## Verification and limits

- Three expanded Playwright browser scenarios pass against the real local Compose backend.
- Thirteen proxy/link regression tests pass, including PUT forwarding with session/CSRF headers.
- Production build and TypeScript checks pass.
- Backend source hashes remain unchanged during this review.

Browser coverage adds weighted/custom variant creation, shipping-profile save and checkout, stock recheck, seller
lookup, review paging boundaries, address/session display, returned-item status lookup, account-to-order navigation,
order paging controls, recovery queue loading/workflow retry dispatch, and rejection of aborting an already purchased
label. It also retains the
purchase/shipment/delivery/review/return/refund/label/mobile/logout/session-expiry/cancellation scenarios.

The local review data has fewer than 20 reviews per product, so review paging was checked at its boundary, not with a
populated second page. Order next-page navigation is exercised when a full page exists. Image URLs are absent in seeded
products, so image rendering has build coverage but no live-image fixture.

The sandbox has no failed external tax/webhook/Kafka jobs. Actual successful provider reconciliation, event replay and
recovery from those failures remain unverified; their forms call the existing endpoints and display backend errors.
Workflow retry was accepted for an existing order, not proven to recover a failed workflow. The attempted
purchased-label abort returned the expected backend conflict and did not release quantity.

## Test fixture cleanup

The browser suite configures the seeded seller's shipping profile as “Browser Store / 4 Test Street” and creates
weighted variants. This changes how unweighted seed offers are quoted. After testing, remove only that test-created
profile in the local Compose sandbox:

```sh
docker compose exec -T postgres psql -U marketplace -d marketplace < frontend/browser-tests/cleanup-shipping-profile.sql
```

The query matches the exact test seller and test origin. Do not use it for a real seller with the same settings.
Orders/products/reviews remain for evidence. The cleanup was executed after this review, restoring the original seed
seller's lack of shipping profile.
