package com.playbillingwrapper.type;

import com.android.billingclient.api.BillingFlowParams;

/**
 * Named aliases for {@link BillingFlowParams.SubscriptionUpdateParams.ReplacementMode}.
 * Use when calling {@code PlayBillingWrapper.changeSubscription(...)} so you don't have
 * to remember which Play constant does what.
 */
public enum ChangeMode {

    /**
     * Upgrade: swap immediately, charge the prorated delta, keep the original billing
     * date. Best default for monthly → yearly upgrades where the user expects to pay
     * extra now and keep their existing renewal anchor.
     * <p>Maps to {@link BillingFlowParams.SubscriptionUpdateParams.ReplacementMode#CHARGE_PRORATED_PRICE}.
     */
    UPGRADE_PRORATE_NOW(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE),

    /**
     * Upgrade: swap immediately, charge the full new price, extend the expiry by the
     * credit of the unused portion. Common for annual upgrades with "+1 year added".
     * <p>Maps to {@link BillingFlowParams.SubscriptionUpdateParams.ReplacementMode#CHARGE_FULL_PRICE}.
     */
    UPGRADE_CHARGE_FULL(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE),

    /**
     * Swap immediately, credit the user with the unused time on the old plan (no new
     * charge until that credit is consumed).
     * <p>Maps to {@link BillingFlowParams.SubscriptionUpdateParams.ReplacementMode#WITH_TIME_PRORATION}.
     */
    SWAP_WITH_TIME_CREDIT(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION),

    /**
     * Swap immediately, no proration. New price applies at the next renewal. Preserves
     * an ongoing free-trial phase — useful when you want a trial to survive a plan change.
     * <p>Maps to {@link BillingFlowParams.SubscriptionUpdateParams.ReplacementMode#WITHOUT_PRORATION}.
     */
    SWAP_WITHOUT_PRORATION(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION),

    /**
     * Downgrade: defer the change until the next renewal. User stays on the current plan
     * until the paid period ends, then switches. Play's recommended default for
     * yearly → monthly downgrades.
     * <p>Maps to {@link BillingFlowParams.SubscriptionUpdateParams.ReplacementMode#DEFERRED}.
     */
    DOWNGRADE_DEFERRED(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED);

    public final int playReplacementMode;

    ChangeMode(int mode) {
        this.playReplacementMode = mode;
    }
}
