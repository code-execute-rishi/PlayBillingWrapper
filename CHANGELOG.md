# Changelog

## Unreleased (post-review hardening)

Fixes surfaced by a manual correctness review of 0.1.1.

### Critical

- **Failed `queryPurchasesAsync` branches now decrement the outstanding counter and surface
  the `BillingResult` to the listener.** Previously a single failed INAPP or SUBS query left
  `fetchedPurchasedProducts == false` forever; `isPurchased(...)` would keep returning
  `PURCHASED_PRODUCTS_NOT_FETCHED_YET` even after the other query succeeded.
- **Pending purchases are auto-retried.** Every `PurchaseInfo` reported in `PENDING` state —
  whether from `queryPurchasesAsync` on reconnect or live from `onPurchasesUpdated` — now
  schedules `retryPurchaseWithBackoff` automatically. Previously the retry existed but was
  only invoked if the caller explicitly called `rawConnector().retryPendingPurchase()`.
- **Idempotency ledger records delivery _before_ the grant callback.** A purchase that is
  delivered but never acknowledged (autoAcknowledge=false + server-side ack flow) used to
  be redelivered on restart or restore, double-firing `onLifetimePurchased` /
  `onSubscriptionActivated`. Tokens are now marked handled the moment the callback fires;
  callers that reject the grant (e.g. server verification failed) should call
  `IdempotencyStore#forget(token)`.

### Important

- **Dropped catalog-inferred `IN_TRIAL`.** The old heuristic scanned the product catalog
  for any offer with a zero-price phase, which mislabeled paid renewals as in-trial
  whenever Play still exposed a trial offer. The enum value is retained but deprecated
  and never returned; purchased + auto-renewing now maps to `ACTIVE`. Backends that need
  the trial/paid distinction must consult `subscriptionsv2.get`.
- **`onReady()` now fires exactly once, after purchase reconciliation completes.**
  Previously it fired per product-details batch (potentially twice when both INAPP and
  SUBS were configured) before ownership had been loaded, so callers reading
  `hasLifetime()` / `monthlyState()` inside the callback saw stale state.
- **`BillingConnector` callbacks are null-safe post-`release()`.** All `billingEventListener.onX(...)`
  dispatches route through `safe()`, which returns a no-op listener once `released` is set,
  so in-flight async BillingClient callbacks can't NPE on a destroyed caller.

### Tooling

- Added JUnit/Mockito/Robolectric test module.
- First real unit tests: 11 for `OfferSelector` (trial preference, base-plan fallback,
  eligibility detection, offerId override), 6 for `IdempotencyStore` (persistence across
  instances, forget, clearAll, idempotent marks).
- Suppressed the benign AGP 8.7.3 / compileSdk 36 warning via
  `android.suppressUnsupportedCompileSdk=36` until AGP 8.13+ can ship with Gradle 8.13+.

## 0.1.1 — 2026-04-21

### Fixed (post-review)

Critical correctness fixes surfaced by Codex + CodeRabbit review of the initial release:

- **Depend `api` instead of `implementation`** on `com.android.billingclient:billing`,
  `androidx.lifecycle:lifecycle-common`, and `androidx.annotation:annotation`. These types
  (`BillingResult`, `ProductDetails`, `Purchase`, `Lifecycle`, `@NonNull`) are exposed on the
  public surface; `implementation` left them off the consumer's compile classpath.
- **Skip on-device signature verification when no license key is configured.** Previously the
  library passed an empty string to `Security.verifyPurchase`, which returned `false`, so
  `processPurchases` silently filtered out every real purchase.
- **Replace `List.of` / `List.copyOf`** with `Collections.singletonList` /
  `Collections.unmodifiableList(new ArrayList<>(…))`. The former are Java 9 library APIs that
  crash with `NoSuchMethodError` on Android releases without core-library desugaring.
- **Don't clobber `fetchedProductInfoList` across INAPP ↔ SUBS query callbacks.** The second
  callback used to wipe the first's results; now both queries append into a list that is
  cleared once before the query pair starts.
- **Decrement `productDetailsQueriesPending` on the empty-product branch** so purchase
  reconciliation still runs when one of the two query groups returns no products.
- **Rerun product + purchase queries when `connect()` is called on an already-ready client.**
  Previously `restorePurchases()` / app-resume refreshes were silent no-ops.
- **Surface synchronous `launchBillingFlow` failures** through `onBillingError` instead of
  dropping the `BillingResult`.
- **`Security.verifyPurchase` uses UTF-8 explicitly.** Platform-default encoding could cause
  verification to fail on non-Latin locales.
- **`IdempotencyStore` now uses `commit()` instead of `apply()`** so a crash after grant does
  not lose the dedupe record.
- **Updated ProGuard `consumer-rules.pro`** to keep members of the Billing library classes and
  the wrapper's own public API.
- **Replaced misleading "unsubscribe" deprecated alias with `openManageSubscription`** (this was
  already done pre-0.1.0; documented here for clarity).
- **`ProductInfo` constructor** annotates `skuProductType` as `@NonNull`.
- **Full Apache License 2.0 text** in `LICENSE` (was previously just the short-form header).
- Documentation clean-up (Javadoc parameter ordering, `OfferSelector` doc/impl consistency,
  README wording).
- Integration docs call out the listener-cleanup pattern (`billing.setListener(null)`
  in `Activity.onDestroy`) for consumers.

## 0.1.0 — 2026-04-20

Initial release.

### Added

- `PlayBillingWrapper` facade with three first-class methods: `buyLifetime()`,
  `subscribeMonthly()`, `subscribeYearlyWithTrial()`.
- `BillingConfig` builder.
- `OfferSelector` — picks offer tokens by base plan id + optional offer id, auto-prefers
  free-trial when the user is eligible.
- `SubscriptionState` enum covering `ACTIVE`, `IN_TRIAL`, `CANCELED_ACTIVE`, `PENDING`,
  `EXPIRED`. Local-only — computed from `Purchase` data, no RTDN.
- `IdempotencyStore` — persistent dedupe ledger keyed on `purchaseToken`.
- `WrapperListener` — simplified listener with default no-op methods.
- `obfuscatedAccountId` / `obfuscatedProfileId` wired into every `BillingFlowParams`.
- `openManageSubscription()` deep link.
- Pending retries: unbounded by default. Real PENDING payments (cash, bank transfer) can
  take hours or days; the library keeps the purchase token alive and reconciles on every
  reconnect.
- Documentation: INTEGRATION, API, SECURITY, TESTING, MIGRATION.
- Integration guide demonstrates all three shapes end-to-end.

### Based on

Forked and rewritten from [moisoni97/google-inapp-billing](https://github.com/moisoni97/google-inapp-billing)
(Apache-2.0). Retained its pending-retry state machine, acknowledge-with-backoff logic, and
Play Store installation heuristic as correctness references.
