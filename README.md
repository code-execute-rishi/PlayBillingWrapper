# PlayBillingWrapper

[![CI](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/ci.yml/badge.svg)](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/ci.yml)
[![Release](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/release.yml/badge.svg)](https://github.com/code-execute-rishi/PlayBillingWrapper/actions/workflows/release.yml)
[![JitPack](https://jitpack.io/v/code-execute-rishi/PlayBillingWrapper.svg)](https://jitpack.io/#code-execute-rishi/PlayBillingWrapper)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg)](https://android-arsenal.com/api?level=23)
[![Billing](https://img.shields.io/badge/Play%20Billing-8.3.0-blue.svg)](https://developer.android.com/google/play/billing/release-notes)

Java wrapper around Google Play Billing Library 8.3.0. Three product shapes, three method
calls, one builder, zero backend.

1. **Lifetime** — one-time non-consumable unlock.
2. **Monthly subscription** — auto-renewing, no trial.
3. **Yearly subscription with a free trial** — trial auto-picked when the user is eligible.

```java
billing.buyLifetime(activity);
billing.subscribeMonthly(activity);
billing.subscribeYearlyWithTrial(activity);
```

---

## Table of contents

1. [Why this library exists](#why-this-library-exists)
2. [What it does for you](#what-it-does-for-you)
3. [What it does NOT do](#what-it-does-not-do-gaps--caveats)
4. [Installation](#installation)
5. [Play Console setup](#play-console-setup)
6. [Quick start](#quick-start)
7. [Full usage walkthrough](#full-usage-walkthrough)
8. [Public API reference](#public-api-reference)
9. [State model](#state-model)
10. [Idempotent delivery](#idempotent-delivery)
11. [Error handling](#error-handling)
12. [Testing](#testing)
13. [Security notes](#security-notes)
14. [Troubleshooting](#troubleshooting)
15. [Versioning + auto-release](#versioning--auto-release)
16. [Roadmap](#roadmap)
17. [License + credits](#license--credits)

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
opinionated for the three shapes above, and safe by default.

---

## What it does for you

- `BillingClient` lifecycle + reconnect with exponential backoff.
- `queryProductDetailsAsync` for both INAPP and SUBS, reconciled on every connect.
- **Auto-acknowledgment** with a 3-retry exponential backoff (prevents 72-hour auto-refund).
- **Pending-purchase auto-retry** with an effectively unbounded timeout. Real cash / bank-
  transfer PENDING purchases can take days — the wrapper keeps the `purchaseToken` alive
  and reconciles on every reconnect.
- **Idempotent callback delivery** — `onLifetimePurchased` / `onSubscriptionActivated` fire
  at most once per `purchaseToken`, persisted across app restarts via `SharedPreferences`.
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

### Out of scope (planned or deliberately omitted)

| Feature | Status | Workaround |
|---------|--------|------------|
| Subscription upgrade / downgrade (monthly ↔ yearly, proration, `ReplacementMode`) | Planned for 0.2 | Drop to `rawConnector().launchBillingFlow(...)` manually. |
| Consumable multi-quantity handling | Out of scope — not one of the three shapes | Use `rawConnector()` + `Purchase.getQuantity()` directly. |
| Installment plans / prepaid plans | Out of scope | `rawConnector()`. |
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
    implementation 'com.github.code-execute-rishi:PlayBillingWrapper:v0.1.5'
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

## Quick start

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
| `void restorePurchases()` | Trigger a fresh product + ownership query cycle. |

#### The three purchase methods

| Method | Behaviour |
|--------|-----------|
| `void buyLifetime(Activity)` | Launches the Play flow for the configured lifetime product. |
| `void subscribeMonthly(Activity)` | Launches the monthly subscription with its base plan offer. |
| `void subscribeYearlyWithTrial(Activity)` | Launches the yearly subscription. Auto-picks a free-trial offer when the user is eligible; falls back to the base plan offer. |

#### State queries

| Method | Returns |
|--------|---------|
| `boolean hasLifetime()` | User owns the lifetime product. |
| `SubscriptionState monthlyState()` | Lifecycle state for the monthly subscription. |
| `SubscriptionState yearlyState()` | Lifecycle state for the yearly subscription. |
| `boolean isTrialEligibleForYearly()` | Play-determined trial eligibility. |
| `boolean isSubscribed()` | Either subscription is entitling. |
| `boolean isPremium()` | `hasLifetime() \|\| isSubscribed()`. |
| `List<PurchaseInfo> getOwnedPurchases()` | Immutable snapshot of owned purchases. |

#### Management

| Method | Description |
|--------|-------------|
| `void setListener(@Nullable WrapperListener)` | Swap the callback surface. |
| `void openManageSubscription(Activity, String productId)` | Deep-link into Play. |
| `BillingConnector rawConnector()` | Escape hatch for advanced use cases. |

### `BillingConfig.Builder`

| Method | Required? | Purpose |
|--------|-----------|---------|
| `lifetimeProductId(String)` | If you want lifetime | Non-consumable product id. |
| `monthlySubProductId(String)` | If you want monthly | Subscription product id. |
| `monthlyBasePlanId(String)` | With monthly | Base plan id from Play Console. |
| `yearlySubProductId(String)` | If you want yearly | Subscription product id. |
| `yearlyBasePlanId(String)` | With yearly | Base plan id from Play Console. |
| `yearlyTrialOfferId(String)` | Optional | Explicit offer id to prefer. If null, any free-trial offer is auto-picked. |
| `userId(String)` | **yes** | One-way hashed stable user id (≤64 chars). |
| `profileId(String)` | Optional | For multi-profile apps. |
| `base64LicenseKey(String)` | Optional | Play Console → Monetization setup → Licensing. Enables weak on-device signature verification when non-null. |
| `enableLogging(boolean)` | Optional | Verbose logcat on the `BillingConnector` tag. Default false. |
| `autoAcknowledge(boolean)` | Optional | Default true. Turn off only if you acknowledge server-side. |

### `WrapperListener`

All methods have `default` no-op implementations — override only what you need.

```java
void onReady();                                         // ownership reconciliation complete
void onLifetimePurchased(PurchaseInfo);
void onSubscriptionActivated(String productId, SubscriptionState state, PurchaseInfo);
void onPending(PurchaseInfo);                           // slow payment, do NOT grant yet
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

On refund / chargeback, call `billing.rawConnector().getIdempotencyStore().forget(token)`
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

The library ships with 17 unit tests — 11 for `OfferSelector` (trial preference, base-plan
fallback, offerId override, eligibility) and 6 for `IdempotencyStore` (persistence, forget,
clearAll).

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
`implementation 'com.github.code-execute-rishi:PlayBillingWrapper:v0.1.5'`.

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
