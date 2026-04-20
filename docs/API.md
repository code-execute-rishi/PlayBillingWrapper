# API Reference

All public surface is in package `com.playbillingwrapper`. The handful of models live in
`com.playbillingwrapper.model` / `status` / `type` / `listener`.

## `PlayBillingWrapper`

### Constructor

```java
PlayBillingWrapper(Context context, BillingConfig config, @Nullable WrapperListener listener)
PlayBillingWrapper(Context context, BillingConfig config, @Nullable WrapperListener listener,
                   @Nullable Lifecycle lifecycle)
```

- `context` may be an Activity; the wrapper will always store `getApplicationContext()`.
- `lifecycle` is usually `ProcessLifecycleOwner.get().getLifecycle()` — the wrapper calls
  `release()` on `ON_DESTROY`.

### Connection

| Method | Description |
|--------|-------------|
| `void connect()` | Open the connection. Idempotent. |
| `void release()` | Close the connection. Safe to call multiple times. |
| `void restorePurchases()` | Re-trigger a full product + purchases fetch. Call on app foreground. |

### Purchases (the three methods)

| Method | What it does |
|--------|--------------|
| `void buyLifetime(Activity)` | Launches the Play flow for the configured lifetime product. |
| `void subscribeMonthly(Activity)` | Launches the Play flow for the monthly sub, base plan offer. |
| `void subscribeYearlyWithTrial(Activity)` | Launches the yearly flow; picks the trial offer when the user is still trial-eligible, falls back to the base plan offer otherwise. |

### State queries

| Method | Returns |
|--------|---------|
| `boolean hasLifetime()` | true if the lifetime product is currently owned. |
| `SubscriptionState monthlyState()` | lifecycle state for the monthly subscription. |
| `SubscriptionState yearlyState()` | lifecycle state for the yearly subscription. |
| `boolean isTrialEligibleForYearly()` | whether the user can still start a free trial. |
| `boolean isSubscribed()` | true if either subscription currently entitles. |
| `boolean isPremium()` | `hasLifetime() || isSubscribed()`. |
| `List<PurchaseInfo> getOwnedPurchases()` | Read-only snapshot of every known owned purchase. |

### Management

| Method | Description |
|--------|-------------|
| `openManageSubscription(Activity, String productId)` | Deep-link to Play's manage-subscription page. Does NOT cancel; lets the user do it. |
| `setListener(WrapperListener)` | Replace the listener. |
| `BillingConnector rawConnector()` | Escape hatch for advanced workflows. |

## `BillingConfig.Builder`

| Method | Required? | Notes |
|--------|-----------|-------|
| `lifetimeProductId(String)` | if you want lifetime | Non-consumable product id. |
| `monthlySubProductId(String)` | if you want monthly | Subscription product id. |
| `monthlyBasePlanId(String)` | with monthly | Base plan id from Play Console. |
| `yearlySubProductId(String)` | if you want yearly | Subscription product id. |
| `yearlyBasePlanId(String)` | with yearly | Base plan id from Play Console. |
| `yearlyTrialOfferId(String)` | optional | Explicit offer id to prefer. If null, any free-trial offer is auto-picked. |
| `userId(String)` | **yes** | One-way hashed stable user id (≤64 chars). |
| `profileId(String)` | optional | For multi-profile apps. |
| `base64LicenseKey(String)` | optional | Play Console → Monetization setup → Licensing. See `SECURITY.md`. |
| `enableLogging(boolean)` | optional | Verbose logcat. Default false. |
| `autoAcknowledge(boolean)` | optional | Default true. Flip to false only if you acknowledge server-side. |

## `WrapperListener`

All methods have `default` implementations — override only what you need.

```java
void onReady();
void onLifetimePurchased(PurchaseInfo);
void onSubscriptionActivated(String productId, SubscriptionState state, PurchaseInfo);
void onPending(PurchaseInfo);
void onUserCancelled();
void onError(BillingResponse);
```

Each purchase callback fires **at most once per `purchaseToken`**, persistently across app
restarts. If you need a callback every time the user reaches the paywall, query the state
methods directly (they read a fresh snapshot).

## `SubscriptionState`

| Value | Entitled? |
|-------|-----------|
| `ACTIVE` | ✓ |
| `IN_TRIAL` | ✓ |
| `CANCELED_ACTIVE` | ✓ |
| `PENDING` | ✗ |
| `EXPIRED` | ✗ |

All values are derived from the `Purchase` object Play returns. No RTDN, no backend.

## `OfferSelector`

Static helpers. Usually invoked internally, but exposed for advanced offer routing.

```java
static String pick(ProductDetails details,
                   String basePlanId,
                   @Nullable String preferredOfferId,
                   boolean preferTrial);
static boolean isTrialEligible(ProductDetails details, String basePlanId);
```

## `IdempotencyStore`

Rarely needed directly. The wrapper uses it to dedupe purchase callbacks across app restarts.

```java
new IdempotencyStore(context);
store.markHandled(purchaseToken);
store.isHandled(purchaseToken);
store.forget(purchaseToken);          // on refund / void
store.clearAll();                     // tests only
```

## `BillingConnector`

The underlying client. Exposed via `rawConnector()` for advanced cases (custom product
catalogs, consumables, multiple subscriptions beyond the three shapes). See the Javadoc on
the class for specifics.

## `BillingResponse`

Thin wrapper over `BillingResult`:

```java
ErrorType getErrorType();
int getResponseCode();
String getDebugMessage();
```

## `ErrorType`

Enumeration covering every Play Billing response code plus library-specific ones:
`CLIENT_NOT_READY`, `PRODUCT_NOT_EXIST`, `USER_CANCELED`, `DEVELOPER_ERROR`, `NETWORK_ERROR`,
`ITEM_ALREADY_OWNED`, `PENDING_PURCHASE_RETRY_ERROR`, etc. See the source for the full list.
