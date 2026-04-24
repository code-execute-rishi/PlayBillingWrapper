# End-to-End Guide: 4 Real-World Paywall Shapes

Play Console setup + Android code + testing checklist for the four most common paywall
shapes. Every snippet uses the generic API; the sugar methods (`buyLifetime`,
`subscribeMonthly`, `subscribeYearlyWithTrial`) still work if you prefer them.

Jump to:

1. [Yearly subscription with a 3-day free trial](#1-yearly-subscription-with-a-3-day-free-trial)
2. [Monthly subscription (no trial)](#2-monthly-subscription-no-trial)
3. [Lifetime one-time product](#3-lifetime-one-time-product)
4. [Subscription with intro pricing (cheap first period, then normal price)](#4-subscription-with-intro-pricing-cheap-first-period-then-normal-price)
5. [Wiring all four in a single paywall](#5-wiring-all-four-in-a-single-paywall)
6. [Shared Play Console prep](#shared-play-console-prep)
7. [Shared testing setup](#shared-testing-setup)

> **Install:**
> ```gradle
> implementation 'com.github.code-execute-rishi:PlayBillingWrapper:v0.2.1'
> ```
> Plus `<uses-permission android:name="com.android.vending.BILLING" />` in the manifest.

---

## 1. Yearly subscription with a 3-day free trial

Classic premium upsell: "Start your 3-day free trial, then $39.99/year."

### 1.1 Play Console setup

1. Play Console → Monetize → Products → **Subscriptions** → Create subscription.
2. Product id: `com.yourapp.premium`.
3. Add a **Base plan**:
   - Base plan id: `yearly`
   - Billing period: `P1Y`
   - Auto-renewing.
   - Price: `$39.99` (Play applies local currency per country).
4. Add an **Offer** on the yearly base plan:
   - Offer id: `freetrial_3d`
   - Offer type: **Developer-determined**
   - Eligibility: **New customer acquisition → New customers**
   - Phase 1: **Free** for `P3D`
5. Activate the subscription, the base plan, and the offer (three separate toggles).

### 1.2 Register in `BillingConfig`

```java
BillingConfig cfg = BillingConfig.builder()
    .addSubscription(SubscriptionSpec.builder()
        .productId("com.yourapp.premium")
        .basePlanId("yearly")
        .preferTrial(true)                  // auto-pick free-trial offer if eligible
        .preferredOfferId("freetrial_3d")   // exact id beats auto-pick
        .tag("yearly_trial")
        .build())
    .userId(sha256(currentUserId()))
    .enableLogging(BuildConfig.DEBUG)
    .build();

PlayBillingWrapper billing = new PlayBillingWrapper(application, cfg, listener);
billing.connect();
```

### 1.3 Paywall UI

```java
// Gate CTA label on Play's trial eligibility.
boolean eligible = billing.isTrialEligible("com.yourapp.premium", "yearly");
ctaButton.setText(eligible
        ? "Start 3-day free trial, then " + billing.getFormattedPrice("com.yourapp.premium", "yearly") + "/year"
        : "Subscribe " + billing.getFormattedPrice("com.yourapp.premium", "yearly") + "/year");

ctaButton.setOnClickListener(v ->
        billing.subscribe(activity, "com.yourapp.premium", "yearly"));
```

### 1.4 Listener + trial-end reminder

```java
@Override
public void onSubscriptionActivated(@NonNull String productId,
                                    @NonNull SubscriptionState state,
                                    @NonNull PurchaseInfo purchase) {
    unlockPremium();

    // Schedule a push 12 hours before the trial ends.
    long trialEnd = billing.getTrialEndMillis(purchase);
    if (trialEnd > 0) {
        reminderScheduler.at(trialEnd - TimeUnit.HOURS.toMillis(12),
                "Your trial ends soon. Your card will be charged 39.99/year.");
    }
}
```

### 1.5 Testing

| # | Step | Expected |
|---|------|----------|
| 1 | Install via Internal testing track with a license-tester account that has never used this trial. | `isTrialEligible("com.yourapp.premium", "yearly")` returns `true`. |
| 2 | Tap "Start 3-day free trial" → complete Play dialog. | `onSubscriptionActivated` fires once. `billing.yearlyState()` returns `ACTIVE`. `getTrialEndMillis(purchase)` returns a millis value ~3 days in the future (or 30 minutes with license-tester acceleration). |
| 3 | Cancel the subscription from Play before the trial ends. | `onSubscriptionCancelled` fires. `yearlyState()` returns `CANCELED_ACTIVE`. User is still entitled. |
| 4 | Let the trial expire without cancelling. | First paid period starts; `yearlyState()` stays `ACTIVE`; a new `purchaseToken` eventually replaces the old one on reconnect. |
| 5 | Uninstall → reinstall. | `restorePurchases(30_000L)` restores the subscription; `onSubscriptionActivated` does NOT re-fire for the same `purchaseToken` (idempotency ledger catches it). |
| 6 | Buy the same SKU again with a license-tester account that already used the trial. | `isTrialEligible` returns `false`; `subscribe(...)` charges the full yearly price (library auto-picks the base plan offer). |

**License-tester acceleration:** Play compresses the 1-year billing period to 30 minutes
and the 3-day trial to a matter of minutes. Budget 1–2 hours of wall-clock testing to
exercise a full renewal cycle. See [docs/TESTING.md](TESTING.md) for the full table.

---

## 2. Monthly subscription (no trial)

Lower-friction upsell: "$4.99/month, cancel anytime."

### 2.1 Play Console setup

1. Play Console → Monetize → Products → **Subscriptions** → Create subscription.
2. Product id: `com.yourapp.premium` (same product id as the yearly plan above is
   fine — Play supports multiple base plans per subscription product).
3. Add a **Base plan**:
   - Base plan id: `monthly`
   - Billing period: `P1M`
   - Auto-renewing.
   - Price: `$4.99`.
4. No offer — the base plan is sold directly.
5. Activate the base plan.

### 2.2 Register + purchase

```java
BillingConfig cfg = BillingConfig.builder()
    .addSubscription(SubscriptionSpec.of("com.yourapp.premium", "monthly"))
    // (keep the yearly+trial spec from above if you ship both)
    .userId(sha256(currentUserId()))
    .build();

ctaMonthly.setText("Subscribe " + billing.getFormattedPrice("com.yourapp.premium", "monthly") + "/month");
ctaMonthly.setOnClickListener(v ->
        billing.subscribe(activity, "com.yourapp.premium", "monthly"));
```

### 2.3 Listener

Same `onSubscriptionActivated`. `state` is `ACTIVE`; `getTrialEndMillis(purchase)` returns
`-1` because this purchase has no trial phase.

### 2.4 Testing

| # | Step | Expected |
|---|------|----------|
| 1 | Buy monthly with a license tester. | `onSubscriptionActivated(productId, ACTIVE, purchase)` fires once. `monthlyState()` returns `ACTIVE`. `hasLifetime()` stays `false`. |
| 2 | Cancel from Play. | `onSubscriptionCancelled` fires once. `monthlyState()` = `CANCELED_ACTIVE`. `isPremium()` stays `true` until the paid period ends. |
| 3 | Wait for the accelerated paid period (~5 min for license testers) to elapse. | `restorePurchases()` → `monthlyState()` flips to `EXPIRED`. `isPremium()` = `false`. |
| 4 | Attempt to buy the same SKU while already subscribed. | Play returns `ITEM_ALREADY_OWNED`; surface a "Manage subscription" CTA that calls `billing.openManageSubscription(activity, productId)`. |

---

## 3. Lifetime one-time product

"Pay once, own forever."

### 3.1 Play Console setup

1. Play Console → Monetize → Products → **In-app products** → Create product.
2. Product id: `com.yourapp.lifetime`.
3. Type: **Managed product** (non-consumable).
4. Price: `$49.99`.
5. Activate.

### 3.2 Register + purchase

```java
BillingConfig cfg = BillingConfig.builder()
    .addLifetimeProductId("com.yourapp.lifetime")
    .userId(sha256(currentUserId()))
    .build();

ctaLifetime.setText("Buy lifetime — " + billing.getFormattedPrice("com.yourapp.lifetime"));
ctaLifetime.setOnClickListener(v ->
        billing.purchaseProduct(activity, "com.yourapp.lifetime"));
```

### 3.3 Listener

```java
@Override
public void onLifetimePurchased(@NonNull PurchaseInfo purchase) {
    // Auto-acknowledged by default within Play's 72-hour window.
    unlockPremiumPermanent();
    analytics.track("purchase_lifetime", purchase.getPurchaseToken());
}

@Override
public void onPending(@NonNull PurchaseInfo purchase) {
    // Cash / bank-transfer PENDING. Do NOT grant entitlement.
    showToast("Payment processing. You'll be unlocked as soon as it clears.");
}
```

### 3.4 Testing

| # | Step | Expected |
|---|------|----------|
| 1 | Buy with a license tester. | `onLifetimePurchased(purchase)` fires once. `hasLifetime()` returns `true`. `isPremium()` returns `true`. |
| 2 | Kill and relaunch the app. | `onLifetimePurchased` does NOT re-fire (idempotency ledger). `hasLifetime()` still `true` after `connect()` reconciles. |
| 3 | Uninstall and reinstall the app. | After `connect()`, `hasLifetime()` returns `true` without any user action. `onLifetimePurchased` does NOT re-fire (ledger persists; see note below). |
| 4 | Refund the purchase in Play Console. | After the next `restorePurchases(30_000L)`, `hasLifetime()` returns `false`. `getIdempotencyStore().forget(token)` is optional but recommended so a future re-purchase with a recycled token (rare) is handled fresh. |
| 5 | Test PENDING path with the static id `android.test.purchased` OR a license tester using a slow payment method. | `onPending(purchase)` fires; `hasLifetime()` stays `false` until the PENDING clears. |

> **Idempotency note:** the dedupe ledger lives in `SharedPreferences` and is cleared on
> app uninstall + data wipe. After a full reinstall the first purchase query still
> surfaces the purchase via `onLifetimePurchased`. That's expected: a fresh install with
> no local state should be treated as a fresh grant. If you want cross-install persistence
> keep entitlement state on your server and ignore local callbacks for the grant decision.

---

## 4. Subscription with intro pricing (cheap first period, then normal price)

"First month $0.99, then $4.99/month." Play calls this a **developer-determined offer with
a finite-recurring pricing phase**. Distinct from a free trial — the user pays something,
just less than the regular price.

### 4.1 Play Console setup

1. Play Console → Monetize → Products → **Subscriptions** → Create subscription.
2. Product id: `com.yourapp.premium`.
3. Add a **Base plan**:
   - Base plan id: `monthly`
   - Billing period: `P1M`, auto-renewing.
   - Price: `$4.99` (the regular price).
4. Add an **Offer** on the monthly base plan:
   - Offer id: `intro_99c_1mo`
   - Offer type: **Developer-determined**
   - Eligibility: **New customer acquisition → New customers**
   - Phase 1:
     - Type: **Finite recurring**
     - Price: `$0.99`
     - Billing period: `P1M`
     - Number of periods: `1`
   - (No phase 2 — Play automatically transitions to the base plan price after phase 1.)
5. Activate.

> If you want "first 3 months $0.99 then $4.99" change the phase 1 number of periods to
> `3`. If you want "free 3 days, then $0.99 first month, then $4.99" add a free Phase 1
> with `P3D` before the finite-recurring phase.

### 4.2 Register + purchase

```java
BillingConfig cfg = BillingConfig.builder()
    // Sugar: equivalent to builder().preferredOfferId("intro_99c_1mo").
    .addSubscription(SubscriptionSpec.withIntro(
            "com.yourapp.premium", "monthly", "intro_99c_1mo"))
    .userId(sha256(currentUserId()))
    .build();

// One-liner CTA labels using the typed intro helpers.
String introPrice     = billing.getIntroPrice("com.yourapp.premium", "monthly");   // "$0.99"
String recurringPrice = billing.getRecurringPrice("com.yourapp.premium", "monthly"); // "$4.99"
String introPeriod    = billing.getIntroPeriodIso("com.yourapp.premium", "monthly"); // "P1M"

if (introPrice != null) {
    ctaIntro.setText(introPrice + " for 1 month, then " + recurringPrice + " / month");
} else {
    // Repeat user -- Play omits the intro offer, library falls back to the base plan.
    ctaIntro.setText(recurringPrice + " / month");
}

ctaIntro.setOnClickListener(v ->
        billing.subscribe(activity, "com.yourapp.premium", "monthly"));
```

If you need every phase (intro + recurring) structurally, walk the typed phases:

```java
List<PricingPhases> phases = billing.getOfferPhases("com.yourapp.premium", "monthly");
for (PricingPhases p : phases) {
    if (p.isIntro())      Log.d("paywall", "intro: " + p.getFormattedPrice() + " × " + p.getBillingCycleCount());
    if (p.isRecurring())  Log.d("paywall", "renews: " + p.getFormattedPrice() + " / " + p.getPeriodIso());
}
```

### 4.3 Detecting which phase the user is currently in

Play does not expose "current pricing phase" in the client `Purchase`. Options:

1. **Time-based estimate** (client-only, good for UI) -- one-liner via the library:
   ```java
   long introEnd = billing.getIntroEndMillis(purchase, "monthly");
   boolean inIntroPhase = introEnd > 0 && System.currentTimeMillis() < introEnd;
   ```
   Uses `purchaseTime + introPeriod * billingCycleCount`. Returns `-1` if the purchase has
   no intro offer on the given base plan (e.g. repeat user who paid the full base price).
2. **Authoritative** (server-side): query
   [`purchases.subscriptionsv2.get`](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get)
   — the response contains the `currentPeriod` object with the phase id in effect.

### 4.4 Price step-up via Play's price change flow

If you later want to raise the regular price to `$5.99/month` without creating a new SKU,
use Play Console's **Price change** feature (distinct from intro pricing — it applies to
existing subscribers):

1. Play Console → Subscriptions → Price changes → New price change → choose base plan.
2. Set the new price and the rollout schedule.
3. For opt-in markets (US, EU, etc.), Play prompts each subscriber for consent at their
   next renewal. For opt-out markets, Play changes the price automatically and notifies
   the user.
4. Track acceptance via Real-Time Developer Notifications
   (`SUBSCRIPTION_PRICE_CHANGE_CONFIRMED` = notification type 19, deprecated in v8; use
   `SUBSCRIPTION_PRICE_CHANGE_CONFIRMED` via `subscriptionsv2.get` instead).

The library is intentionally client-only, so price-change state requires a backend.
`getFormattedPrice(productId, basePlanId)` always returns the current Play-advertised
price for new purchases, so your paywall reflects the new price immediately after Play
rolls it out.

### 4.5 Testing intro pricing

| # | Step | Expected |
|---|------|----------|
| 1 | Register the `intro_99c_1mo` offer in Play Console. Wait 10 minutes for propagation. | `getPricingPhases("com.yourapp.premium", "monthly")` returns 2 phases: `[FINITE_RECURRING $0.99 × 1 month, INFINITE_RECURRING $4.99]`. |
| 2 | License tester who never subscribed before → tap CTA → Play dialog shows `$0.99 for 1 month, then $4.99`. | Play dialog wording matches your `getPricingPhases()` output. |
| 3 | Complete purchase. | `onSubscriptionActivated(productId, ACTIVE, purchase)` fires. Play reports `isAutoRenewing() == true`. Your server-side verification should see `currentPeriod.phaseId == "intro_99c_1mo"`. |
| 4 | Wait for the accelerated 1-month intro phase to roll over to the base plan. | No client callback fires (Play does not emit an event for phase transitions). `getFormattedPrice(productId, basePlanId)` still returns the same string because it's the regular-phase price. |
| 5 | License tester who already consumed the intro → tap CTA. | `Play` dialog charges the full `$4.99`; the library auto-falls back to the base plan offer because the intro offer is omitted from `ProductDetails` for ineligible users. |
| 6 | Roll out a price change to `$5.99`. After Play propagation, a fresh paywall load shows `$0.99 for 1 month, then $5.99 / month`. | `getPricingPhases()` reflects the new regular phase. Existing subscribers see the old price until they consent / auto-accept. |

---

## 5. Wiring all four in a single paywall

A realistic app ships yearly+trial AND monthly-intro AND lifetime side by side, with
`PaywallCoordinator` picking which CTA to emphasise.

### 5.1 Full `BillingConfig`

```java
BillingConfig cfg = BillingConfig.builder()
    // Lifetime + optional discount SKU routed after paywall dismissal.
    .addLifetimeProductId("com.yourapp.lifetime")
    .addLifetimeProductId("com.yourapp.lifetime_launch")

    // Yearly with 3-day free trial.
    .addSubscription(SubscriptionSpec.builder()
        .productId("com.yourapp.premium")
        .basePlanId("yearly")
        .preferTrial(true)
        .preferredOfferId("freetrial_3d")
        .tag("yearly_trial")
        .build())

    // Monthly with 1-month $0.99 intro.
    .addSubscription(SubscriptionSpec.builder()
        .productId("com.yourapp.premium")
        .basePlanId("monthly")
        .preferredOfferId("intro_99c_1mo")
        .tag("monthly_intro")
        .build())

    // Monthly base plan (shown to ineligible users or as a fallback).
    .addSubscription(SubscriptionSpec.of("com.yourapp.premium", "monthly"))

    .userId(sha256(currentUserId()))
    .autoAcknowledge(true)
    .enableLogging(BuildConfig.DEBUG)
    .build();
```

### 5.2 Paywall coordinator

```java
class PaywallCoordinator {
    void render(int dismissalCount) {
        boolean trialEligible = billing.isTrialEligible("com.yourapp.premium", "yearly");

        yearlyCta.setText(trialEligible
                ? "Start 3-day free trial"
                : "Yearly " + billing.getFormattedPrice("com.yourapp.premium", "yearly"));
        yearlyCta.setOnClickListener(v ->
                billing.subscribe(activity, "com.yourapp.premium", "yearly"));

        monthlyCta.setText(renderIntroLabel(
                billing.getPricingPhases("com.yourapp.premium", "monthly")));
        monthlyCta.setOnClickListener(v ->
                billing.subscribe(activity, "com.yourapp.premium", "monthly"));

        // After the first dismissal, switch the lifetime CTA to the discount SKU.
        String lifetimeSku = dismissalCount >= 1
                ? "com.yourapp.lifetime_launch"
                : "com.yourapp.lifetime";
        lifetimeCta.setText("Lifetime — " + billing.getFormattedPrice(lifetimeSku));
        lifetimeCta.setOnClickListener(v ->
                billing.purchaseProduct(activity, lifetimeSku));
    }
}
```

### 5.3 Cancellation → re-engagement

```java
@Override
public void onSubscriptionCancelled(@NonNull String productId,
                                    @NonNull PurchaseInfo purchase) {
    // Fires once, on the actual auto-renew true→false transition. Not on prepaid plans,
    // not on first observation of an already-cancelled subscription.
    reEngageScheduler.schedule(productId, billing.getTrialEndMillis(purchase));
}
```

### 5.4 Refresh on resume

```java
@Override
protected void onResume() {
    super.onResume();
    // Catch web-redeemed promo codes and cross-device sync, ≤1 refresh / 30 s.
    billing.restorePurchases(30_000L);
}
```

---

## Shared Play Console prep

Every shape above assumes:

- A Play Console account with Merchant profile enabled.
- At least one **signed release APK/AAB** uploaded to the **Internal testing** track. Play
  returns empty product lists for apps that have never been uploaded.
- The testing track opt-in URL installed on the test device's Google account. Sideloaded
  APKs never receive billing responses.
- Product ids + base plan ids + offer ids spelled identically in Play Console and in the
  library's `BillingConfig`. Case-sensitive.

---

## Shared testing setup

1. **Add license testers.** Play Console → Settings → License testing → add each tester
   Google account. Testers buy at $0; cancel freely.
2. **Accelerated cycles.** Play compresses subscription billing periods for testers:
   - Weekly → 5 minutes
   - Monthly → 5 minutes
   - Quarterly → 10 minutes
   - Semi-annual → 15 minutes
   - Yearly → 30 minutes
   - Renewals cap at 6 before the subscription auto-expires.
3. **Play Billing Lab.** Play Console → Subscription → Testing tab exposes buttons to
   force Cancel and Refund transitions (Grace Period / On Hold / Paused require a
   backend — the wrapper is local-only).
4. **Static test ids** for smoke checks without Play Console setup:
   - `android.test.purchased` — always returns `PURCHASED`.
   - `android.test.canceled` — always returns `USER_CANCELED`.
   - `android.test.item_unavailable` — always returns `ITEM_UNAVAILABLE`.
5. **Run the QA checklist** in [`docs/TESTING.md`](TESTING.md) before flipping the
   production track switch.

### Cross-shape QA matrix

| Scenario | Lifetime | Monthly | Yearly+trial | Intro-priced monthly |
|----------|----------|---------|--------------|----------------------|
| Fresh buy | `onLifetimePurchased` fires once | `onSubscriptionActivated(ACTIVE)` | `onSubscriptionActivated(ACTIVE)` + `getTrialEndMillis > 0` | `onSubscriptionActivated(ACTIVE)` + two phases visible |
| Kill + relaunch | No re-delivery | No re-delivery | No re-delivery | No re-delivery |
| Uninstall + reinstall | `hasLifetime() == true` after `connect()` | `monthlyState() == ACTIVE` | `yearlyState() == ACTIVE` | `monthlyState() == ACTIVE` |
| Cancel from Play | n/a | `CANCELED_ACTIVE`, then `EXPIRED` at renewal | `CANCELED_ACTIVE` during trial, `EXPIRED` at trial end | `CANCELED_ACTIVE`, then `EXPIRED` |
| `onSubscriptionCancelled` | n/a | fires once on cancel | fires once on cancel (during or after trial) | fires once on cancel |
| Refund in Play Console | `hasLifetime() → false` after restore | `EXPIRED` after restore | `EXPIRED` after restore | `EXPIRED` after restore |
| Trial ineligible repeat | n/a | n/a | Charges full yearly; no trial | Charges base price; no intro |
| Airplane mode → buy | `onError(NETWORK_ERROR)` | `onError(NETWORK_ERROR)` | `onError(NETWORK_ERROR)` | `onError(NETWORK_ERROR)` |
| Slow-payment PENDING | `onPending` fires; `hasLifetime()` stays false | `onPending` + `monthlyState() == PENDING` | `onPending` + `yearlyState() == PENDING` | `onPending` + `monthlyState() == PENDING` |

---

## What the library does NOT do here

See the **What it does NOT do (gaps + caveats)** section of the
[`README`](../README.md#what-it-does-not-do-gaps--caveats) for the full list. Highlights
relevant to this guide:

- **Authoritative current-phase detection** for intro pricing requires
  `subscriptionsv2.get` server-side — client `Purchase` does not expose which phase is
  active.
- **Price change consent** is Play-managed; the wrapper surfaces the new price via
  `getFormattedPrice(...)` once Play rolls it out but does not emit a callback for
  per-user consent events.
- **Grace period / on hold / paused** states require Real-Time Developer Notifications +
  backend storage.
- **Server-side verification** is strongly recommended for any purchase worth protecting.
  See [`docs/SECURITY.md`](SECURITY.md).
