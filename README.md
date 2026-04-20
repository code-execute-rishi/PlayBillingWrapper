# PlayBillingWrapper

[![CI](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/ci.yml/badge.svg)](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/ci.yml)
[![Release](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/release.yml/badge.svg)](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/release.yml)
[![JitPack](https://jitpack.io/v/code-execute-rishi/PlayBillingWrapper.svg)](https://jitpack.io/#code-execute-rishi/PlayBillingWrapper)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg)](https://android-arsenal.com/api?level=23)
[![Billing](https://img.shields.io/badge/Play%20Billing-8.3.0-blue.svg)](https://developer.android.com/google/play/billing/release-notes)

A tiny, opinionated Java wrapper around the Google Play Billing Library (v8.3.0) for the three
product shapes every app actually sells:

1. **Lifetime** — one-time non-consumable unlock.
2. **Monthly subscription** — auto-renewing, no trial.
3. **Yearly subscription with a free trial** — trial auto-picked when the user is eligible.

Three method calls, one builder, zero backend. No Kotlin required.

```java
billing.buyLifetime(activity);
billing.subscribeMonthly(activity);
billing.subscribeYearlyWithTrial(activity);
```

---

## Why

Raw `BillingClient` is ~1,500 lines of boilerplate per app. RevenueCat and Adapty solve it but
lock you to a SaaS backend and cut ~1% of every dollar. Existing community wrappers
(`moisoni97/google-inapp-billing`, `MFlisar/KotBilling`, etc.) skip the pieces that actually
leak revenue — the three-day acknowledgment rule, offer-token selection for subscription trials,
`obfuscatedAccountId` for fraud binding, idempotent purchase delivery across app restarts.

PlayBillingWrapper picks the middle path: thin, permissively licensed (Apache-2.0),
opinionated for the three shapes above, and safe by default.

## What's included

- `BillingClient` lifecycle + retry with exponential backoff.
- `queryProductDetailsAsync` for both INAPP and SUBS, reconciled automatically on connect.
- **Auto-acknowledgment** with a 3-retry exponential backoff (prevents 72h auto-refund).
- **Auto-consume** for consumables (if you opt in — not needed for the three shapes above).
- **Pending purchase retries** with an effectively unbounded timeout (real PENDING can take days).
- **Idempotent delivery** of `onLifetimePurchased` / `onSubscriptionActivated` callbacks across
  app restarts, using a persistent `SharedPreferences` ledger keyed on `purchaseToken`.
- **`obfuscatedAccountId` + `obfuscatedProfileId`** bound to every purchase for fraud binding,
  trial-per-account enforcement, and server-side token ↔ user mapping.
- **`OfferSelector`** picks the right subscription offer token by base plan id + optional offer id,
  auto-preferring a free-trial offer when the user is still eligible.
- **`SubscriptionState` enum** — `ACTIVE`, `IN_TRIAL`, `CANCELED_ACTIVE`, `PENDING`,
  `EXPIRED`. Purely client-side, computed from the `Purchase` Play returns. No backend
  required.
- **On-device signature verification** via Play's public key (optional). Disabled by default —
  the README explains how to move verification to your server.
- **Deep-link to Play's manage-subscription page** in one call.

## What's NOT included (out of scope for v0.1)

- Consumable multi-quantity handling (not in the three target shapes).
- Subscription upgrade/downgrade (monthly ↔ yearly). Planned for 0.2.
- Installment plans, prepaid plans, one-time product offers.
- Kotlin coroutines / Flow extensions (the library is Java and Kotlin-compatible; add your own
  `suspendCoroutine` if you want).
- Compose UI.
- Real-Time Developer Notifications, server-side state sync, backend infrastructure. If your
  business depends on distinguishing `GRACE_PERIOD` / `ON_HOLD` / `PAUSED`, you need a
  backend — this library is intentionally local-only.

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

### Step 2 — Add the dependency

```gradle
dependencies {
    implementation 'com.github.code-execute-rishi:PlayBillingWrapper:0.1.2'
}
```

### Step 3 — Declare the billing permission

```xml
<uses-permission android:name="com.android.vending.BILLING" />
```

### Step 4 — Enable core-library desugaring (if your minSdk < 26)

Not needed for PlayBillingWrapper itself (we target the APIs that Android Gradle Plugin
desugars automatically), but you may want it for your own code.

---

## Quick start

### 1. Configure once, in your `Application`

```java
public class MyApp extends Application {
    private PlayBillingWrapper billing;
    public static PlayBillingWrapper billing(Context c) {
        return ((MyApp) c.getApplicationContext()).billing;
    }

    @Override public void onCreate() {
        super.onCreate();
        BillingConfig cfg = BillingConfig.builder()
            .lifetimeProductId("com.foo.lifetime")
            .monthlySubProductId("com.foo.premium_monthly")
            .monthlyBasePlanId("monthly")
            .yearlySubProductId("com.foo.premium_yearly")
            .yearlyBasePlanId("yearly")
            .yearlyTrialOfferId("freetrial")             // optional — auto-detect if null
            .userId(sha256(currentUserId))               // required
            .enableLogging(BuildConfig.DEBUG)
            .build();

        billing = new PlayBillingWrapper(this, cfg, /*listener=*/null);
        billing.connect();
    }
}
```

### 2. Hook a listener from an Activity

```java
public class PaywallActivity extends AppCompatActivity implements WrapperListener {

    private PlayBillingWrapper billing;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        billing = MyApp.billing(this);
        billing.setListener(this);

        findViewById(R.id.btn_lifetime).setOnClickListener(v -> billing.buyLifetime(this));
        findViewById(R.id.btn_monthly).setOnClickListener(v -> billing.subscribeMonthly(this));
        findViewById(R.id.btn_yearly).setOnClickListener(v ->
            billing.subscribeYearlyWithTrial(this));
    }

    @Override public void onReady() { /* products fetched */ }

    @Override public void onLifetimePurchased(@NonNull PurchaseInfo purchase) {
        unlockFeatures();
    }

    @Override public void onSubscriptionActivated(@NonNull String productId,
                                                  @NonNull SubscriptionState state,
                                                  @NonNull PurchaseInfo purchase) {
        if (state == SubscriptionState.IN_TRIAL) showTrialBanner();
        unlockFeatures();
    }

    @Override public void onPending(@NonNull PurchaseInfo purchase) {
        showToast("Payment is processing. You'll be notified when it completes.");
    }

    @Override public void onUserCancelled() { /* user dismissed Play dialog */ }

    @Override public void onError(@NonNull BillingResponse response) {
        showToast("Purchase failed: " + response.getDebugMessage());
    }
}
```

### 3. Query state anywhere

```java
billing.hasLifetime();                  // boolean
billing.monthlyState();                 // SubscriptionState
billing.yearlyState();                  // SubscriptionState
billing.isTrialEligibleForYearly();     // boolean — Play-determined eligibility
billing.isSubscribed();                 // monthly or yearly currently entitles
billing.isPremium();                    // lifetime OR subscribed
```

---

## Documentation

- [**INTEGRATION.md**](docs/INTEGRATION.md) — step-by-step setup, Play Console checklist,
  license tester setup, common error codes.
- [**API.md**](docs/API.md) — full public API reference.
- [**SECURITY.md**](docs/SECURITY.md) — optional server-side purchase verification, with a
  minimal reference implementation.
- [**TESTING.md**](docs/TESTING.md) — license testers, static test products, Play Billing Lab
  accelerated renewals.
- [**MIGRATION.md**](docs/MIGRATION.md) — upgrading from `moisoni97/google-inapp-billing`.

---

## Required Play Console setup

1. **Create products** in Play Console → Monetize → Products:
   - One **Managed product** (non-consumable) for lifetime.
   - Two **Subscriptions** — one for monthly, one for yearly.
2. For each subscription, add a **Base Plan** (e.g. `monthly`, `yearly`) and configure pricing.
3. For the yearly subscription, add a **Free Trial offer**:
   - In Play Console → Subscriptions → your-yearly-sub → Base plan `yearly` → Add offer →
     Offer type: Developer-determined → Eligibility: New customers → Phase 1: Free, 7 days.
   - The offer gets an id (e.g. `freetrial`) — pass this as `yearlyTrialOfferId(...)` to the
     builder, or leave it null and the library auto-picks the first free-trial offer it finds.
4. **Upload a signed release APK** (internal testing track is fine) — Play Billing will not
   return products for unpublished apps.
5. **Add license testers** in Play Console → Settings → License testing → Tester Google
   accounts. Test purchases are free and can be cancelled from Play.

---

## Billing-correctness checklist (what this lib handles for you)

| Pitfall | What the lib does |
|---------|-------------------|
| 72h auto-refund if unacknowledged | `autoAcknowledge(true)` by default. |
| Double-granting entitlement on restart | `IdempotencyStore` keyed on `purchaseToken` (persistent). |
| PENDING purchases lost after short timeout | Retries effectively forever; reconciles on every `connect()`. |
| Picking wrong subscription offer (brittle index) | `OfferSelector` picks by `basePlanId` + optional `offerId`, auto-prefers trial if eligible. |
| Missing fraud binding | `obfuscatedAccountId` / `obfuscatedProfileId` set on every `BillingFlowParams`. |
| `isSubscriptionActive` returning naive booleans | `SubscriptionState` enum with `ACTIVE`, `IN_TRIAL`, `CANCELED_ACTIVE`, `PENDING`, `EXPIRED` — computed from local `Purchase` data. |
| Fresh-install restore returns empty | `restorePurchases()` re-triggers a full reconnect and query cycle. |
| "Already owned" error after reinstall | Handled transparently — purchases surface via `onPurchasedProductsFetched`. |

---

## Roadmap

- **0.2** — Subscription upgrade/downgrade between monthly ↔ yearly (`ReplacementMode` wrapper).
- **0.3** — Ktor / OkHttp `ServerVerifier` reference implementation + matching Cloud Function.
- **0.4** — Optional Compose Paywall module (`playbillingwrapper-compose`).
- **0.5** — Kotlin coroutines / Flow extensions.

---

## Credits

Based on and inspired by [moisoni97/google-inapp-billing](https://github.com/moisoni97/google-inapp-billing)
(Apache-2.0) — its pending-retry state machine, ack-with-backoff logic, and Play Store installation
check were used as correctness references during the rewrite.

## License

Apache-2.0 (inherited from the reference base). See `LICENSE`.
