# Migrating from `moisoni97/google-inapp-billing`

PlayBillingWrapper's core is derived from moisoni's work. If you're already using it, the
migration is a few find-and-replace steps plus picking up the facade.

## Package renames

```
games.moisoni.google_iab.*        ->  com.playbillingwrapper.*
games.moisoni.google_iab.model.*  ->  com.playbillingwrapper.model.*
games.moisoni.google_iab.type.*   ->  com.playbillingwrapper.type.*
games.moisoni.google_iab.status.* ->  com.playbillingwrapper.status.*
games.moisoni.google_iab.listener.* -> com.playbillingwrapper.listener.*
```

## Method renames

| moisoni97 | PlayBillingWrapper |
|-----------|--------------------|
| `new BillingConnector(ctx, key, lifecycle)` | Still works. Prefer `new PlayBillingWrapper(ctx, cfg, listener)` for the 3-shape shortcut. |
| `subscribe(activity, productId, offerIndex)` | **Removed.** Use `billing.subscribeMonthly(...)` / `billing.subscribeYearlyWithTrial(...)` or the raw `connector.purchaseSubscription(activity, productId, offerToken)` with `OfferSelector.pick(...)`. |
| `unsubscribe(activity, productId)` | Deprecated alias for `openManageSubscription(activity, productId)`. Name is misleading — it only deep-links to Play, does not cancel. |
| `isSubscriptionActive(productId)` | Still works but only returns the auto-renewing flag. Use `billing.monthlyState()` / `yearlyState()` for the richer enum. |

## New required call

Every `BillingConnector` must now receive an obfuscated account id. The `PlayBillingWrapper`
facade wires this automatically from `BillingConfig.userId(...)`. If you use `BillingConnector`
directly:

```java
connector.setObfuscatedAccountId(sha256(myUserId));
connector.setObfuscatedProfileId(sha256(myProfileId));
```

Without it, your subscription trial-per-account enforcement is blind and your backend cannot
map purchases back to users.

## Behaviour change: pending retries

moisoni97 gave up on a PENDING purchase after 5 minutes / 3 retries and removed the token
from the local list. Real PENDING (cash, bank transfer) can take days. PlayBillingWrapper
retries indefinitely by default and preserves the token across give-up points so the next
`connect()` reconciles with Play. If you want the old behaviour:

```java
connector.setMaxPendingRetries(3)
         .setMaxPendingDurationMs(5 * 60 * 1000L);
```

## New: persistent idempotency ledger

Purchase callbacks are now deduped across app restarts via `SharedPreferences`. If you
were handling duplicate `onProductsPurchased` calls manually, you can delete that code —
the `PlayBillingWrapper` facade does it for you. If you work with `BillingConnector`
directly, instantiate an `IdempotencyStore` yourself.

## Gradle

Switch the dependency coordinate:

```diff
- implementation 'com.github.moisoni97:google-inapp-billing:1.1.8'
+ implementation 'com.github.code-execute-rishi:PlayBillingWrapper:0.1.1'
```

Both libraries use `com.android.billingclient:billing:8.3.0`, so you don't need to change
anything else in your `build.gradle`.

## License

moisoni97/google-inapp-billing is Apache-2.0. PlayBillingWrapper inherits that license.
