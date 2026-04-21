package com.playbillingwrapper.status;

/**
 * Lifecycle state of a subscription, computed from local
 * {@link com.android.billingclient.api.Purchase} data.
 * <p>
 * {@link #PAUSED} is observable from the client when the purchase list is queried with
 * {@code QueryPurchasesParams.Builder.includeSuspendedSubscriptions(true)} — the wrapper
 * sets this automatically. The other server-side states (grace period, on hold, revoked)
 * still require a backend + the Google Play Developer API.
 */
public enum SubscriptionState {

    /** Purchased and auto-renewing. Entitles the user. */
    ACTIVE,

    /**
     * User cancelled but the paid period hasn't ended yet — still entitled, will not renew.
     * Computed from {@code isAutoRenewing() == false} with the purchase still present.
     */
    CANCELED_ACTIVE,

    /**
     * User paused the subscription via Play. Entitlement is revoked until the user resumes.
     * Computed from {@code Purchase.isSuspended() == true}.
     */
    PAUSED,

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
