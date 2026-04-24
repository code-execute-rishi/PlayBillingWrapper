package com.playbillingwrapper.listener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.playbillingwrapper.model.BillingResponse;
import com.playbillingwrapper.model.PurchaseInfo;
import com.playbillingwrapper.status.SubscriptionState;

/**
 * Single hook for billing analytics events. Implement to forward to Firebase, Amplitude,
 * Mixpanel, etc. without scattering bridge code across every caller. All methods have
 * {@code default} no-op bodies so implementers only override the events they care about.
 * <p>
 * Pass an instance to {@code BillingConfig.Builder.analyticsListener(...)}. The wrapper
 * invokes each method at the semantically correct moment:
 * <ul>
 *     <li>{@link #onBeginCheckout} — user tapped a CTA, right before the Play dialog
 *         opens.</li>
 *     <li>{@link #onPurchaseCompleted} — a grant callback fired
 *         ({@code onLifetimePurchased} / {@code onSubscriptionActivated} /
 *         {@code onConsumablePurchased}).</li>
 *     <li>{@link #onTrialStarted} — a subscription with a free-trial offer was just
 *         activated.</li>
 *     <li>{@link #onSubscriptionCancelled} — Play reports an auto-renew true→false
 *         transition.</li>
 *     <li>{@link #onUserCancelled} — user dismissed the Play dialog before paying.</li>
 *     <li>{@link #onError} — any error surfaced through {@code WrapperListener#onError}.</li>
 * </ul>
 *
 * <h2>Event vs. grant semantics</h2>
 * {@link #onPurchaseCompleted} fires on the grant callback, which is idempotent across
 * app restarts. If you want to fire analytics on every paywall success (even duplicates
 * across sessions) hook it here. If you want a truly once-per-purchase signal hook it
 * in {@code WrapperListener} and call the analytics listener yourself.
 */
public interface BillingAnalytics {

    /** User tapped a purchase CTA; the Play dialog is about to launch. */
    default void onBeginCheckout(@NonNull String productId,
                                 @Nullable String basePlanId,
                                 @Nullable String offerId) { }

    /** A non-consumable or consumable one-time purchase completed. */
    default void onPurchaseCompleted(@NonNull String productId,
                                     @NonNull PurchaseInfo purchase) { }

    /**
     * A subscription was just activated. Fires alongside
     * {@code onPurchaseCompleted} -- use whichever is clearer for your taxonomy.
     */
    default void onSubscriptionActivated(@NonNull String productId,
                                         @NonNull SubscriptionState state,
                                         @NonNull PurchaseInfo purchase) { }

    /**
     * A subscription was activated with a free-trial offer. Fires once per
     * {@code purchaseToken}. {@code periodIso} is the trial length as ISO 8601
     * (e.g. {@code "P3D"}, {@code "P7D"}). Useful for funnel dashboards.
     */
    default void onTrialStarted(@NonNull String productId,
                                @Nullable String periodIso,
                                @NonNull PurchaseInfo purchase) { }

    /**
     * A subscription was activated with an intro-pricing offer (e.g. "$1 first week,
     * then $19/year"). Fires once per {@code purchaseToken}. {@code periodIso} is the
     * intro phase billing period as ISO 8601 (e.g. {@code "P1W"}, {@code "P1M"}).
     * {@code billingCycleCount} is how many times the intro phase repeats before the
     * recurring phase kicks in (often 1 but can be N).
     */
    default void onIntroStarted(@NonNull String productId,
                                @Nullable String periodIso,
                                int billingCycleCount,
                                @NonNull PurchaseInfo purchase) { }

    /** Auto-renewing flipped from true to false (fires once per transition). */
    default void onSubscriptionCancelled(@NonNull String productId,
                                         @NonNull PurchaseInfo purchase) { }

    /** Consumable purchase confirmed and consumed on Play's side. */
    default void onConsumablePurchased(@NonNull String productId,
                                       int quantity,
                                       @NonNull PurchaseInfo purchase) { }

    /** User dismissed the Play dialog without completing the purchase. */
    default void onUserCancelled(@NonNull String productId) { }

    /** Any error surfaced through the wrapper's error callback. */
    default void onError(@NonNull String productId,
                         @NonNull BillingResponse response) { }
}
