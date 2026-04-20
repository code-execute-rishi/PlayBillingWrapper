# Integration Guide

This doc walks you from "never touched Play Billing" to "three working purchase flows in
production" in about an hour.

## Prerequisites

- Android Studio Ladybug or newer (AGP 8.7+).
- A Play Console account with Merchant profile enabled.
- A signed release build of your app uploaded to at least the **Internal testing** track.

## Step 1 — Add the dependency

`settings.gradle` (at the repo root):

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

`app/build.gradle`:

```gradle
dependencies {
    implementation 'com.github.code-execute-rishi:PlayBillingWrapper:0.1.1'
}
```

`AndroidManifest.xml`:

```xml
<uses-permission android:name="com.android.vending.BILLING" />
```

## Step 2 — Create products in Play Console

### Lifetime (one-time unlock)

1. Play Console → Monetize → Products → **In-app products** → Create product.
2. Product id: `com.yourapp.lifetime` (anything — you'll pass this to the builder).
3. Type: **Managed product** (non-consumable).
4. Name, description, price. Save, then **Activate**.

### Monthly subscription (no trial)

1. Play Console → Monetize → Products → **Subscriptions** → Create subscription.
2. Product id: `com.yourapp.premium_monthly`.
3. Add a **Base plan**: id `monthly`, billing period `P1M`, auto-renewing, set price.
4. Activate.

### Yearly subscription with trial

1. Play Console → Monetize → Products → **Subscriptions** → Create subscription.
2. Product id: `com.yourapp.premium_yearly`.
3. Add a **Base plan**: id `yearly`, billing period `P1Y`, auto-renewing, set price.
4. Add a **Developer-determined offer** on that base plan:
   - Offer id: `freetrial`
   - Eligibility: New customer acquisition → New customers
   - Phase 1: Free, `P7D` (7 days)
5. Activate the subscription + the base plan + the offer.

## Step 3 — Configure the wrapper

```java
BillingConfig cfg = BillingConfig.builder()
    .lifetimeProductId("com.yourapp.lifetime")
    .monthlySubProductId("com.yourapp.premium_monthly")
    .monthlyBasePlanId("monthly")
    .yearlySubProductId("com.yourapp.premium_yearly")
    .yearlyBasePlanId("yearly")
    .yearlyTrialOfferId("freetrial")         // pass null to auto-pick any free-trial offer
    .userId(sha256(currentUserId))           // REQUIRED — a one-way hash of your user id
    .enableLogging(BuildConfig.DEBUG)
    .autoAcknowledge(true)                   // default; flip to false only if you ack server-side
    .build();

playBillingWrapper = new PlayBillingWrapper(this, cfg, /*listener=*/null);
playBillingWrapper.connect();
```

### About `userId`

`obfuscatedAccountId` is how Google binds a purchase to one of your users. It must be:

- **Stable**: the same user → always the same id.
- **Non-PII**: never an email, never a phone number. Use a hash.
- **Under 64 characters** (Play's hard limit).

A SHA-256 hex string is fine:

```java
private static String sha256(String input) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.substring(0, 64);
    } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
}
```

If your user is not logged in yet, use a device id (install id) as a stable placeholder, and
migrate once they log in. Without a stable id, Google can't enforce "one free trial per
account" and your server can't map `purchaseToken` back to a user.

## Step 4 — Call the three methods

```java
billing.buyLifetime(activity);
billing.subscribeMonthly(activity);
billing.subscribeYearlyWithTrial(activity);
```

That's it. The wrapper handles:

1. Picking the right subscription offer token.
2. Attaching `obfuscatedAccountId` / `obfuscatedProfileId`.
3. Launching the Play dialog.
4. Handling the result: `PURCHASED`, `PENDING`, `USER_CANCELED`, transient errors.
5. Acknowledging the purchase (within Google's 72h window).
6. Delivering `onLifetimePurchased` / `onSubscriptionActivated` exactly once per
   `purchaseToken`, even across app restarts.
7. Reconciling on every reconnect in case the Play Services cache was stale.

## Step 5 — Check state wherever you need it

```java
if (billing.isPremium()) {
    showPremiumFeatures();
} else {
    showPaywall();
}
```

- `hasLifetime()` — true if the lifetime product is owned.
- `monthlyState()` / `yearlyState()` — returns a `SubscriptionState`:
  - `ACTIVE` — auto-renewing, not in trial
  - `IN_TRIAL` — auto-renewing, currently in a free-trial pricing phase
  - `CANCELED_ACTIVE` — cancelled but still entitled until period end
  - `PENDING` — slow payment method, not yet cleared
  - `EXPIRED` — not owned

  The wrapper is local-only. Play's server-side states (`GRACE_PERIOD`, `ON_HOLD`,
  `PAUSED`) are not observable reliably without a backend — if you need them, stand up
  your own server and query the Google Play Developer API directly.
- `isTrialEligibleForYearly()` — Play determines this; ineligible offers are silently omitted
  from `ProductDetails`, so the lib checks whether any offer on the base plan still has a free
  pricing phase.
- `isSubscribed()` — true if either subscription is currently entitling.
- `isPremium()` — `hasLifetime() || isSubscribed()`.

## Step 6 — Test on a real device

1. Upload your signed release APK to **Internal testing** in Play Console.
2. Add your Google account as a **License tester** (Play Console → Settings → License testing).
3. Opt in to the testing track from the account on the device.
4. Install the APK from the Play Store testing link (not sideloaded — sideloaded builds don't
   get billing responses).
5. Launch the app. Products should fetch within a second or two. If not, enable logging and
   check the logcat tag `BillingConnector`.

### Common first-time errors

| Error | Cause | Fix |
|-------|-------|-----|
| `PRODUCT_NOT_EXIST` | Product id typo, product not activated, or app not uploaded. | Verify id in Play Console; ensure app is on a testing track. |
| `BILLING_UNAVAILABLE` | Test device doesn't have a license tester account, or sideloaded APK. | Install via Play Store testing link; confirm tester account. |
| `DEVELOPER_ERROR` at purchase time | Offer token resolution failed — wrong `basePlanId` or no offer matches. | Double-check `monthlyBasePlanId` / `yearlyBasePlanId` spelling. |
| Products return empty list | APK signed with different key than the one uploaded to Play. | Use the same upload keystore. |
| No `onLifetimePurchased` callback after real purchase | Activity was destroyed before the callback fired. | Use the Application-scoped wrapper instance; don't create a new one per Activity. |

## Step 7 — Ship to production

Before flipping the production track switch:

- [ ] License tester covers all three purchase paths.
- [ ] (Optional) Your backend receives and verifies `purchaseToken` (see `SECURITY.md`).
- [ ] Privacy policy mentions that user ids are one-way hashed before being sent to Google.
- [ ] Settings screen has a "Manage subscription" entry point calling
      `billing.openManageSubscription(activity, productId)`.
