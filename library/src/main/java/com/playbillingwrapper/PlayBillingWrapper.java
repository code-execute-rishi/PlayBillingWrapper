package com.playbillingwrapper;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;

import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;

import com.playbillingwrapper.listener.BillingEventListener;
import com.playbillingwrapper.listener.WrapperListener;
import com.playbillingwrapper.model.BillingConfig;
import com.playbillingwrapper.model.BillingResponse;
import com.playbillingwrapper.model.ProductInfo;
import com.playbillingwrapper.model.PurchaseInfo;
import com.playbillingwrapper.status.SubscriptionState;
import com.playbillingwrapper.type.ErrorType;
import com.playbillingwrapper.type.ProductType;
import com.playbillingwrapper.type.SkuProductType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * High-level facade over {@link BillingConnector}, specialised for three product shapes:
 * <ol>
 *     <li>Lifetime one-time unlock (non-consumable)</li>
 *     <li>Monthly subscription (no trial)</li>
 *     <li>Yearly subscription with a free trial (auto-picked when the user is eligible)</li>
 * </ol>
 * Usage:
 * <pre>
 * BillingConfig cfg = BillingConfig.builder()
 *     .lifetimeProductId("com.foo.lifetime")
 *     .monthlySubProductId("com.foo.monthly")
 *     .monthlyBasePlanId("monthly")
 *     .yearlySubProductId("com.foo.yearly")
 *     .yearlyBasePlanId("yearly")
 *     .yearlyTrialOfferId("freetrial")       // optional
 *     .userId(hashStableUserId(myUserId))    // required for fraud/trial enforcement
 *     .enableLogging(BuildConfig.DEBUG)
 *     .build();
 *
 * PlayBillingWrapper billing = new PlayBillingWrapper(application, cfg, myListener);
 * billing.connect();
 *
 * // later, from an Activity:
 * billing.buyLifetime(activity);
 * billing.subscribeMonthly(activity);
 * billing.subscribeYearlyWithTrial(activity);
 * </pre>
 * The wrapper operates entirely locally — no backend, no Real-Time Developer Notifications.
 * Subscription state is computed from the {@link Purchase} objects Play returns.
 */
public final class PlayBillingWrapper implements BillingEventListener {

    private final BillingConnector connector;
    private final BillingConfig config;
    private final IdempotencyStore idemStore;
    private WrapperListener listener;

    /**
     * @param context  must be an Application (or anything from which {@code getApplicationContext()} yields one).
     * @param config   product ids + user id + feature toggles.
     * @param listener callback surface — may be null, set later via {@link #setListener(WrapperListener)}.
     */
    public PlayBillingWrapper(@NonNull Context context,
                              @NonNull BillingConfig config,
                              @Nullable WrapperListener listener) {
        this(context, config, listener, null);
    }

    /**
     * Optional constructor that ties the connector lifetime to a provided {@link Lifecycle}
     * (typically {@code ProcessLifecycleOwner.get().getLifecycle()}).
     */
    public PlayBillingWrapper(@NonNull Context context,
                              @NonNull BillingConfig config,
                              @Nullable WrapperListener listener,
                              @Nullable Lifecycle lifecycle) {
        Context app = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
        this.idemStore = new IdempotencyStore(app);

        // Pass the key through as-is; BillingConnector skips signature verification when it is
        // null or empty (the Play-recommended posture is to verify on your server anyway).
        this.connector = new BillingConnector(app, config.base64LicenseKey, lifecycle);

        List<String> nonConsumables = new ArrayList<>();
        if (config.lifetimeProductId != null) nonConsumables.add(config.lifetimeProductId);

        List<String> subs = new ArrayList<>();
        if (config.monthlySubProductId != null) subs.add(config.monthlySubProductId);
        if (config.yearlySubProductId != null) subs.add(config.yearlySubProductId);

        if (!nonConsumables.isEmpty()) connector.setNonConsumableIds(nonConsumables);
        if (!subs.isEmpty()) connector.setSubscriptionIds(subs);

        if (config.autoAcknowledge) connector.autoAcknowledge();
        if (config.enableLogging) connector.enableLogging();

        if (config.obfuscatedAccountId != null) connector.setObfuscatedAccountId(config.obfuscatedAccountId);
        if (config.obfuscatedProfileId != null) connector.setObfuscatedProfileId(config.obfuscatedProfileId);

        connector.setBillingEventListener(this);
    }

    /** Replace the active listener. */
    public void setListener(@Nullable WrapperListener listener) {
        this.listener = listener;
    }

    /** Open the connection to Google Play. Call once per process (or after {@link #release()}). */
    public void connect() {
        connector.connect();
    }

    /** Release the underlying BillingClient. Safe to call multiple times. */
    public void release() {
        connector.release();
    }

    /** Re-query purchases. Useful after {@code onResume()} or a pull-to-refresh. */
    public void restorePurchases() {
        connector.connect();
    }

    // ---------------------------------------------------------------------
    //  Purchase entry points
    // ---------------------------------------------------------------------

    /** Launch the Play billing flow for the configured lifetime product. */
    public void buyLifetime(@NonNull Activity activity) {
        String id = config.lifetimeProductId;
        if (id == null) {
            reportError("No lifetimeProductId configured.");
            return;
        }
        connector.purchase(activity, id);
    }

    /** Launch the Play billing flow for the configured monthly subscription base plan. */
    public void subscribeMonthly(@NonNull Activity activity) {
        String id = config.monthlySubProductId;
        String basePlan = config.monthlyBasePlanId;
        if (id == null || basePlan == null) {
            reportError("Monthly subscription is not configured (productId/basePlanId missing).");
            return;
        }
        ProductDetails details = findProductDetails(id);
        if (details == null) {
            reportError("Product details not fetched yet for " + id + " — did you call connect()?");
            return;
        }
        String token = OfferSelector.pick(details, basePlan, null, /*preferTrial=*/false);
        if (token == null) {
            reportError("No offer token resolved for monthly subscription " + id +
                    " (basePlanId=" + basePlan + ").");
            return;
        }
        connector.purchaseSubscription(activity, id, token);
    }

    /**
     * Launch the Play billing flow for the yearly subscription. If the user is still trial-
     * eligible (Play determines this — ineligible offers are silently omitted) and a free-
     * trial phase exists on the base plan, that offer is selected automatically. Otherwise
     * the base plan offer is used.
     */
    public void subscribeYearlyWithTrial(@NonNull Activity activity) {
        String id = config.yearlySubProductId;
        String basePlan = config.yearlyBasePlanId;
        if (id == null || basePlan == null) {
            reportError("Yearly subscription is not configured (productId/basePlanId missing).");
            return;
        }
        ProductDetails details = findProductDetails(id);
        if (details == null) {
            reportError("Product details not fetched yet for " + id + " — did you call connect()?");
            return;
        }
        String token = OfferSelector.pick(details, basePlan, config.yearlyTrialOfferId, /*preferTrial=*/true);
        if (token == null) {
            reportError("No offer token resolved for yearly subscription " + id +
                    " (basePlanId=" + basePlan + ").");
            return;
        }
        connector.purchaseSubscription(activity, id, token);
    }

    /** Deep-link into Google Play to let the user manage/cancel a subscription. */
    public void openManageSubscription(@NonNull Activity activity, @NonNull String productId) {
        connector.openManageSubscription(activity, productId);
    }

    // ---------------------------------------------------------------------
    //  Queries
    // ---------------------------------------------------------------------

    /** True if the user currently owns the configured lifetime product. */
    public boolean hasLifetime() {
        if (config.lifetimeProductId == null) return false;
        for (PurchaseInfo p : connector.getPurchasedProductsList()) {
            if (config.lifetimeProductId.equals(p.getProduct()) && p.isPurchased()) return true;
        }
        return false;
    }

    /** True if the yearly subscription is still trial-eligible for the current Play account. */
    public boolean isTrialEligibleForYearly() {
        if (config.yearlySubProductId == null || config.yearlyBasePlanId == null) return false;
        ProductDetails details = findProductDetails(config.yearlySubProductId);
        if (details == null) return false;
        return OfferSelector.isTrialEligible(details, config.yearlyBasePlanId);
    }

    /** Current lifecycle state for the configured monthly subscription. */
    @NonNull
    public SubscriptionState monthlyState() {
        return subscriptionStateFor(config.monthlySubProductId);
    }

    /** Current lifecycle state for the configured yearly subscription. */
    @NonNull
    public SubscriptionState yearlyState() {
        return subscriptionStateFor(config.yearlySubProductId);
    }

    /** True if either the monthly or yearly subscription is currently entitling the user. */
    public boolean isSubscribed() {
        return hasEntitlement(monthlyState()) || hasEntitlement(yearlyState());
    }

    /** True if any of the three configured products is currently entitling the user. */
    public boolean isPremium() {
        return hasLifetime() || isSubscribed();
    }

    /** Read-only snapshot of every purchase the wrapper currently knows about. */
    @NonNull
    public List<PurchaseInfo> getOwnedPurchases() {
        return Collections.unmodifiableList(connector.getPurchasedProductsList());
    }

    /** The underlying connector. Exposed for advanced use cases; prefer the facade methods. */
    @NonNull
    public BillingConnector rawConnector() {
        return connector;
    }

    // ---------------------------------------------------------------------
    //  BillingEventListener -> WrapperListener bridge
    // ---------------------------------------------------------------------

    @Override
    public void onProductsFetched(@NonNull List<ProductInfo> productDetails) {
        WrapperListener l = listener;
        if (l != null) l.onReady();
    }

    @Override
    public void onPurchasedProductsFetched(@NonNull ProductType productType, @NonNull List<PurchaseInfo> purchases) {
        dispatchPurchases(purchases);
    }

    @Override
    public void onProductsPurchased(@NonNull List<PurchaseInfo> purchases) {
        dispatchPurchases(purchases);
    }

    @Override
    public void onPurchaseAcknowledged(@NonNull PurchaseInfo purchase) {
        idemStore.markHandled(purchase.getPurchaseToken());
    }

    @Override
    public void onPurchaseConsumed(@NonNull PurchaseInfo purchase) {
        idemStore.markHandled(purchase.getPurchaseToken());
    }

    @Override
    public void onBillingError(@NonNull BillingConnector billingConnector, @NonNull BillingResponse response) {
        WrapperListener l = listener;
        if (l == null) return;
        if (response.getErrorType() == ErrorType.USER_CANCELED) {
            l.onUserCancelled();
        } else {
            l.onError(response);
        }
    }

    @Override
    public void onProductQueryError(@NonNull String productId, @NonNull BillingResponse response) {
        WrapperListener l = listener;
        if (l != null) l.onError(response);
    }

    // ---------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------

    private void dispatchPurchases(List<PurchaseInfo> purchases) {
        WrapperListener l = listener;
        if (l == null) return;

        for (PurchaseInfo p : purchases) {
            if (p.isPending()) {
                l.onPending(p);
                continue;
            }
            if (!p.isPurchased()) continue;
            if (idemStore.isHandled(p.getPurchaseToken())) continue;

            if (p.getSkuProductType() == SkuProductType.SUBSCRIPTION) {
                SubscriptionState state = computeSubscriptionState(p);
                l.onSubscriptionActivated(p.getProduct(), state, p);
            } else {
                l.onLifetimePurchased(p);
            }
            if (p.isAcknowledged()) idemStore.markHandled(p.getPurchaseToken());
        }
    }

    @Nullable
    private ProductDetails findProductDetails(@Nullable String productId) {
        if (productId == null) return null;
        ProductInfo info = connector.findFetchedProductInfo(productId);
        return info == null ? null : info.getProductDetails();
    }

    @NonNull
    private SubscriptionState subscriptionStateFor(@Nullable String productId) {
        if (productId == null) return SubscriptionState.EXPIRED;

        for (PurchaseInfo p : connector.getPurchasedProductsList()) {
            if (!productId.equals(p.getProduct())) continue;
            if (p.isPending()) return SubscriptionState.PENDING;
            if (!p.isPurchased()) continue;
            return computeSubscriptionState(p);
        }
        return SubscriptionState.EXPIRED;
    }

    @NonNull
    private SubscriptionState computeSubscriptionState(@NonNull PurchaseInfo info) {
        Purchase p = info.getPurchase();
        if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) return SubscriptionState.PENDING;
        if (!p.isAutoRenewing()) return SubscriptionState.CANCELED_ACTIVE;
        if (isInFreeTrialPhase(info)) return SubscriptionState.IN_TRIAL;
        return SubscriptionState.ACTIVE;
    }

    private boolean isInFreeTrialPhase(@NonNull PurchaseInfo info) {
        ProductDetails details = info.getProductInfo().getProductDetails();
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null) return false;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
                if (phase.getPriceAmountMicros() == 0L) return true;
            }
        }
        return false;
    }

    private static boolean hasEntitlement(@NonNull SubscriptionState s) {
        switch (s) {
            case ACTIVE:
            case IN_TRIAL:
            case CANCELED_ACTIVE:
                return true;
            default:
                return false;
        }
    }

    private void reportError(String message) {
        WrapperListener l = listener;
        if (l == null) return;
        l.onError(new BillingResponse(ErrorType.DEVELOPER_ERROR, message, 99));
    }
}
