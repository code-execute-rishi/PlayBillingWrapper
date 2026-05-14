# AGENTS.md — PlayBillingWrapper

Model-readable integration spec. Read this first if you are an AI agent (Claude Code,
Cursor, Copilot, Codex, etc.) wiring up Play Billing v8 via this library. Keep this
file in your working set when generating integration code.

`README.md` is the human guide and has prose + rationale. This file is the terse,
copy-pasteable contract.

---

## 1. What this library is

`PlayBillingWrapper` (`com.playbillingwrapper:library`, Apache-2.0) is a thin facade
over Google Play Billing v8 for Android. Catalog-agnostic — every product id, base
plan id, offer id is supplied by the caller. No SaaS dependency, no hosted backend, no
hardcoded SKUs.

It handles: connection lifecycle, offer-token selection, 72-hour acknowledgment, intro
+ trial pricing introspection, idempotent purchase delivery across app restarts,
pending purchases, paused subscriptions, cancellation hooks, throttled refresh,
obfuscated account/profile id binding.

It does NOT: ship a paywall UI, manage entitlements server-side, verify receipts
server-side (do that yourself), localize strings, run experiments.

---

## 2. Minimum viable integration

```java
// Application.onCreate
BillingConfig cfg = BillingConfig.builder()
    .addLifetimeProductId("YOUR_LIFETIME_SKU")
    .addSubscription(SubscriptionSpec.withTrial("YOUR_SUB_SKU", "monthly"))
    .userId(sha256(stableUserIdentifier))           // required, ≤64 chars
    .build();
PlayBillingWrapper billing = new PlayBillingWrapper(app, cfg, new WrapperListener() {
    @Override public void onReady() { /* products + purchases reconciled */ }
    @Override public void onLifetimePurchased(PurchaseInfo p) { grantLifetime(p); }
    @Override public void onSubscriptionActivated(String pid, SubscriptionState s, PurchaseInfo p) {
        grantSubscription(pid, p);
    }
    @Override public void onError(BillingResponse r) { logError(r); }
});
billing.connect();
```

```java
// Activity (paywall)
billing.subscribe(activity, "YOUR_SUB_SKU", "monthly");   // uses spec's preferTrial/preferredOfferId
billing.purchaseProduct(activity, "YOUR_LIFETIME_SKU");
billing.isPremium();                                       // any entitlement?
```

`AndroidManifest.xml` must declare `<uses-permission android:name="com.android.vending.BILLING" />`.

---

## 3. Decision matrix — "I want X → call Y"

### Setup

| Want | Call |
|---|---|
| Register a subscription with auto-trial fallback | `SubscriptionSpec.withTrial(productId, basePlanId)` |
| Register an intro-pricing offer (`$1 first week`) | `SubscriptionSpec.withIntro(productId, basePlanId, introOfferId)` |
| Register a winback / promo offer by id | `SubscriptionSpec.builder().preferredOfferId(...).build()` |
| Bind sugar API (`subscribeMonthly`, `buyLifetime`) | `BillingConfig.Builder.defaultMonthly(...)`, `.defaultLifetimeProductId(...)` |

### Purchase

| Want | Call |
|---|---|
| Subscribe per registered spec | `billing.subscribe(activity, productId, basePlanId)` |
| Subscribe overriding trial preference | `billing.subscribe(activity, productId, basePlanId, boolean preferTrial)` |
| Subscribe to an exact Play Console offer id (A/B routing) | `billing.subscribe(activity, productId, basePlanId, String offerId)` — pass `null` for the un-promoted base plan offer |
| Buy a one-time product | `billing.purchaseProduct(activity, productId)` |
| v8 per-product purchase option | `billing.purchaseProduct(activity, productId, purchaseOptionId)` |
| Buy a consumable (coins/gems) | `billing.purchaseConsumable(activity, productId)` |
| Upgrade / downgrade / swap subscription | `billing.changeSubscription(activity, oldId, newId, newBasePlanId, ChangeMode.WITH_TIME_PRORATION)` |
| Open Play's manage-sub page | `billing.openManageSubscription(activity, productId)` |

### Entitlement

| Want | Call |
|---|---|
| User has any entitlement (lifetime or sub) | `billing.isPremium()` |
| User has any active subscription | `billing.isSubscribed()` |
| User owns specific lifetime SKU | `billing.isOwned(productId)` |
| Current subscription state (ACTIVE, PAUSED, CANCELED_ACTIVE, EXPIRED, PENDING) | `billing.subscriptionState(productId)` |
| All currently-entitled product ids | `billing.getActiveEntitlements()` |

### Pricing introspection

| Want | Call |
|---|---|
| Formatted price for paywall CTA | `billing.getFormattedPrice(productId, basePlanId)` |
| Intro phase formatted price (`"$0.99"`) | `billing.getIntroPrice(productId, basePlanId)` |
| Recurring renewal price (`"$4.99"`) | `billing.getRecurringPrice(productId, basePlanId)` |
| Intro phase as typed object | `billing.getIntroPhase(productId, basePlanId)` |
| All pricing phases (free → intro → recurring) | `billing.getOfferPhases(productId, basePlanId)` |
| The exact offer `subscribe(...)` would pay for | `billing.getActiveOffer(productId, basePlanId)` |
| Play offerToken by exact (product, basePlan, offerId) | `billing.getOfferToken(productId, basePlanId, offerId)` |
| Read Play Console cohort/experiment tags | `billing.getActiveOffer(...).getOfferTags()` |

### Eligibility

| Want | Call |
|---|---|
| User trial-eligible on this base plan? | `billing.isTrialEligible(productId, basePlanId)` |
| User intro-eligible on this base plan? | `billing.isIntroEligible(productId, basePlanId)` |
| Trial period ISO 8601 (`"P3D"`, `"P7D"`) | `billing.getTrialPeriodIso(productId, basePlanId)` |
| Intro period ISO 8601 (`"P1W"`, `"P1M"`) | `billing.getIntroPeriodIso(productId, basePlanId)` |
| Trial-end wall-clock estimate (millis) | `billing.getTrialEndMillis(purchase, basePlanId)` |
| Intro-end wall-clock estimate (millis) | `billing.getIntroEndMillis(purchase, basePlanId)` |
| ISO 8601 period → millis | `PlayBillingWrapper.parseIso8601DurationMillis("P1W")` |

### Lifecycle

| Want | Call |
|---|---|
| Connect Play Billing | `billing.connect()` |
| Connect with timeout + callback | `billing.connect(timeoutMs, Runnable)` |
| Force refresh products + purchases | `billing.restorePurchases()` |
| Throttled refresh (e.g. onResume) | `billing.restorePurchases(30_000L)` |
| Release on app shutdown | `billing.release()` |
| Is client ready? | `billing.isReady()` |

---

## 4. Critical contract: offer selection order

When the wrapper picks an offer to pay for at `subscribe(...)` checkout (or reads via
`getActiveOffer`, `getIntroPhase`, `getOfferPhases`, etc.), it applies
`OfferSelector.pick` to the registered `SubscriptionSpec`:

```
1. preferredOfferId           → if non-null and matches an offer, that wins.
2. preferTrial                → if true and a free-trial offer is eligible, that wins.
3. first promo on base plan   → first offer with offerId != null.
4. base plan offer            → the un-promoted recurring price (offerId == null).
```

**Why "promo before base plan":** Play silently omits promos the current account fails
the eligibility filter for (first-time-redeemer for a repeat buyer, missing audience
tag, expired promo, region mismatch). Any promo that survives into `ProductDetails`
is one Play will honour at checkout. Surfacing the best available promo by default
is revenue-positive; falling through to the un-promoted base plan would bury the
discount.

**To force the un-promoted base plan at checkout:**

```java
billing.subscribe(activity, productId, basePlanId, /* offerId */ null);
```

The `@Nullable offerId` overload routes to the base plan offer directly via
`Objects.equals(null, o.getOfferId())`. There is no spec-level "always pick base
plan" flag in v0.4; setting `SubscriptionSpec.preferredOfferId = null` means "no
preference", not "prefer the base plan offer".

---

## 5. Eligibility safety contract

Play silently omits offers the current Play account fails the eligibility filter for
from `ProductDetails.getSubscriptionOfferDetails()`. The wrapper inherits that as the
authoritative filter:

- Every accessor that returns an offer / phase / token (`getActiveOffer`,
  `getIntroPhase`, `getOfferPhases`, `getIntroPrice`, etc.) only ever returns offers
  Play would honour at checkout.
- `isTrialEligible` / `isIntroEligible` are direct queries against the filtered list
  — calling the right accessor IS the eligibility check; there is no separate "did I
  forget to gate" pitfall.
- "Promised intro price, charged full" is impossible through wrapper APIs because
  the un-eligible offer is not in the offer list at all.

**Eligibility flipped during the session** (sign-in, restore, cross-device): call
`billing.restorePurchases(...)` and re-evaluate from the next `onReady()` callback.
There is no separate eligibility listener — re-evaluating on `onReady()` is the
contract.

---

## 6. Idempotent delivery contract

`onLifetimePurchased` / `onSubscriptionActivated` / `onConsumablePurchased` fire **at
most once per `purchaseToken`**, persisted across app restarts via
`IdempotencyStore` (SharedPreferences-backed). The grant callback is the right place
to add entitlement; the listener will not re-fire for the same token on the next
launch.

After a refund / chargeback, call `billing.getIdempotencyStore().forget(token)` so a
future re-purchase with a recycled token gets delivered fresh.

---

## 7. Common pitfalls (do NOT do)

| Bad pattern | Why | Do this instead |
|---|---|---|
| Granting entitlement on `onPending(...)` | Pending purchase may never settle (cash/bank transfer that times out) | Grant only in `onLifetimePurchased` / `onSubscriptionActivated` |
| Calling `getFormattedPrice(productId, basePlanId)` expecting recurring price | Returns the first non-trial phase — could be the intro price | Use `getRecurringPrice(...)` explicitly |
| Picking offer token by index in `ProductDetails.getSubscriptionOfferDetails()` | Order is not stable; ineligible offers are omitted shifting indices | Use `OfferSelector.pick` or `getActiveOffer` |
| Implementing your own ISO 8601 parser for trial / intro periods | Loses the `"PnW"` / approximate-month / approximate-year semantics Play uses | Use `PricingPhases.getPeriodDurationMillis()` or `PlayBillingWrapper.parseIso8601DurationMillis` |
| Skipping `userId(...)` in `BillingConfig.Builder` | `build()` throws `IllegalArgumentException` (not optional — required for fraud binding) | Hash a stable user identifier (SHA-256) and pass it |
| Re-running `connect()` from `Activity.onResume()` | Hits Play on every navigation | `billing.restorePurchases(30_000L)` for throttled refresh |
| Mocking `BillingClient` in tests | Tight coupling, brittle | Use the unit tests in `library/src/test/java/com/playbillingwrapper` as a model — they mock `ProductDetails` only |

---

## 8. Listener surface (`WrapperListener`)

Every method has a `default` no-op body — override only what you need.

```java
void onProductsFetched(List<ProductInfo> products);  // fires as soon as queryProductDetailsAsync returns
void onReady();                                       // fires once after products + purchases reconciled
void onLifetimePurchased(PurchaseInfo);
void onConsumablePurchased(String productId, int quantity, PurchaseInfo);
void onSubscriptionActivated(String productId, SubscriptionState state, PurchaseInfo);
void onSubscriptionCancelled(String productId, PurchaseInfo);  // auto-renew flipped true→false
void onPending(PurchaseInfo);                          // slow payment, do NOT grant
void onUserCancelled();
void onError(BillingResponse);
```

Analytics surface (`BillingAnalytics`, optional via
`BillingConfig.Builder.analyticsListener(...)`): `onBeginCheckout`,
`onPurchaseCompleted`, `onSubscriptionActivated`, `onTrialStarted`, `onIntroStarted`,
`onSubscriptionCancelled`, `onConsumablePurchased`, `onUserCancelled`, `onError`.

`onTrialStarted` and `onIntroStarted` are **independent** — a combined offer
(free trial → intro phase → recurring) fires both.

---

## 9. State model — `SubscriptionState`

| Value | Meaning | Has entitlement? |
|---|---|---|
| `ACTIVE` | Auto-renewing, paid | Yes |
| `IN_TRIAL` | In free-trial phase | Yes |
| `CANCELED_ACTIVE` | User cancelled but paid period still active | Yes |
| `PAUSED` | Play-side pause; resumes automatically | No |
| `PENDING` | Slow payment in progress | No |
| `EXPIRED` | Lapsed | No |

Helper: `PlayBillingWrapper.isPremium()` / `isSubscribed()` collapse this to a single
boolean for "should we show premium features?".

---

## 10. Server-side verification (recommended)

The library does on-device signature verification when `base64LicenseKey(...)` is
configured, but a determined attacker can patch the APK. For production:

1. Send `purchase.getPurchaseToken()` + `productId` to your backend.
2. Verify against the Play Developer API (`purchases.subscriptionsv2.get` or
   `purchases.products.get`).
3. Grant entitlement server-side and gate features on a server check.

`obfuscatedAccountId` (the wrapper sets this from `userId(...)`) ties the purchase to
your user record for fraud detection. Read it back on the server side from the Play
Developer API.

---

## 11. Files of interest (when modifying the library itself)

| File | Purpose |
|---|---|
| `library/src/main/java/com/playbillingwrapper/PlayBillingWrapper.java` | Public facade — most API surface |
| `library/src/main/java/com/playbillingwrapper/OfferSelector.java` | Offer selection algorithm (single source of truth) |
| `library/src/main/java/com/playbillingwrapper/BillingConnector.java` | Lower-level `BillingClient` wrapper |
| `library/src/main/java/com/playbillingwrapper/model/SubscriptionSpec.java` | Catalog config |
| `library/src/main/java/com/playbillingwrapper/model/BillingConfig.java` | Top-level config builder |
| `library/src/main/java/com/playbillingwrapper/listener/WrapperListener.java` | High-level callback surface |
| `library/src/test/java/com/playbillingwrapper/OfferSelectorTest.java` | Selection algorithm tests — model for unit-testable patterns |

When extending the wrapper, the rule: **only `OfferSelector.pick` may decide which
offer wins.** Do not inline selection logic elsewhere — paywall accessors and
checkout flows must delegate to `OfferSelector.pickOffer` so they cannot drift.

---

## 12. Quick reference — full API by file

`PlayBillingWrapper.java` — see `README.md` § "Public API reference" for the
complete table of public methods grouped by purpose: Construction, Connection,
Generic purchase API, Sugar purchase API, Ownership + state queries, Trial period
introspection, Intro pricing introspection, Offer routing, Paywall price helpers,
Connection state, Management, Static helpers.

`OfferSelector.java` — public static helpers: `pick`, `isTrialEligible`,
`isIntroEligible`, `hasIntroPhase`, `findByOfferId`.

`model/SubscriptionOfferDetails.java` — typed offer wrapper with `getOfferId`,
`getOfferTags`, `getOfferToken`, `getBasePlanId`, `getPricingPhases`; static factory
`from(sdkOffer)`.

`model/SubscriptionOfferDetails.PricingPhases` — typed phase with `getFormattedPrice`,
`getPriceAmountMicros`, `getPriceCurrencyCode`, `getBillingPeriod`, `getPeriodIso`
(alias), `getBillingCycleCount`, `getRecurrenceMode`, `isFree`, `isIntro`,
`isRecurring`, `getPeriodDurationMillis`.

`model/SubscriptionSpec.java` — catalog entry; factories `of`, `withTrial`,
`withIntro`; fields `productId`, `basePlanId`, `preferTrial`, `preferredOfferId`,
`tag`.

`model/BillingConfig.Builder` — see `README.md` § "BillingConfig.Builder" table.

`status/SubscriptionState` — enum: `ACTIVE`, `IN_TRIAL`, `CANCELED_ACTIVE`, `PAUSED`,
`PENDING`, `EXPIRED`.

`type/ChangeMode` — enum mapping to Play's `ReplacementMode` for
upgrade/downgrade/swap: `WITH_TIME_PRORATION`, `CHARGE_PRORATED_PRICE`, etc.

`type/ErrorType` — enum surfaced through `BillingResponse.getErrorType()`.

`type/ProductType` / `type/SkuProductType` — Play SDK type aliases.
