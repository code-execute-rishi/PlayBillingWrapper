# Changelog

## Unreleased

### Behavior change -- `OfferSelector.pick` fallback order swapped

Previous order (pre-v0.4):

> `preferredOfferId` → trial → base plan offer (un-promoted) → first offer on base plan

New order:

> `preferredOfferId` → trial → first promo offer on base plan (`offerId != null`) → base plan offer (un-promoted, `offerId == null`)

**Affects which offer is sold by default** in every spec-driven flow:
`subscribe(activity, productId, basePlanId)`, `subscribe(activity, SubscriptionSpec)`,
`changeSubscription`, and the spec-derived accessors `getActiveOffer`, `getIntroPhase`,
`getIntroEndMillis`, `getOfferPhases`, `getPricingPhases`, `getFormattedPrice`,
`getRecurringPrice`.

**Concretely changes behavior whenever the spec-derived selection falls through
preferred + trial AND the base plan has BOTH a promo offer (`offerId != null`) and the
un-promoted base plan offer (`offerId == null`).** Specifically:

- `SubscriptionSpec.of(productId, basePlanId)` (no preferred, no trial) with a promo on
  the plan — old: sold base plan, new: sells the promo.
- `SubscriptionSpec.withTrial(...)` when Play omitted the trial offer (e.g. returning
  user) and another promo exists — old: fell back to base plan, new: sells the other
  promo.
- `SubscriptionSpec` with `preferredOfferId` set, when Play omitted that offer (failed
  eligibility filter) and another promo exists — old: fell back to base plan, new:
  sells the other promo.

Rationale: Play silently omits ineligible promos from `ProductDetails`, so any promo
that survives into the offer list is one Play will honour at checkout. Surfacing the
best available promo by default is the revenue-positive choice; "fall through to the
un-promoted recurring price" is a buried-discount footgun, not a safety feature.

**Migration.**

- To target the un-promoted base plan offer at checkout time, call the new
  `subscribe(activity, productId, basePlanId, null)` overload — `null` for `offerId`
  resolves to the base plan offer directly (`Objects.equals(null, o.getOfferId())`).
- For paywall accessors (`getActiveOffer`, `getIntroPhase`, etc.) there is no
  spec-level "always pick base plan" flag in v0.4. If you need the un-promoted
  recurring phase, use `getRecurringPrice(productId, basePlanId)` (filters phases by
  `INFINITE_RECURRING` recurrence mode) — it returns the renewal price regardless of
  which offer the selector picked.
- If you specifically want spec-driven flows to keep selecting the base plan as the
  default, file an issue — adding a `preferBasePlan` flag to `SubscriptionSpec` is a
  candidate follow-up.

### Added -- offer-level routing for multi-offer base plans

For paywalls that A/B between several offers on the same base plan (e.g.
`intro_variant_a` vs `intro_variant_b`) or need to read offer tags configured in Play
Console for cohort-aware UI.

- **`PlayBillingWrapper.getActiveOffer(productId, basePlanId)`** -- returns the typed
  `SubscriptionOfferDetails` wrapper that `subscribe(activity, productId, basePlanId)`
  would pay for right now. Same selection as `OfferSelector.pick`: registered spec's
  `preferredOfferId` > trial preference > first promo offer on the base plan > base
  plan offer (un-promoted). Returns `null` when no `SubscriptionSpec` is registered for
  the pair (mirrors the silent no-op of `subscribe(activity, productId, basePlanId)`
  with no spec). Exposes `getOfferId`, `getOfferTags`, `getOfferToken`,
  `getPricingPhases` for cohort-aware paywall branching.
- **`PlayBillingWrapper.getOfferToken(productId, basePlanId, offerId)`** -- exact-tuple
  lookup. Accepts a `@Nullable offerId`: pass `null` to target the un-promoted base plan
  offer directly. Returns `null` when Play omitted the offer under the eligibility
  filter or the id is wrong. Pure `ProductDetails` lookup -- no spec registration
  required, so this works for ad-hoc routing outside the configured catalog.
- **`PlayBillingWrapper.subscribe(activity, productId, basePlanId, offerId)`** -- new
  overload that pays for an exact Play Console offer id, bypassing the registered spec's
  trial / preferred-offer pick. Gated on `findSpec(productId, basePlanId)` -- if the
  pair is not registered via `BillingConfig.Builder.addSubscription`, emits `onError`
  and returns without launching checkout. Accepts a `@Nullable offerId`: pass `null` to
  pay for the base plan offer itself.
- **`OfferSelector.findByOfferId(details, basePlanId, offerId)`** + **`SubscriptionOfferDetails.from(sdk)`**
  -- static helpers used by the wrapper and exposed for advanced offer routing.
  `findByOfferId` accepts a `@Nullable offerId` and uses `Objects.equals` to allow
  lookup of the base plan offer (which Play returns with `offerId == null`).

### Documented

- New **`AGENTS.md`** at the project root: terse, copy-pasteable integration contract
  for AI coding agents (Claude Code, Cursor, Copilot, Codex, etc.). Covers minimum
  viable integration, decision matrix, offer-selection order, eligibility safety
  contract, idempotent delivery contract, common pitfalls, full API surface index.
- **`PlayBillingWrapper.parseIso8601DurationMillis(String)`** is now in the API
  reference. Already existed since v0.2; aliased on `PricingPhases.getPeriodDurationMillis()`.
- New **decision-matrix table for intro pricing** under the 5-step intro guide, mapping
  every common paywall question to the right accessor in one place.
- Explicit **eligibility safety contract** in README + `getActiveOffer` Javadoc: Play
  silently omits offers the account fails the eligibility filter for, so every
  wrapper accessor only ever returns offers Play would honour at checkout. There is no
  separate client-side eligibility "check" to forget.

## v0.4.0 — 2026-05-09

### Added -- first-class intro pricing

Making "cheap first period then normal price" offers (e.g. `$1 first week, then $19/year`)
a one-liner, symmetric with the existing free-trial API.

- **`SubscriptionSpec.withIntro(productId, basePlanId, introOfferId)`** -- sugar that sets
  `preferredOfferId` to the intro offer.
- **`PlayBillingWrapper.isIntroEligible(productId [, basePlanId])`** -- true when an offer
  with a non-zero `FINITE_RECURRING` phase passes Play's offer-eligibility filter on this
  account. Play omits offers the account fails the filter for (first-time-redeemer offer
  for a repeat buyer, missing audience tag, expired promo), so this is the correct
  "should I show the intro CTA?" gate. Mirrors `isTrialEligible`.
- **`getIntroPhase(id, basePlan)` / `getIntroPeriodIso(id, basePlan)`** -- typed accessor +
  ISO-8601 period for the intro phase. Spec-aware: prefers the registered
  `SubscriptionSpec`'s offer (covers combined trial+intro shapes), falls back to the first
  eligible offer on the base plan with an intro phase.
- **`getIntroEndMillis(purchase [, basePlan])`** -- deterministic wall-clock estimate of
  `purchaseTime + introPeriod * billingCycleCount`. Mirrors `getTrialEndMillis`. The
  registered `SubscriptionSpec` is the source of truth for which offer the user purchased
  -- a `preferTrial=true` spec that resolved to a trial-only offer returns `-1`, **not**
  the end of an unrelated intro offer on the same base plan.
- **`getIntroPrice(id, basePlan)` / `getRecurringPrice(id, basePlan)`** -- formatted price
  strings for paywall CTAs, disambiguating the existing `getFormattedPrice` which returns
  the first non-trial phase (i.e. the intro price when an intro offer is selected).
- **`BillingAnalytics.onIntroStarted(productId, periodIso, billingCycleCount, purchase)`**
  -- default no-op hook. Fires once per `purchaseToken` on first-time delivery,
  **independent of `onTrialStarted`**: a combined offer (free trial -> intro week ->
  recurring) fires both events for the same purchase. Pure-trial / pure-intro offers fire
  only their respective event. Dedupe in your analytics pipeline if you need a single
  funnel signal per checkout.
- **`OfferSelector.isIntroEligible(details, basePlanId)`** + **`hasIntroPhase(offer)`** --
  static helpers used by the wrapper and exposed for advanced offer routing.

### Changed

- **`getFormattedPrice(productId, basePlanId)` semantics clarified.** The method always
  returned the first non-trial pricing phase. With the new intro-pricing surface, that
  first non-trial phase is the *intro* price (e.g. `"$1.00"`) when an intro offer is
  selected via `SubscriptionSpec.withIntro(...)` -- not the recurring price. Paywall
  surfaces that always want the renewal price should migrate to the new
  `getRecurringPrice(productId, basePlanId)`. No code change; documenting so integrators
  upgrading from v0.3.0 who start using `withIntro` aren't surprised by labels flipping
  from recurring → intro.

## v0.3.0 (post-review hardening) — 2026-04-21

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
