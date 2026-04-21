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

    /**
     * A consumable product was purchased and successfully consumed on Google's side.
     * Grant the in-game resource from here (coins, gems, lives). The per-purchase quantity
     * is {@code purchase.getQuantity()} -- always honor it instead of assuming 1.
     *
     * @param productId Play Console product id that was bought
     * @param quantity  how many units the user bought in this transaction
     * @param purchase  raw purchase info (token, order id, etc.)
     */
    default void onConsumablePurchased(@NonNull String productId,
                                       int quantity,
                                       @NonNull PurchaseInfo purchase) { }

    /** A subscription became active. */
    default void onSubscriptionActivated(@NonNull String productId,
                                         @NonNull SubscriptionState state,
                                         @NonNull PurchaseInfo purchase) { }

    /**
     * Fired once per purchase when {@code Purchase.isAutoRenewing()} flips from true to
     * false -- the user has cancelled the subscription via Play but the paid period is
     * still active ({@link SubscriptionState#CANCELED_ACTIVE}).
     * <p>
     * This is the right hook for a "re-engage" scheduler: show a retention prompt, queue a
     * reminder before the paid period ends, etc. Fires at most once per {@code purchaseToken}
     * transition; persisted across app restarts.
     *
     * @param productId the Play Console product id
     * @param purchase  the current {@link PurchaseInfo} (with {@code isAutoRenewing() == false})
     */
    default void onSubscriptionCancelled(@NonNull String productId,
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
