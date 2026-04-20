package com.playbillingwrapper.listener;

import androidx.annotation.NonNull;

import com.playbillingwrapper.model.BillingResponse;
import com.playbillingwrapper.model.PurchaseInfo;
import com.playbillingwrapper.status.SubscriptionState;

/**
 * Simpler, high-level listener for {@code PlayBillingWrapper}.
 * <p>
 * Every method has a no-op default so implementers can override only what they care about.
 */
public interface WrapperListener {

    /**
     * Fired once after products are fetched and ownership is reconciled, and again whenever
     * the ownership state changes (new purchase, consumed item, restore, etc.).
     */
    default void onReady() { }

    /** A one-time product (lifetime) became owned. */
    default void onLifetimePurchased(@NonNull PurchaseInfo purchase) { }

    /** A subscription became active. */
    default void onSubscriptionActivated(@NonNull String productId,
                                         @NonNull SubscriptionState state,
                                         @NonNull PurchaseInfo purchase) { }

    /**
     * A purchase landed in {@code PENDING} state (cash, bank transfer, family approval, etc.).
     * Do NOT grant entitlement. {@code PlayBillingWrapper} will keep polling / reconcile on
     * reconnects and re-fire {@link #onLifetimePurchased(PurchaseInfo)} or
     * {@link #onSubscriptionActivated(String, SubscriptionState, PurchaseInfo)} once the state
     * transitions to {@code PURCHASED}.
     */
    default void onPending(@NonNull PurchaseInfo purchase) { }

    /** User pressed back / cancelled the Play dialog. */
    default void onUserCancelled() { }

    /** Something went wrong. */
    default void onError(@NonNull BillingResponse response) { }
}
