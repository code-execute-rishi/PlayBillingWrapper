# Testing

Play Billing is notoriously awkward to test. This doc lists the three paths that actually
work, in order of setup effort.

## 1 — Static test product ids (zero Play Console setup)

Google hard-codes four test product ids that always return without requiring anything to be
published:

| Product id | Behaviour |
|------------|-----------|
| `android.test.purchased` | Always returns `PURCHASED`. |
| `android.test.canceled` | Always returns `USER_CANCELED`. |
| `android.test.item_unavailable` | Always returns `ITEM_UNAVAILABLE`. |
| `android.test.refunded` | Deprecated — don't rely on it. |

These work on any device without a license tester account. Use them for unit-test-style
smoke checks, but they don't simulate subscriptions, trials, or pending purchases. Don't ship
them.

## 2 — License tester accounts (required for real flows)

1. Play Console → Settings → License testing → Add tester Google accounts.
2. Upload at least one signed build to **Internal testing** (not production).
3. On the test device, opt in to the testing track from the Play Store testing link.
4. Install the app via Play. **Sideloaded APKs do not receive billing responses.**

Real purchases are free for testers. You can cancel a test subscription from
`https://play.google.com/store/account/subscriptions` and immediately re-test.

### Accelerated renewals

Testers' subscription billing periods are compressed:

| Real period | Test period |
|-------------|-------------|
| Weekly      | 5 minutes   |
| Monthly     | 5 minutes   |
| Every 3 months | 10 minutes |
| Every 6 months | 15 minutes |
| Yearly      | 30 minutes  |

Renewals cap at 6. After that the subscription expires normally.

## 3 — Play Billing Lab (optional)

Play Billing Lab is a separate Play Console tab that lets you force specific transitions on a
test subscription. The wrapper is local-only, so most of its value lies in the states it can
still observe from `Purchase` data:

- **Cancel** — confirm `CANCELED_ACTIVE` until expiry.
- **Refund** — confirm the purchase disappears from `getOwnedPurchases()` after the next
  restore.

The grace-period / on-hold / paused transitions require a backend to observe reliably and are
outside this library's scope.

Access: Play Console → Monetize → Subscriptions → your-sub → Testing → Play Billing Lab.

## Unit tests

The wrapper itself isn't heavily testable in isolation because `BillingClient` is final and
depends on a Google Play Services service connection. For the pieces that don't:

- `OfferSelector.pick(...)` — fully unit-testable with a mocked `ProductDetails`. Write tests
  for each branch: preferred offer id hits, trial preferred + eligible, trial preferred +
  ineligible, base plan fallback.
- `IdempotencyStore` — instrumentation test against a real `SharedPreferences`.
- `PlayBillingWrapper.computeSubscriptionState` — unit-test with a mocked `PurchaseInfo`.

Skeleton:

```java
@Test
public void picks_trial_offer_when_eligible() {
    ProductDetails details = mockDetailsWith(
        offer("yearly", /*offerId=*/"freetrial", phase(0L), phase(12_99_00_000L)),
        offer("yearly", /*offerId=*/null, phase(12_99_00_000L))
    );
    String token = OfferSelector.pick(details, "yearly", null, true);
    assertEquals("trial-token", token);
}

@Test
public void falls_back_to_base_plan_when_ineligible() {
    ProductDetails details = mockDetailsWith(
        offer("yearly", /*offerId=*/null, phase(12_99_00_000L))
    );
    String token = OfferSelector.pick(details, "yearly", null, true);
    assertEquals("base-plan-token", token);
}
```

## Instrumentation

Use `Robolectric` or a real device emulator with the Play Store installed (API 34+). Point
the wrapper at `android.test.purchased` for the lifetime product and exercise the full
purchase→acknowledge→idempotent-redelivery loop.

## QA checklist before production

- [ ] Buy lifetime on a fresh install → `hasLifetime()` becomes true.
- [ ] Reinstall the app → `hasLifetime()` still true (restore works).
- [ ] Buy monthly → `monthlyState() == ACTIVE` after Play dialog closes.
- [ ] Cancel monthly from Play → `monthlyState() == CANCELED_ACTIVE` until expiry.
- [ ] Buy yearly as a fresh tester (trial-eligible) → `onSubscriptionActivated(..., IN_TRIAL, ...)`.
- [ ] Buy yearly as a returning tester (ineligible) → `onSubscriptionActivated(..., ACTIVE, ...)`.
- [ ] Pending payment method (cash/bank transfer test id) → `onPending(...)` fires; does not
      grant entitlement until state transitions.
- [ ] Kill and restart the app mid-flow → idempotent re-delivery does not double-grant.
- [ ] Enable airplane mode mid-purchase → wrapper surfaces a `NETWORK_ERROR`; no ghost
      entitlement granted.
- [ ] Refund a test purchase from Play Console → app revokes the entitlement after the next
      `restorePurchases()`.
