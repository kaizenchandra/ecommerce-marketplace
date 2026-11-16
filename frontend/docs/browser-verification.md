# Browser verification — 16 November 2026

Completed with Playwright's isolated Chromium headless browser against the running Next.js frontend (127.0.0.1:3000) and
actual Spring/PostgreSQL/Redis Compose stack (backend port 8097). All interactions in these tests use rendered UI
controls; API responses are observed to identify created records, with no API calls used to create fixtures or advance
workflows.

## Results

- **3 browser tests passed** in the final run (about one minute).
- **13 proxy/link regression tests passed.**
- Production build and TypeScript checks passed.
- No uncaught page errors in the main three-role journey.
- Backend source, schema, security configuration and API contracts were unchanged.

## Browser journeys

1. Admin login → create product/variant → seller login → create offer → adjust stock → change price → customer login →
   keyword search → compare offer → add twice → verify quantity two → save delivery address → calculate authoritative
   quote → place order → refresh until payment captured → seller partial shipment → admin delivery → customer partial
   return and delivered-purchase review → admin accepts/restocks return → customer sees completed partial refund →
   seller purchases remaining unit's label → opens authenticated label document in a browser popup → dispatches → mobile
   catalog at 390×844 with no horizontal overflow → mobile customer sign-out → seller/admin sign-out.
2. Customer session expires while product dialog is open → add action closes dialog and exposes sign-in → invalid
   password produces a clear error → valid credentials recover the session.
3. Customer places an order → waits for captured sandbox payment → cancels through the UI → sees completed refund.

These tests cover the listed local workflows, not every administrative operation or external provider. All
payments/postage were simulated. OIDC, hosted Stripe and real carrier accounts are not included.

## Frontend fixes

- Added an explicit account-button accessible name so it remains discoverable when mobile CSS hides its text; enlarged
  its icon and tap target.
- Replaced a successfully submitted return form with confirmation to prevent accidental repeat submissions.
- Converted Spring's failed form-login redirect into a clear 401 response in the frontend proxy, preserving session
  cookies. This avoids losing the error marker while following the backend's login-page redirects.

## Reproduce

Start the backend and frontend using the repository setup instructions, then:

```sh
cd frontend
npm ci
npx playwright install chromium
npm run test:browser
npm run build
npm test
npm run typecheck
```

The browser tests read LOCAL_PASSWORD from the root .env or environment. They use separate customer2, seller and admin
browser contexts and create uniquely named sandbox records. They clear customer2's bag through the UI before the main
scenario, retain created records, and must run only against the local sandbox. Do not run them concurrently with other
use of customer2.

This session installed Chromium under /private/tmp/marketlane-browsers; its exact command was:

```sh
PLAYWRIGHT_BROWSERS_PATH=/private/tmp/marketlane-browsers npm run test:browser
```

Playwright creates an HTML report in playwright-report/ and failure screenshots/traces in test-results/ (both ignored).
Successful-run screenshots are preserved here:

- [Customer order and captured payment](browser-proof/customer-order.png)
- [Seller fulfillment workspace](browser-proof/seller-fulfillment.png)
- [Admin return inspection](browser-proof/admin-return.png)
- [Mobile storefront](browser-proof/mobile-catalog.png)

The expanded requirements-review run also verified shipping-profile saves, weighted/custom variant creation,
stock/seller inspection, account/session/return tracking and recovery controls.
See [coverage and limits](requirements-review.md). Run the documented sandbox shipping-profile cleanup after the browser
suite.
