# Changelog

## 0.1.0 — 2026-04-20

Initial release.

### Added

- `PlayBillingWrapper` facade with three first-class methods: `buyLifetime()`,
  `subscribeMonthly()`, `subscribeYearlyWithTrial()`.
- `BillingConfig` builder.
- `OfferSelector` — picks offer tokens by base plan id + optional offer id, auto-prefers
  free-trial when the user is eligible.
- `SubscriptionState` enum covering `ACTIVE`, `IN_TRIAL`, `CANCELED_ACTIVE`, `PENDING`,
  `EXPIRED`. Local-only — computed from `Purchase` data, no RTDN.
- `IdempotencyStore` — persistent dedupe ledger keyed on `purchaseToken`.
- `WrapperListener` — simplified listener with default no-op methods.
- `obfuscatedAccountId` / `obfuscatedProfileId` wired into every `BillingFlowParams`.
- `openManageSubscription()` deep link.
- Pending retries: unbounded by default (previously capped at 5 minutes / 3 attempts).
- Documentation: INTEGRATION, API, SECURITY, TESTING, MIGRATION.
- Sample app demonstrating all three shapes.

### Based on

Forked and rewritten from [moisoni97/google-inapp-billing](https://github.com/moisoni97/google-inapp-billing)
(Apache-2.0). Retained its pending-retry state machine, acknowledge-with-backoff logic, and
Play Store installation heuristic as correctness references.
