# PlayBillingWrapper

[![CI](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/ci.yml/badge.svg)](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/ci.yml)
[![Release](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/release.yml/badge.svg)](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/release.yml)
[![JitPack](https://jitpack.io/v/code-execute-rishi/PlayBillingWrapper.svg)](https://jitpack.io/#code-execute-rishi/PlayBillingWrapper)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg)](https://android-arsenal.com/api?level=23)
[![Billing](https://img.shields.io/badge/Play%20Billing-8.3.0-blue.svg)](https://developer.android.com/google/play/billing/release-notes)

Java wrapper around Google Play Billing Library 8.3.0. Arbitrary product catalogs,
generic purchase API, one builder, zero backend.

Register any combination of **lifetime-style non-consumables**, **consumables** (coins /
gems / lives), and **subscription base plans** (with or without trials on any of them).
Upgrade / downgrade between subscriptions is wrapped with a named `ChangeMode` enum. Price
strings are Play-formatted and ready for paywall UI.

```java
// -- one-time unlocks and consumables --
billing.purchaseProduct(activity, "com.app.pro_lifetime_launch");
billing.purchaseConsumable(activity, "com.app.coins_500");

// -- subscriptions, any base plan, any trial preference --
billing.subscribe(activity, "com.app.premium", "monthly");            // auto-picks trial if eligible
billing.subscribe(activity, "com.app.premium", "yearly", false);      // no trial

// -- upgrade / downgrade --
billing.changeSubscription(activity,
        "com.app.premium", "com.app.premium", "yearly",
        ChangeMode.UPGRADE_PRORATE_NOW);

// -- ownership + state (works for any registered id) --
billing.isOwned("com.app.remove_ads");
billing.subscriptionState("com.app.premium", "monthly");
billing.getActiveEntitlements();

// -- paywall UI helpers --
billing.getFormattedPrice("com.app.pro_lifetime_launch");             // "$4.99"
billing.getFormattedPrice("com.app.premium", "monthly");              // "$3.99"
billing.getPricingPhases("com.app.premium", "monthly");               // free trial + recurring
billing.getTrialPeriodIso("com.app.premium", "monthly");              // "P3D"

// -- lifecycle --
billing.restorePurchases(30_000L);                                    // throttled
billing.isReady();                                                    // connection + fetch state
```

---

## Table of contents

1. [Why this library exists](#why-this-library-exists)
2. [What it does for you](#what-it-does-for-you)
3. [What it does NOT do](#what-it-does-not-do-gaps--caveats)
4. [Installation](#installation)
5. [Play Console setup](#play-console-setup)
6. [Quick start (sugar API)](#quick-start-sugar-api)
7. [Versatile catalog (generic API)](#versatile-catalog-generic-api)
8. [Full usage walkthrough](#full-usage-walkthrough)
9. [Public API reference](#public-api-reference)
10. [State model](#state-model)
11. [Idempotent delivery](#idempotent-delivery)
12. [Error handling](#error-handling)
13. [Testing](#testing)
14. [Security notes](#security-notes)
15. [Troubleshooting](#troubleshooting)
16. [Versioning + auto-release](#versioning--auto-release)
17. [Roadmap](#roadmap)
18. [License + credits](#license--credits)

---

## Why this library exists

Raw `BillingClient` is ~1,500 lines of boilerplate per app. RevenueCat, Adapty, and Qonversion
solve it but lock you to a SaaS backend and cut ~1% of every dollar in runtime fees.

Open-source wrappers on GitHub (e.g. `moisoni97/google-inapp-billing`, `MFlisar/KotBilling`)
skip the pieces that actually leak revenue:

- The 72-hour acknowledgment rule.
- Offer-token selection for subscription trials (picking by index is brittle).
- `obfuscatedAccountId` for fraud binding and trial-per-account enforcement.
- Idempotent purchase delivery across app restarts.
- PENDING purchases that take days to settle (cash / bank transfer).

**PlayBillingWrapper** is the middle path: thin, permissively licensed (Apache-2.0),
opinionated but catalog-agnostic, and safe by default. Your paywall coordinator decides
which SKU to route to — the library just executes.

---

## What it does for you

- **Arbitrary catalogs.** Declare N non-consumable products + N `SubscriptionSpec` entries
  (productId + basePlanId + optional `preferTrial` + optional `preferredOfferId`). Every
  combination the Play Console supports is expressible.
- **Generic purchase API** — `purchaseProduct(activity, id)` and
  `subscribe(activity, productId, basePlanId)` work for any registered SKU. Trial
  preference on any subscription (monthly with 3-day trial is identical to yearly with
  7-day trial).
- **Legacy 3-shape sugar** — `buyLifetime` / `subscribeMonthly` / `subscribeYearlyWithTrial`
  still ship as convenience aliases for the default bindings you optionally wire up.
- **`BillingClient` lifecycle** + reconnect with exponential backoff.
- **Ownership queries for any product** — `isOwned(productId)`,
  `subscriptionState(productId, basePlanId)`, `getActiveEntitlements()`, `isPremium()`.
- **Auto-acknowledgment** with a 3-retry exponential backoff (prevents 72-hour auto-refund).
- **Pending-purchase auto-retry** with an effectively unbounded timeout. Real cash / bank-
  transfer PENDING purchases can take days — the wrapper keeps the `purchaseToken` alive
  and reconciles on every reconnect.
- **Idempotent callback delivery** — `onLifetimePurchased` / `onSubscriptionActivated` fire
  at most once per `purchaseToken`, persisted across app restarts via `SharedPreferences`.
- **Cancellation hook** — `onSubscriptionCancelled(productId, purchase)` fires once when
  `isAutoRenewing` flips from true to false, so re-engagement schedulers can latch onto the
  transition without polling.
- **Throttled refresh** — `restorePurchases(long minIntervalMs)` dedupes rapid `onResume`
  calls so you don't hammer Play on every navigation while still catching web-redeemed
  promos and cross-device sync.
- **Trial period introspection** — `getTrialPeriodIso("com.app.premium", "monthly")` returns
  the Play `P3D` / `P7D` / `P14D` ISO 8601 billing period so you can schedule a
  trial-ending reminder. `getTrialEndMillis(purchase)` gives an absolute estimate.
- **`obfuscatedAccountId` + `obfuscatedProfileId`** are wired onto every `BillingFlowParams`
  for fraud binding, trial-per-account enforcement, and server-side token ↔ user mapping.
- **`OfferSelector`** picks subscription offer tokens by `basePlanId` + optional `offerId`,
  auto-preferring a free-trial offer when Play still reports the user as trial-eligible.
- **`SubscriptionState` enum** — `ACTIVE`, `CANCELED_ACTIVE`, `PENDING`, `EXPIRED`.
- **Optional on-device signature verification** via the Play Console base64 public key.
  Off by default; the recommended path is server-side verification.
- **Deep-link to Play's manage-subscription page** in one call.
- **Synchronous `launchBillingFlow` failures** (stale offer token, bad Activity state) are
  surfaced via `onError` rather than silently dropped.
- **Null-safe callbacks post-release()** — in-flight BillingClient callbacks cannot NPE a
  destroyed caller.

---

## What it does NOT do (gaps + caveats)

Read this section before you ship. Everything here is either out of scope for v0.x, or
requires a backend that this library intentionally does not force on you.

### Covered scenarios

A non-exhaustive list of real-app patterns the library now handles:

| Scenario | Supported by |
|----------|--------------|
| N lifetime SKUs (standard + discount + legacy) | `addLifetimeProductId(...)` + `purchaseProduct(activity, id)`. |
| Discount SKU routed after paywall dismissal | Caller's PaywallCoordinator picks the id, `purchaseProduct(activity, chosenId)`. |
| Consumable coins / gems / lives | `addConsumableProductId(...)` + `purchaseConsumable(activity, id)` + `onConsumablePurchased(id, qty, purchase)`. |
| Multi-quantity consumables (user buys 10 coin packs in one tap) | `Purchase.getQuantity()` surfaced via `onConsumablePurchased(..., quantity, ...)`. |
| Monthly with 3-day trial | `addSubscription(SubscriptionSpec.withTrial("...", "monthly"))` + `subscribe(activity, id, "monthly")`. |
| Yearly without trial alongside monthly with trial | Two specs on the same `productId`, different `basePlanId`. |
| Upgrade monthly → yearly with proration | `changeSubscription(activity, oldId, newId, newBasePlanId, ChangeMode.UPGRADE_PRORATE_NOW)`. |
| Downgrade yearly → monthly at next renewal | `changeSubscription(..., ChangeMode.DOWNGRADE_DEFERRED)`. |
| Explicit winback / promo offer id | `SubscriptionSpec.builder().preferredOfferId("winback_25").build()`. |
| Paywall price labels | `getFormattedPrice(id)` for one-time, `getFormattedPrice(id, basePlanId)` for subs. |
| Intro pricing UI ("Free for 3 days, then $3.99/mo") | `getPricingPhases(id, basePlanId)` returns every phase with its price + period. |
| Refresh on resume with throttle | `restorePurchases(30_000L)`. |
| ReEngage scheduler on cancel | `onSubscriptionCancelled(productId, purchase)` listener hook. |
| Trial-ending reminder | `getTrialPeriodIso(id, basePlanId)` + `getTrialEndMillis(purchase)`. |
| Legacy SKU ownership check | `isOwned("com.app.remove_ads")`. |
| Active-entitlement analytics | `getActiveEntitlements()`. |
| Connection gating before UI | `isReady()` + `isPurchaseReconciliationComplete()`. |

### Out of scope (planned or deliberately omitted)

| Feature | Status | Workaround |
|---------|--------|------------|
| Installment plans / prepaid plans | Out of scope | `rawConnector()` + raw `BillingFlowParams`. |
| One-time product offers (v8 feature) | Out of scope | `rawConnector()`. |
| Kotlin coroutines / Flow extensions | Out of scope — the library is Java so Kotlin consumers work without them | Wrap callbacks in `suspendCancellableCoroutine` if you want. |
| Jetpack Compose helpers | Out of scope | Build your own `StateFlow` wrapper. |
| Alternative billing / user-choice billing (EU DMA, South Korea) | Out of scope | Not relevant for most apps. |

### States this library **cannot** detect from the client alone

Google Play's richer subscription states are not observable from a client `Purchase` object
in a reliable way:

- `IN_TRIAL` — whether the current Purchase is in its free-trial pricing phase vs paid phase.
  The v0.1 enum value is **deprecated and never returned** by the wrapper; we return `ACTIVE`
  for any purchased + auto-renewing subscription.
- `GRACE_PERIOD` — payment failed, user still entitled while Play retries.
- `ON_HOLD` — payment retries exhausted, entitlement revoked.
- `PAUSED` — user paused the subscription through Play.
- `REVOKED` — chargeback / voided purchase.

If your revenue depends on any of these, stand up a server and call the
[Play Developer API's `subscriptionsv2.get`](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get).
The wrapper does not provide this.

### Things you still must do yourself

- **Server-side purchase verification.** On-device signature verification is weak (the key
  sits in your APK). The recommended path is to POST the `purchaseToken` + `productId` +
  `obfuscatedAccountId` from `onLifetimePurchased` / `onSubscriptionActivated` to your
  backend, call the Play Developer API to verify, and grant entitlement based on the
  server's response. See [`docs/SECURITY.md`](docs/SECURITY.md).
- **Real-Time Developer Notifications (RTDN).** The library does not ship a Pub/Sub
  consumer. Without RTDN, you can't observe grace-period / on-hold / paused / refunded
  transitions that happen outside the device.
- **`sha256(userId)`**. The library needs a stable one-way-hashed user id for
  `obfuscatedAccountId`. It does not compute this for you (intentional — the hashing is a
  policy decision you own).
- **Activity lifecycle wiring**. You must call `billing.setListener(this)` in the paywall
  Activity's `onCreate` and `billing.setListener(null)` in `onDestroy`. The wrapper cannot
  do this for you.

### Known footguns

- The install coordinate must use the `v` prefix: `v0.1.3`, not `0.1.3`. JitPack's version
  normalization behaves inconsistently for this repo; the v-prefixed tag always resolves.
- `compileSdk 36` is validated but triggers an AGP warning; suppressed via
  `android.suppressUnsupportedCompileSdk=36` in `gradle.properties`. Remove once AGP 8.13+
  ships.
- Minimum `minSdk 23` (Android 6.0). Lower is not supported.
- The wrapper is single-`PlayBillingWrapper`-per-process. Creating multiple instances
  backed by the same `SharedPreferences` idempotency ledger works, but concurrent
  `markHandled` calls serialize on a single instance's monitor — keep it a singleton.
- `getTrialEndMillis()` is a client-side estimate derived from `purchaseTime` plus the
  ISO 8601 trial duration. For authoritative expiry use the Play Developer API.
- `onSubscriptionCancelled` fires once per `purchaseToken` when `isAutoRenewing` flips to
  false, persisted in a dedicated ledger (`pbw_cancel_idempotency`). A re-cancellation on
  a fresh purchase with a different token fires again.

---

## Installation

### Step 1 — Add JitPack to your root `settings.gradle`

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### Step 2 — Declare the dependency in `app/build.gradle`

```gradle
dependencies {
    implementation 'com.github.code-execute-rishi:PlayBillingWrapper:v0.2.0'
}
```

> Use the **v-prefixed** tag name in the coordinate. `0.1.3` without the `v` will fail to
> resolve on JitPack for this repo.

### Step 3 — Declare the Play Billing permission in `AndroidManifest.xml`

```xml
<uses-permission android:name="com.android.vending.BILLING" />
```

### Step 4 — Java 17 source/target (AGP 8.7+ defaults)

No special config; the library is compiled against `JavaVersion.VERSION_17` and re-exports
its `api` dependencies (billing 8.3.0, lifecycle-common, annotation).

---

## Play Console setup

### Lifetime (one-time unlock)

1. Play Console → Monetize → Products → **In-app products** → Create product.
2. Product id: `com.yourapp.lifetime` (use whatever id you pass to the builder).
3. Type: **Managed product** (non-consumable).
4. Activate.

### Monthly subscription (no trial)

1. Play Console → Monetize → Products → **Subscriptions** → Create subscription.
2. Product id: `com.yourapp.premium_monthly`.
3. Add a **Base plan** — id `monthly`, billing period `P1M`, auto-renewing.
4. Activate the subscription and the base plan.

### Yearly subscription with trial

1. Play Console → Monetize → Products → **Subscriptions** → Create subscription.
2. Product id: `com.yourapp.premium_yearly`.
3. Add a **Base plan** — id `yearly`, billing period `P1Y`, auto-renewing.
4. Add a **Developer-determined offer** on the base plan:
   - Offer id: `freetrial`
   - Eligibility: **New customer acquisition → New customers**
   - Phase 1: Free, `P7D`
5. Activate the subscription + the base plan + the offer.

### Uploading your app

Play Billing returns empty product lists for apps that are not yet uploaded. Upload a
signed build to at least the **Internal testing** track before testing.

### License testers

Play Console → Settings → **License testing** → add each tester's Google account. Test
purchases are free and can be cancelled from
`https://play.google.com/store/account/subscriptions`.

---

## Quick start (sugar API)

Use the sugar API when your catalog fits exactly one lifetime product + one monthly
subscription + one yearly subscription with trial. Otherwise skip ahead to
[Versatile catalog (generic API)](#versatile-catalog-generic-api).

### 1. Configure once in your `Application`

```java
public class MyApp extends Application {
    private PlayBillingWrapper billing;

    public static PlayBillingWrapper billing(Context c) {
        return ((MyApp) c.getApplicationContext()).billing;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        BillingConfig cfg = BillingConfig.builder()
            .lifetimeProductId("com.yourapp.lifetime")
            .monthlySubProductId("com.yourapp.premium_monthly")
            .monthlyBasePlanId("monthly")
            .yearlySubProductId("com.yourapp.premium_yearly")
            .yearlyBasePlanId("yearly")
            .yearlyTrialOfferId("freetrial")        // optional — auto-detect if null
            .userId(sha256(currentUserId()))        // required
            .enableLogging(BuildConfig.DEBUG)
            .autoAcknowledge(true)                  // default
            .build();

        billing = new PlayBillingWrapper(this, cfg, /*listener=*/null);
        billing.connect();
    }
}
```

### 2. Bind a listener to your paywall Activity

```java
public class PaywallActivity extends AppCompatActivity implements WrapperListener {

    private PlayBillingWrapper billing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        billing = MyApp.billing(this);
        billing.setListener(this);

        findViewById(R.id.btn_lifetime).setOnClickListener(v -> billing.buyLifetime(this));
        findViewById(R.id.btn_monthly).setOnClickListener(v -> billing.subscribeMonthly(this));
        findViewById(R.id.btn_yearly).setOnClickListener(v ->
            billing.subscribeYearlyWithTrial(this));
    }

    @Override
    protected void onDestroy() {
        billing.setListener(null);   // prevent Activity leak
        super.onDestroy();
    }

    @Override public void onReady() {
        // Products + ownership have been fetched. Safe to call hasLifetime / monthlyState.
    }

    @Override
    public void onLifetimePurchased(@NonNull PurchaseInfo purchase) {
        grantLifetimeEntitlement(purchase.getPurchaseToken());
    }

    @Override
    public void onSubscriptionActivated(@NonNull String productId,
                                        @NonNull SubscriptionState state,
                                        @NonNull PurchaseInfo purchase) {
        grantSubscriptionEntitlement(productId, purchase.getPurchaseToken());
    }

    @Override
    public void onPending(@NonNull PurchaseInfo purchase) {
        // Slow payment method (cash, bank transfer). Do NOT grant entitlement yet.
        showToast("Payment is processing. You'll be notified when it completes.");
    }

    @Override
    public void onUserCancelled() { /* user dismissed Play dialog */ }

    @Override
    public void onError(@NonNull BillingResponse response) {
        showToast("Purchase failed: " + response.getDebugMessage());
    }
}
```

### 3. Gate premium features anywhere

```java
if (MyApp.billing(this).isPremium()) {
    showPremiumContent();
} else {
    showPaywall();
}
```

---

## Versatile catalog (generic API)

Real apps rarely fit the 3-shape mold exactly. Typical real catalog:

| SKU | Purpose |
|-----|---------|
| `com.app.pro_lifetime` | Standard lifetime unlock. |
| `com.app.pro_lifetime_launch` | Discount lifetime SKU routed after first paywall dismissal. |
| `com.app.remove_ads` | Legacy SKU from an earlier app version — still entitles users. |
| `com.app.upgrade_to_pro` | Legacy SKU. |
| `com.app.premium` → base plan `monthly` | Monthly sub with 3-day trial. |
| `com.app.premium` → base plan `yearly` | Yearly sub, no trial. |

Declare the whole catalog in one builder call and route at purchase time:

```java
public class MyApp extends Application {
    private PlayBillingWrapper billing;

    @Override public void onCreate() {
        super.onCreate();
        BillingConfig cfg = BillingConfig.builder()
            .addLifetimeProductId("com.app.pro_lifetime")
            .addLifetimeProductId("com.app.pro_lifetime_launch")
            .addLifetimeProductId("com.app.remove_ads")
            .addLifetimeProductId("com.app.upgrade_to_pro")
            .addSubscription(SubscriptionSpec.withTrial("com.app.premium", "monthly"))
            .addSubscription(SubscriptionSpec.of("com.app.premium", "yearly"))
            .userId(sha256(currentUserId()))
            .enableLogging(BuildConfig.DEBUG)
            .build();

        billing = new PlayBillingWrapper(this, cfg, /*listener=*/null);
        billing.connect();
    }
}
```

### PaywallCoordinator example (discount-after-dismiss)

```java
String chosenSku = dismissalCount >= 1
        ? "com.app.pro_lifetime_launch"
        : "com.app.pro_lifetime";
billing.purchaseProduct(activity, chosenSku);
```

### Monthly subscription with a 3-day trial

```java
// Registered as .withTrial(...), so subscribe() auto-picks the trial offer when eligible.
billing.subscribe(activity, "com.app.premium", "monthly");

// Or explicitly force no trial, overriding the registered preference:
billing.subscribe(activity, "com.app.premium", "monthly", /*preferTrial=*/false);
```

### Checking a legacy SKU

```java
if (billing.isOwned("com.app.remove_ads")
        || billing.isOwned("com.app.upgrade_to_pro")) {
    // Legacy entitlement still honored.
    grantPro();
}
```

### `getActiveEntitlements()`

```java
List<String> owned = billing.getActiveEntitlements();
// -> ["com.app.pro_lifetime_launch"]   after the discount flow succeeds
// -> ["com.app.premium"]                after monthly subscription
// -> []                                  free user
analytics.log("active_entitlements", owned);
```

### Re-engage on subscription cancellation

```java
class Paywall extends AppCompatActivity implements WrapperListener {
    @Override
    public void onSubscriptionCancelled(@NonNull String productId,
                                        @NonNull PurchaseInfo purchase) {
        long trialEnd = billing.getTrialEndMillis(purchase);
        if (trialEnd > 0) {
            reEngageScheduler.scheduleAt(trialEnd - ONE_HOUR);
        } else {
            reEngageScheduler.schedule(productId);
        }
    }
}
```

### Schedule a trial-ending reminder from the ISO 8601 period

```java
String periodIso = billing.getTrialPeriodIso("com.app.premium", "monthly");  // "P3D"
long trialEndMs  = billing.getTrialEndMillis(currentMonthlyPurchase);
if (trialEndMs > 0) {
    reminderScheduler.at(trialEndMs - SIX_HOURS, "Your 3-day trial ends soon.");
}
```

### Throttled refresh on resume

```java
@Override
protected void onResume() {
    super.onResume();
    // Catch web-redeemed promo codes + cross-device sync, but never more than once per 30s.
    billing.restorePurchases(30_000L);
}
```

### Consumables (coins / gems / lives)

```java
BillingConfig cfg = BillingConfig.builder()
    .addConsumableProductId("com.app.coins_100")
    .addConsumableProductId("com.app.coins_500")
    .addConsumableProductId("com.app.gems_pack")
    .userId(sha256(userId))
    .build();

// Buy -- auto-consumed on Play's side after successful delivery.
billing.purchaseConsumable(activity, "com.app.coins_500");

// Grant the in-game resource on the consume callback.
@Override
public void onConsumablePurchased(@NonNull String productId,
                                  int quantity,
                                  @NonNull PurchaseInfo purchase) {
    switch (productId) {
        case "com.app.coins_100":  coins += 100 * quantity; break;
        case "com.app.coins_500":  coins += 500 * quantity; break;
        case "com.app.gems_pack":  gems  +=  10 * quantity; break;
    }
    wallet.persist();
}
```

Always multiply by `quantity` — users can buy 1-to-999 of the same SKU in a single Play
transaction and the library surfaces the count verbatim.

### Upgrade / downgrade between subscriptions

Named modes wrap Play's `SubscriptionUpdateParams.ReplacementMode`:

| `ChangeMode` | Play constant | Typical use |
|--------------|--------------|-------------|
| `UPGRADE_PRORATE_NOW` | `CHARGE_PRORATED_PRICE` | Monthly → yearly, charge the prorated delta now. |
| `UPGRADE_CHARGE_FULL` | `CHARGE_FULL_PRICE` | Upgrade with full new price + credit the unused time as extension. |
| `SWAP_WITH_TIME_CREDIT` | `WITH_TIME_PRORATION` | Swap, credit unused time, no new charge until credit is used. |
| `SWAP_WITHOUT_PRORATION` | `WITHOUT_PRORATION` | Swap now, new price applies at next renewal (preserves trial). |
| `DOWNGRADE_DEFERRED` | `DEFERRED` | Yearly → monthly at next renewal. |

```java
billing.changeSubscription(activity,
        "com.app.premium",           // old productId (looked up in owned purchases)
        "com.app.premium",           // new productId
        "yearly",                    // new basePlanId
        ChangeMode.UPGRADE_PRORATE_NOW);
```

The library finds the `oldPurchaseToken` automatically from the owned-purchases list, so
you never touch raw Play tokens.

### Paywall pricing UI

`getFormattedPrice(...)` always returns Play-computed, locale-correct strings — show
them verbatim, never re-format.

```java
String lifetimePrice = billing.getFormattedPrice("com.app.pro_lifetime");
// -> "$4.99"  (or "₹399.00" in IN, "€4.49" in DE, etc.)

String monthlyPrice = billing.getFormattedPrice("com.app.premium", "monthly");
// -> "$3.99"
```

For an intro-pricing banner ("Free for 3 days, then $3.99/mo") walk the pricing phases:

```java
List<ProductDetails.PricingPhase> phases =
        billing.getPricingPhases("com.app.premium", "monthly");

for (ProductDetails.PricingPhase phase : phases) {
    if (phase.getPriceAmountMicros() == 0L) {
        banner.setTrialText("Free for " + isoPretty(phase.getBillingPeriod()));
    } else {
        banner.setRecurringText(phase.getFormattedPrice() + " / " + isoPretty(phase.getBillingPeriod()));
    }
}
```

### Connection gating

```java
findViewById(R.id.btn_buy).setEnabled(billing.isReady());
```

`isReady()` returns true only after product details have been fetched AND
`BillingClient` is connected. For post-restore state checks use
`isPurchaseReconciliationComplete()` to avoid querying before owned purchases are loaded.

### Mixing sugar + generic

Nothing stops you from wiring a default sugar binding AND extra catalog entries:

```java
BillingConfig cfg = BillingConfig.builder()
    // default bindings -> buyLifetime() / subscribeMonthly() / subscribeYearlyWithTrial()
    .defaultLifetimeProductId("com.app.pro_lifetime")
    .defaultMonthlyWithTrial("com.app.premium", "monthly")
    .defaultYearly("com.app.premium", "yearly")

    // extras used only via the generic API
    .addLifetimeProductId("com.app.pro_lifetime_launch")
    .addLifetimeProductId("com.app.remove_ads")
    .addLifetimeProductId("com.app.upgrade_to_pro")

    .userId(sha256(userId))
    .build();
```

---

## Full usage walkthrough

### Obtaining an `obfuscatedAccountId`

The library's `userId(...)` builder setter maps to `BillingFlowParams.setObfuscatedAccountId`.
Play uses it to:

- Flag multi-account fraud patterns.
- Enforce "one free trial per account".
- Let your server map a `purchaseToken` back to a user.

It must be a stable, one-way hash of your internal user id. Never send raw PII. Example:

```java
static String sha256(String input) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.substring(0, 64);  // Play's hard cap
    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
    }
}
```

If the user is not yet logged in, use a stable install id (e.g. a persisted UUID) as a
placeholder and migrate once they sign in.

### Restoring purchases

Automatic on `connect()`. Also call manually:

```java
billing.restorePurchases();  // triggers a fresh query cycle
```

Typical placement: `Activity.onResume()` of the paywall, or a "Restore purchases" button.

### Checking trial eligibility before showing CTA

Play silently omits ineligible offers from `ProductDetails`, so the presence of a free-trial
offer on the yearly base plan doubles as the eligibility signal:

```java
if (billing.isTrialEligibleForYearly()) {
    ctaButton.setText("Start 7-day free trial");
} else {
    ctaButton.setText("Subscribe yearly");
}
```

### Opening Play's manage-subscription page

Play policy requires an in-app cancellation path. One call:

```java
billing.openManageSubscription(activity, "com.yourapp.premium_yearly");
```

To open the generic list of all user subscriptions, drop to
`https://play.google.com/store/account/subscriptions` via `Intent.ACTION_VIEW`.

### Server-side acknowledgment (optional)

If your backend is the source of truth, flip `autoAcknowledge(false)` and acknowledge from
your server via the Play Developer API. You then must call
`billing.rawConnector().acknowledgePurchase(...)` yourself after the server confirms.

---

## Public API reference

### `PlayBillingWrapper`

#### Construction

```java
PlayBillingWrapper(Context context, BillingConfig config, @Nullable WrapperListener listener)
PlayBillingWrapper(Context context, BillingConfig config, @Nullable WrapperListener listener,
                   @Nullable Lifecycle lifecycle)
```

Pass an Application or Activity; `getApplicationContext()` is always used internally.
Optionally pass `ProcessLifecycleOwner.get().getLifecycle()` so `release()` is called on
`ON_DESTROY` automatically.

#### Connection

| Method | Description |
|--------|-------------|
| `void connect()` | Open the billing connection. Idempotent. Also re-queries products + purchases. |
| `void release()` | Close the connection. Safe to call repeatedly. |
| `void restorePurchases()` | Force a fresh product + ownership query cycle. |
| `boolean restorePurchases(long minIntervalMs)` | Throttled variant. Returns `true` when a refresh actually ran. |

#### Generic purchase API

| Method | Behaviour |
|--------|-----------|
| `void purchaseProduct(Activity, String productId)` | Launch the Play flow for any registered non-consumable product. |
| `void purchaseConsumable(Activity, String productId)` | Launch the Play flow for a consumable (coins / gems / lives). Auto-consumed after delivery. |
| `void subscribe(Activity, String productId, String basePlanId)` | Launch the subscription flow using the registered `SubscriptionSpec`'s trial preference and preferred offer id. |
| `void subscribe(Activity, String productId, String basePlanId, boolean preferTrial)` | Same, but override the registered trial preference for this invocation. |
| `void subscribe(Activity, SubscriptionSpec)` | Launch with an ad-hoc spec (A/B tests). |
| `void changeSubscription(Activity, String oldId, String newId, String newBasePlanId, ChangeMode)` | Upgrade / downgrade / swap. `oldPurchaseToken` is looked up automatically from the owned-purchases list. |
| `void openManageSubscription(Activity, String productId)` | Deep-link into Play. |

#### Sugar purchase API (backward-compatible)

| Method | Behaviour |
|--------|-----------|
| `void buyLifetime(Activity)` | Forwards to `purchaseProduct` using `defaultLifetimeProductId`. |
| `void subscribeMonthly(Activity)` | Forwards to `subscribe` using `defaultMonthlySpec`. |
| `void subscribeYearlyWithTrial(Activity)` | Forwards to `subscribe` using `defaultYearlySpec`. |

#### Ownership + state queries

| Method | Returns |
|--------|---------|
| `boolean isOwned(String productId)` | User holds this product in PURCHASED state. |
| `boolean hasLifetime()` | Alias for `isOwned(defaultLifetimeProductId)`. |
| `SubscriptionState subscriptionState(String productId, String basePlanId)` | Explicit state lookup. |
| `SubscriptionState subscriptionState(String productId)` | First matching purchase wins. |
| `SubscriptionState monthlyState()` / `yearlyState()` | Default-spec sugar. |
| `boolean isTrialEligible(String productId, String basePlanId)` | Any free-trial offer on the base plan? |
| `boolean isTrialEligibleForYearly()` | Sugar for default yearly spec. |
| `boolean isSubscribed()` | Any registered subscription is entitling. |
| `boolean isPremium()` | Any lifetime product owned OR `isSubscribed()`. |
| `List<String> getActiveEntitlements()` | Product ids the user currently holds entitlement for. |
| `List<PurchaseInfo> getOwnedPurchases()` | Immutable snapshot of raw owned purchases. |

#### Trial period introspection

| Method | Returns |
|--------|---------|
| `String getTrialPeriodIso(String productId, String basePlanId)` | ISO 8601 billing period of the first trial offer on the base plan, e.g. `"P3D"`, `"P7D"`, `"P14D"`. `null` if no trial offer is available. |
| `long getTrialEndMillis(PurchaseInfo)` | Wall-clock estimate of `purchaseTime + trialDuration`. `-1` if the purchase has no trial. |

#### Paywall price helpers

| Method | Returns |
|--------|---------|
| `String getFormattedPrice(String productId)` | Play-formatted price string for a one-time product (lifetime or consumable). |
| `String getFormattedPrice(String productId, String basePlanId)` | Formatted price of the first non-trial pricing phase of the best offer on a base plan. |
| `List<ProductDetails.PricingPhase> getPricingPhases(String productId, String basePlanId)` | Every pricing phase of the best offer (trial auto-preferred, then preferredOfferId, then base plan). |

#### Connection state

| Method | Returns |
|--------|---------|
| `boolean isReady()` | `true` when `BillingClient` is connected AND product details have been fetched. |
| `boolean isPurchaseReconciliationComplete()` | `true` after the first INAPP + SUBS `queryPurchasesAsync` round completes. |

#### Management

| Method | Description |
|--------|-------------|
| `void setListener(@Nullable WrapperListener)` | Swap the callback surface. |
| `BillingConnector rawConnector()` | Escape hatch for advanced use cases (upgrade/downgrade, consumables, installment plans). |
| `BillingConfig getConfig()` | Read the config back. |

### `SubscriptionSpec`

Declares one (`productId`, `basePlanId`) pair with optional trial preference and offer id.

```java
SubscriptionSpec.of("com.app.premium", "monthly");
SubscriptionSpec.withTrial("com.app.premium", "monthly");          // 3-day trial monthly
SubscriptionSpec.builder()
    .productId("com.app.premium")
    .basePlanId("monthly")
    .preferTrial(true)
    .preferredOfferId("winback_25")     // overrides trial auto-pick
    .tag("monthly_discount")            // for your paywall coordinator
    .build();
```

### `BillingConfig.Builder`

| Method | Required? | Purpose |
|--------|-----------|---------|
| `addLifetimeProductId(String)` | At least one of lifetime / consumable / subscription | Register a non-consumable product id. |
| `addLifetimeProductIds(Iterable)` | Optional | Bulk-add. |
| `addConsumableProductId(String)` | At least one of lifetime / consumable / subscription | Register a consumable product (coins / gems / lives). Auto-consumed after delivery. |
| `addConsumableProductIds(Iterable)` | Optional | Bulk-add. |
| `addSubscription(SubscriptionSpec)` | At least one of lifetime / consumable / subscription | Register one (productId, basePlanId) pair. |
| `addSubscriptions(Iterable)` | Optional | Bulk-add. |
| `defaultLifetimeProductId(String)` | Optional | Bind for `buyLifetime(activity)`. Also registers the id. |
| `defaultMonthly(productId, basePlanId)` | Optional | Bind for `subscribeMonthly(activity)` (no trial). |
| `defaultMonthlyWithTrial(productId, basePlanId)` | Optional | Same, trial auto-picked. |
| `defaultYearly(productId, basePlanId)` | Optional | Bind for the yearly sugar (no trial). |
| `defaultYearlyWithTrial(productId, basePlanId)` | Optional | Same, trial auto-picked. |
| `lifetimeProductId(String)` etc. | Optional | Legacy 3-shape aliases, still supported. |
| `userId(String)` | **yes** | One-way hashed stable user id (≤64 chars). |
| `profileId(String)` | Optional | For multi-profile apps. |
| `base64LicenseKey(String)` | Optional | Play Console → Monetization setup → Licensing. Enables weak on-device signature verification when non-null. |
| `enableLogging(boolean)` | Optional | Verbose logcat on the `BillingConnector` tag. Default false. |
| `autoAcknowledge(boolean)` | Optional | Default true. Flip off only if you acknowledge server-side. |

### `WrapperListener`

All methods have `default` no-op implementations — override only what you need.

```java
void onReady();                                              // ownership reconciliation complete
void onLifetimePurchased(PurchaseInfo);
void onConsumablePurchased(String productId,
                           int quantity,
                           PurchaseInfo);                    // fires after Play consumes it
void onSubscriptionActivated(String productId,
                             SubscriptionState state,
                             PurchaseInfo);
void onSubscriptionCancelled(String productId, PurchaseInfo); // auto-renew flipped off
void onPending(PurchaseInfo);                                 // slow payment, do NOT grant yet
void onUserCancelled();
void onError(BillingResponse);
```

### `OfferSelector`

Static helpers, usually invoked internally. Exposed for advanced offer routing.

```java
static String pick(ProductDetails details,
                   String basePlanId,
                   @Nullable String preferredOfferId,
                   boolean preferTrial);
static boolean isTrialEligible(ProductDetails details, String basePlanId);
```

### `IdempotencyStore`

`SharedPreferences`-backed dedupe ledger keyed on `purchaseToken`. Usually invoked
internally by the facade.

```java
new IdempotencyStore(context);
store.markHandled(purchaseToken);     // uses commit() for crash durability
store.isHandled(purchaseToken);
store.forget(purchaseToken);          // call on refund / chargeback
store.clearAll();                     // tests only
```

### `BillingResponse`

Thin wrapper over `BillingResult`.

```java
ErrorType getErrorType();
int getResponseCode();
String getDebugMessage();
```

### `ErrorType`

Full enumeration with Javadoc on each value in `library/src/main/java/com/playbillingwrapper/type/ErrorType.java`.

### `BillingConnector`

The underlying client, exposed via `rawConnector()`. Only reach for it for flows the facade
does not cover (consumables, upgrade/downgrade, installment plans). Most users should not
need it.

---

## State model

### `SubscriptionState`

| Value | Entitles user? | Source |
|-------|----------------|--------|
| `ACTIVE` | ✓ | Purchased + auto-renewing |
| `CANCELED_ACTIVE` | ✓ | Purchased + not auto-renewing, paid period not yet elapsed |
| `PENDING` | ✗ | Slow-payment method (cash, bank transfer) |
| `EXPIRED` | ✗ | No purchase record for this product |
| `IN_TRIAL` | — | **Deprecated**, never returned; retained for source compat. |

Grace-period / on-hold / paused / revoked are server-only states. If your business depends
on them, run a backend.

### `isPremium()` logic

```
isPremium() == hasLifetime() ||
               monthlyState() == ACTIVE || CANCELED_ACTIVE ||
               yearlyState()  == ACTIVE || CANCELED_ACTIVE
```

`PENDING` does NOT count as premium — you have not been paid yet.

---

## Idempotent delivery

Callbacks fire **at most once per `purchaseToken`**, persisted via `SharedPreferences` so
the guarantee holds across app restart, reinstall on the same user, and cache clear.

### Delivery sequence

1. Play returns a `Purchase` via `PurchasesUpdatedListener` or `queryPurchasesAsync`.
2. The wrapper checks signature (if a license key was provided), dedupes against the
   idempotency ledger, and either dispatches `onLifetimePurchased` /
   `onSubscriptionActivated` OR stays silent if the token is already recorded.
3. `markHandled(purchaseToken)` is called **before** the grant callback fires, so even an
   unacknowledged purchase delivered during `autoAcknowledge(false)` + server-ack flows is
   only delivered once.
4. If your grant fails (backend verification rejected, user id mismatch, etc.), call
   `IdempotencyStore#forget(purchaseToken)` to allow a future re-delivery.

### Refund handling

On refund / chargeback, call `billing.getIdempotencyStore().forget(token)`
so a re-purchase with a recycled token is handled fresh. There is no built-in refund
listener — you need a server and RTDN for that signal.

---

## Error handling

Every failure arrives via `WrapperListener#onError(BillingResponse)`. The underlying Play
`BillingResult` is available via `response.getResponseCode()` and
`response.getDebugMessage()`.

Common scenarios:

| Trigger | `ErrorType` | Fix |
|---------|-------------|-----|
| Product id typo / not activated | `PRODUCT_NOT_EXIST` or `PRODUCT_ID_QUERY_FAILED` | Check Play Console. |
| License tester account missing | `BILLING_UNAVAILABLE` | Add the Google account to **License testing** + install via Play testing link. |
| Sideloaded APK | `BILLING_UNAVAILABLE` | Install the build from the Internal testing track URL. |
| Offer token rejected | `DEVELOPER_ERROR` | Base plan / offer id mismatch — verify the ids in Play Console against the builder. |
| Launch dialog rejected synchronously | `BILLING_ERROR` (with underlying `ResponseCode`) | Happens for stale offer tokens or destroyed Activities — retry the purchase. |
| Retry loop exhausted for PENDING | `PENDING_PURCHASE_RETRY_ERROR` | The token is preserved; the next `connect()` reconciles. |
| User pressed back | `USER_CANCELED` → routed to `onUserCancelled()` | Show the paywall again if appropriate. |

---

## Testing

### Local JVM tests

The library ships with 43 unit tests across 6 suites:

- `OfferSelectorTest` (11) — trial preference, base-plan fallback, offerId override, eligibility.
- `IdempotencyStoreTest` (6) — persistence across instances, forget, clearAll, idempotent marks.
- `SubscriptionSpecTest` (6) — builder defaults, required fields, equality.
- `BillingConfigTest` (9) — six-SKU catalog, legacy setters, consumable registration, mixed catalogs.
- `ChangeModeTest` (6) — every `ReplacementMode` mapping + distinctness.
- `Iso8601DurationTest` (5) — day / week / month / year parsing + malformed input.

```bash
./gradlew :library:testReleaseUnitTest
```

### Static test product ids

Play ships four hard-coded product ids that always resolve without Play Console setup:

| Id | Result |
|----|--------|
| `android.test.purchased` | `PURCHASED` |
| `android.test.canceled` | `USER_CANCELED` |
| `android.test.item_unavailable` | `ITEM_UNAVAILABLE` |
| `android.test.refunded` | Deprecated — avoid |

Useful for smoke tests; do not ship them.

### License testers + Play Billing Lab

Real purchase paths (subscriptions, trials, PENDING, grace/hold/paused if you have a
backend) require license-tester accounts and the Play Billing Lab. See
[`docs/TESTING.md`](docs/TESTING.md) for the full QA checklist.

---

## Security notes

### Client-side signature check (weak)

Pass `base64LicenseKey(...)` to the builder to enable on-device signature verification.
This rejects forged purchases with a mismatched signature but leaves the key embedded in
your APK — any attacker with `apktool` can extract it.

### Server-side verification (recommended)

The strong path is to POST each `purchaseToken` to your backend, call the
[Play Developer API](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get)
server-side, compare `obfuscatedAccountId` against the hashed user id you expect, and only
then grant entitlement. Ideally acknowledge from the server too (keeps the 72-hour window
safe if the user uninstalls mid-flow).

See [`docs/SECURITY.md`](docs/SECURITY.md) for a reference flow.

### Play Integrity

For high-value purchases, gate your `/verify` endpoint behind a Play Integrity token to
reject rooted / tampered clients. The library does not integrate Play Integrity directly.

---

## Troubleshooting

**No products returned.** APK uploaded to Play? Signed with the release keystore? Product
`Activate`-d in Play Console? `com.android.vending.BILLING` permission declared?

**`BILLING_UNAVAILABLE` on every attempt.** Sideloaded APK, or the test device account
is not a license tester. Install from the Play testing track URL.

**Purchases never fire `onLifetimePurchased`.** Activity was destroyed before the callback
returned. Confirm the wrapper is application-scoped and `setListener(null)` is called in
`onDestroy`.

**JitPack returns 404 for `X.Y.Z` but serves `vX.Y.Z`.** Use the v-prefixed coordinate:
`implementation 'com.github.code-execute-rishi:PlayBillingWrapper:v0.2.0'`.

**PENDING purchase never resolves.** That's the user taking a long time to pay cash / clear
the bank transfer. The wrapper retries indefinitely; the token is preserved on every
reconnect. Surface `onPending` state to the user and do not grant entitlement yet.

**Listener fires twice for the same purchase.** You're creating multiple wrapper instances.
Keep it application-scoped — one instance per process.

---

## Versioning + auto-release

- Commits to `main` trigger CI on every push (tests + lint + AAR + publishToMavenLocal).
- On CI success the `Auto Release` workflow inspects commits since the last tag and picks
  a semver bump:
  - `BREAKING CHANGE` in body or `!:` in subject → major
  - `feat:` → minor
  - anything else → patch
- The workflow bumps `library/build.gradle`, the install snippets in README / INTEGRATION /
  MIGRATION, commits as `chore(release): vX.Y.Z [skip ci]`, pushes the tag, creates a
  GitHub Release, and pings JitPack so the AAR builds within ~30 seconds.

Force a specific bump via **Actions → Auto Release → Run workflow**.

---

## Roadmap

- **0.2** — Subscription upgrade / downgrade with named `ChangeMode` modes wrapping
  `ReplacementMode` and auto-revoke on `linkedPurchaseToken`.
- **0.3** — Ktor / OkHttp `ServerVerifier` reference implementation and matching Cloud
  Function snippet that acknowledges server-side.
- **0.4** — Optional `playbillingwrapper-compose` module with a `rememberBilling()` composable
  and a `PaywallScaffold`.
- **0.5** — Kotlin coroutines / Flow extensions as a companion artifact.

Open issues and PRs welcome at https://github.com/code-execute-rishi/PlayBillingWrapper/issues.

---

## License + credits

Apache-2.0. See [`LICENSE`](LICENSE) for the full text.

Derived from [`moisoni97/google-inapp-billing`](https://github.com/moisoni97/google-inapp-billing)
(Apache-2.0). That project's pending-purchase retry state machine,
acknowledgement-with-backoff logic, and Play Store installation heuristic were used as
correctness references during the rewrite. See [`NOTICE`](NOTICE) for attribution details.

## Additional documentation

- [`docs/INTEGRATION.md`](docs/INTEGRATION.md) — step-by-step Play Console setup and first-run walkthrough.
- [`docs/API.md`](docs/API.md) — condensed API reference.
- [`docs/SECURITY.md`](docs/SECURITY.md) — server-side verification guide with reference code.
- [`docs/TESTING.md`](docs/TESTING.md) — static test ids, license testers, Play Billing Lab.
- [`docs/MIGRATION.md`](docs/MIGRATION.md) — upgrading from `moisoni97/google-inapp-billing`.
- [`CHANGELOG.md`](CHANGELOG.md) — release history.
