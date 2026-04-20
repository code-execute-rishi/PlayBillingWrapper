package com.playbillingwrapper.status;

/**
 * Lifecycle state of a subscription, computed strictly from local
 * {@link com.android.billingclient.api.Purchase} data. No server / RTDN signals are consumed.
 * <p>
 * Google's grace-period / on-hold / paused states are not observable from the client in a
 * fully reliable way and are intentionally omitted. If your business depends on them, run a
 * backend and query the Google Play Developer API directly.
 */
public enum SubscriptionState {

    /** Active, auto-renewing, not in a free-trial pricing phase. */
    ACTIVE,

    /** Active, auto-renewing, currently inside a free-trial pricing phase. */
    IN_TRIAL,

    /**
     * User cancelled but the paid period hasn't ended yet — still entitled, will not renew.
     * Computed from {@code isAutoRenewing() == false} with the purchase still present.
     */
    CANCELED_ACTIVE,

    /** Slow-payment method (cash, bank transfer) — not yet cleared. No entitlement. */
    PENDING,

    /** No purchase record for this product. */
    EXPIRED
}
