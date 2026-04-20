package com.playbillingwrapper.status;

/**
 * Lifecycle state of a subscription, computed strictly from local
 * {@link com.android.billingclient.api.Purchase} data. No server / RTDN signals are consumed.
 * <p>
 * Google's grace-period / on-hold / paused / in-trial states are not observable from the
 * client in a fully reliable way and are intentionally omitted. If your business depends on
 * them, run a backend and query the Google Play Developer API directly.
 */
public enum SubscriptionState {

    /** Purchased and auto-renewing. Entitles the user. */
    ACTIVE,

    /**
     * User cancelled but the paid period hasn't ended yet — still entitled, will not renew.
     * Computed from {@code isAutoRenewing() == false} with the purchase still present.
     */
    CANCELED_ACTIVE,

    /** Slow-payment method (cash, bank transfer) — not yet cleared. No entitlement. */
    PENDING,

    /** No purchase record for this product. */
    EXPIRED,

    /**
     * @deprecated Client-side Purchase objects do not reliably indicate which pricing phase
     * the subscription is currently in; the trial/paid distinction requires the Google Play
     * Developer API. This value is never returned by the wrapper in 0.2+. Retained only for
     * source compatibility with 0.1.x callers.
     */
    @Deprecated
    IN_TRIAL
}
