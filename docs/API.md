# API Reference

All public surface is in package `com.playbillingwrapper`. Models live in
`com.playbillingwrapper.model`, enums in `com.playbillingwrapper.status` /
`com.playbillingwrapper.type`, listeners in `com.playbillingwrapper.listener`.

## `PlayBillingWrapper`

### Construction

```java
PlayBillingWrapper(Context, BillingConfig, @Nullable WrapperListener)
PlayBillingWrapper(Context, BillingConfig, @Nullable WrapperListener, @Nullable Lifecycle)
```

The context is stored as `getApplicationContext()`. Pass
`ProcessLifecycleOwner.get().getLifecycle()` to have `release()` called automatically on
process shutdown.

### Lifecycle

| Method | Description |
|--------|-------------|
| `void connect()` | Open the billing connection. Idempotent. Also runs a full product + purchases query cycle. |
| `void release()` | Close the connection. Safe to call multiple times. |
| `void restorePurchases()` | Force a full refresh. |
| `boolean restorePurchases(long minIntervalMs)` | Throttled atomic refresh. Returns `true` only if this caller won the CAS race and actually dispatched. |
| `void setListener(@Nullable WrapperListener)` | Swap the callback surface. |

### Generic purchase API (works for any registered product)

| Method | Behaviour |
|--------|-----------|
| `void purchaseProduct(Activity, String productId)` | Launch Play flow for any non-consumable. Must be registered via `addLifetimeProductId`. |
| `void purchaseConsumable(Activity, String productId)` | Launch Play flow for a consumable. Must be registered via `addConsumableProductId`. Auto-consumed after Play confirms. |
| `void subscribe(Activity, String productId, String basePlanId)` | Launch subscription flow using the matching `SubscriptionSpec`'s trial + offer preferences. |
| `void subscribe(Activity, String productId, String basePlanId, boolean preferTrial)` | Same, but override the registered trial preference. |
| `void subscribe(Activity, SubscriptionSpec)` | Launch with an ad-hoc spec. |
| `void changeSubscription(Activity, String oldProductId, String newProductId, String newBasePlanId, ChangeMode)` | Upgrade / downgrade / swap. The `oldPurchaseToken` is looked up automatically. |
| `void openManageSubscription(Activity, String productId)` | Deep-link into Play's manage-subscription page. |

### Sugar purchase API (backward-compatible)

| Method | Behaviour |
|--------|-----------|
| `void buyLifetime(Activity)` | Forwards to `purchaseProduct(defaultLifetimeProductId)`. |
| `void subscribeMonthly(Activity)` | Forwards to `subscribe(defaultMonthlySpec)`. |
| `void subscribeYearlyWithTrial(Activity)` | Forwards to `subscribe(defaultYearlySpec)`. |

### Ownership + state queries

| Method | Returns |
|--------|---------|
| `boolean isOwned(String productId)` | User holds this product in PURCHASED state. |
| `boolean hasLifetime()` | Alias for `isOwned(defaultLifetimeProductId)`. |
| `SubscriptionState subscriptionState(String productId)` | First matching purchase wins. |
| `SubscriptionState subscriptionState(String productId, String basePlanId)` | Explicit lookup. |
| `SubscriptionState monthlyState()` / `yearlyState()` | Sugar for the default specs. |
| `boolean isTrialEligible(String productId, String basePlanId)` | Any free-trial offer on the base plan is eligible? |
| `boolean isTrialEligibleForYearly()` | Sugar for the default yearly spec. |
| `boolean isSubscribed()` | Any registered subscription currently entitling. |
| `boolean isPremium()` | Any lifetime product owned OR `isSubscribed()`. |
| `List<String> getActiveEntitlements()` | Product ids the user currently holds entitlement for. |
| `List<PurchaseInfo> getOwnedPurchases()` | Immutable snapshot of raw owned purchases. |

### Trial + pricing introspection

| Method | Returns |
|--------|---------|
| `String getTrialPeriodIso(String productId, String basePlanId)` | ISO 8601 billing period (`P3D`, `P7D`, `P14D`) of the first trial offer on the base plan. `null` if no trial offer. |
| `long getTrialEndMillis(PurchaseInfo)` | Wall-clock estimate of `purchaseTime + trialDuration`. `-1` if the purchase has no trial. |
| `String getFormattedPrice(String productId)` | Play-formatted price for a one-time product. |
| `String getFormattedPrice(String productId, String basePlanId)` | Formatted price of the first non-trial pricing phase of the best offer on a base plan. |
| `List<ProductDetails.PricingPhase> getPricingPhases(String productId, String basePlanId)` | Every phase of the best offer (trial auto-preferred, then preferredOfferId, then base plan). |

### Connection state

| Method | Returns |
|--------|---------|
| `boolean isReady()` | `BillingClient` connected AND product details fetched. |
| `boolean isPurchaseReconciliationComplete()` | First INAPP + SUBS `queryPurchasesAsync` round has completed. |

### Accessors

| Method | Returns |
|--------|---------|
| `BillingConnector rawConnector()` | Escape hatch for advanced flows. |
| `BillingConfig getConfig()` | Read the config back. |
| `IdempotencyStore getIdempotencyStore()` | The dedupe ledger, for manual `forget(token)` after refund / chargeback. |

## `BillingConfig.Builder`

| Method | Required? | Purpose |
|--------|-----------|---------|
| `addLifetimeProductId(String)` | At least one of lifetime / consumable / subscription | Register a non-consumable product id. |
| `addLifetimeProductIds(Iterable)` | Optional | Bulk-add. |
| `addConsumableProductId(String)` | At least one of the three | Register a consumable (coins / gems / lives). Auto-consumed after delivery. |
| `addConsumableProductIds(Iterable)` | Optional | Bulk-add. |
| `addSubscription(SubscriptionSpec)` | At least one of the three | Register one (productId, basePlanId) pair. |
| `addSubscriptions(Iterable)` | Optional | Bulk-add. |
| `defaultLifetimeProductId(String)` | Optional | Bind `buyLifetime(activity)`. Also registers the id. |
| `defaultMonthly(productId, basePlanId)` | Optional | Bind `subscribeMonthly(activity)` (no trial). |
| `defaultMonthlyWithTrial(productId, basePlanId)` | Optional | Same, trial auto-picked. |
| `defaultYearly(productId, basePlanId)` | Optional | Bind yearly sugar (no trial). |
| `defaultYearlyWithTrial(productId, basePlanId)` | Optional | Same, trial auto-picked. |
| `lifetimeProductId(String)` etc. | Optional | Legacy 3-shape aliases, still supported. |
| `userId(String)` | **yes** | One-way hashed stable user id (≤64 chars). |
| `profileId(String)` | Optional | For multi-profile apps. |
| `base64LicenseKey(String)` | Optional | Play Console → Monetization setup → Licensing. Enables on-device signature verification when non-null. Off by default. |
| `enableLogging(boolean)` | Optional | Verbose logcat on the `BillingConnector` tag. Default false. |
| `autoAcknowledge(boolean)` | Optional | Default true. Flip off only if you acknowledge server-side. |

`build()` throws `IllegalArgumentException` when no products are registered.

## `SubscriptionSpec`

```java
SubscriptionSpec.of(productId, basePlanId);                        // no trial, no offer override
SubscriptionSpec.withTrial(productId, basePlanId);                 // prefer free-trial offer
SubscriptionSpec.builder()
    .productId(productId)
    .basePlanId(basePlanId)
    .preferTrial(true)
    .preferredOfferId("winback_25")
    .tag("monthly_discount")
    .build();
```

| Field | Purpose |
|-------|---------|
| `productId` | Play Console subscription id. |
| `basePlanId` | Play Console base plan id. |
| `preferTrial` | If true, `subscribe(...)` auto-picks a free-trial offer when the user is eligible. |
| `preferredOfferId` | Explicit offer id to prefer (overrides trial auto-pick). |
| `tag` | Free-form classification string. Never sent to Play. |

## `ChangeMode`

Named aliases for `BillingFlowParams.SubscriptionUpdateParams.ReplacementMode`.

| Value | Play constant | Use |
|-------|---------------|-----|
| `UPGRADE_PRORATE_NOW` | `CHARGE_PRORATED_PRICE` | Monthly → yearly, charge prorated delta now. |
| `UPGRADE_CHARGE_FULL` | `CHARGE_FULL_PRICE` | Upgrade, charge full price, extend expiry by unused credit. |
| `SWAP_WITH_TIME_CREDIT` | `WITH_TIME_PRORATION` | Swap, credit unused time, no new charge until credit consumed. |
| `SWAP_WITHOUT_PRORATION` | `WITHOUT_PRORATION` | Swap now, new price at next renewal. Preserves trial. |
| `DOWNGRADE_DEFERRED` | `DEFERRED` | Yearly → monthly at next renewal. |

## `WrapperListener`

All methods have `default` no-op implementations — override what you need.

```java
void onReady();                                              // reconciliation complete
void onLifetimePurchased(PurchaseInfo);
void onConsumablePurchased(String productId, int quantity, PurchaseInfo);
void onSubscriptionActivated(String productId, SubscriptionState state, PurchaseInfo);
void onSubscriptionCancelled(String productId, PurchaseInfo);  // true→false transition only
void onPending(PurchaseInfo);
void onUserCancelled();
void onError(BillingResponse);
```

### `onSubscriptionCancelled` guarantees

- Fires at most **once per auto-renew true → false transition**, persisted across app
  restarts via `AutoRenewStateStore`.
- Does NOT fire on first-load observation of an already-cancelled purchase.
- Does NOT fire for prepaid plans (non-renewing by definition).
- Fires again if the user resubscribes and then cancels — resubscribes produce a fresh
  `purchaseToken`.

### `onConsumablePurchased` guarantees

- Fires only after Play confirms the consume.
- `quantity` is the Play-reported per-transaction count. Always multiply grants by it.

## `SubscriptionState`

| Value | Entitles? |
|-------|-----------|
| `ACTIVE` | ✓ |
| `CANCELED_ACTIVE` | ✓ |
| `PENDING` | ✗ |
| `EXPIRED` | ✗ |
| `IN_TRIAL` | — deprecated, never returned |

## `OfferSelector`

Static helpers, usually invoked internally.

```java
static String pick(ProductDetails details,
                   String basePlanId,
                   @Nullable String preferredOfferId,
                   boolean preferTrial);
static boolean isTrialEligible(ProductDetails details, String basePlanId);
```

## `IdempotencyStore`

`SharedPreferences`-backed dedupe ledger keyed on `purchaseToken`. Exposed via
`PlayBillingWrapper.getIdempotencyStore()`.

```java
store.markHandled(purchaseToken);     // uses commit() for crash durability
store.isHandled(purchaseToken);
store.forget(purchaseToken);          // call on refund / chargeback
store.clearAll();                     // tests only
```

## `BillingResponse`

Thin wrapper over `BillingResult`.

```java
ErrorType getErrorType();
int getResponseCode();
String getDebugMessage();
```

## `ErrorType`

Full enumeration with Javadoc on every value — see
`library/src/main/java/com/playbillingwrapper/type/ErrorType.java`.

## `BillingConnector`

The underlying client, exposed via `rawConnector()`. Use it for flows the facade does not
cover (installment plans, prepaid plans, one-time offers, custom offer routing). Most
callers should not need it.
