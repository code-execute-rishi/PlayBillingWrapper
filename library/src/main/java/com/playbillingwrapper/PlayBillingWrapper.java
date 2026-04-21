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
import com.playbillingwrapper.model.SubscriptionSpec;
import com.playbillingwrapper.status.SubscriptionState;
import com.playbillingwrapper.type.ErrorType;
import com.playbillingwrapper.type.ProductType;
import com.playbillingwrapper.type.SkuProductType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * High-level facade over {@link BillingConnector}.
 * <p>
 * Supports arbitrary catalogs: any number of non-consumable "lifetime" products and any
 * number of {@link SubscriptionSpec} (product + base plan) entries, each optionally
 * preferring a free-trial offer or an explicit promo offer id. Use the generic
 * {@link #purchaseProduct(Activity, String)} and
 * {@link #subscribe(Activity, String, String)} methods for anything beyond the three-shape
 * convenience layer.
 * <p>
 * Backward compatibility: {@link #buyLifetime(Activity)}, {@link #subscribeMonthly(Activity)},
 * and {@link #subscribeYearlyWithTrial(Activity)} are still exposed as sugar that forwards
 * to the generic API using the {@code default*} fields from {@link BillingConfig}.
 *
 * <h2>Examples</h2>
 * <pre>
 * // Six-SKU catalog with a discount lifetime + monthly trial + yearly no-trial.
 * BillingConfig cfg = BillingConfig.builder()
 *     .addLifetimeProductId("com.app.pro_lifetime")
 *     .addLifetimeProductId("com.app.pro_lifetime_launch")
 *     .addLifetimeProductId("com.app.remove_ads")
 *     .addSubscription(SubscriptionSpec.withTrial("com.app.premium", "monthly"))
 *     .addSubscription(SubscriptionSpec.of("com.app.premium", "yearly"))
 *     .userId(sha256(myUserId)).build();
 *
 * PlayBillingWrapper billing = new PlayBillingWrapper(app, cfg, listener);
 * billing.connect();
 *
 * // From the paywall coordinator:
 * billing.purchaseProduct(activity, chosenLifetimeSku);
 * billing.subscribe(activity, "com.app.premium", "monthly");   // auto-picks trial offer
 * billing.subscribe(activity, "com.app.premium", "yearly", false); // no trial
 *
 * // State queries at any time:
 * billing.isOwned("com.app.remove_ads");          // legacy SKU check
 * billing.subscriptionState("com.app.premium", "monthly");
 * billing.isTrialEligible("com.app.premium", "monthly");
 * billing.getTrialPeriodIso("com.app.premium", "monthly");   // "P3D"
 * billing.getTrialEndMillis(purchase);                       // absolute expiry
 *
 * // Throttled restore on app resume:
 * billing.restorePurchases(30_000L);   // only if 30s+ since last restore
 *
 * // Cancellation hook:
 * @Override public void onSubscriptionCancelled(String productId, PurchaseInfo purchase) {
 *     reEngageScheduler.schedule(productId, billing.getSubscriptionExpiryMillis(purchase));
 * }
 * </pre>
 */
public final class PlayBillingWrapper implements BillingEventListener {

    private final BillingConnector connector;
    private final BillingConfig config;
    private final IdempotencyStore idemStore;
    private final IdempotencyStore cancelStore; // dedupe for onSubscriptionCancelled
    private WrapperListener listener;

    private volatile long lastRestoreAt = 0L;

    public PlayBillingWrapper(@NonNull Context context,
                              @NonNull BillingConfig config,
                              @Nullable WrapperListener listener) {
        this(context, config, listener, null);
    }

    public PlayBillingWrapper(@NonNull Context context,
                              @NonNull BillingConfig config,
                              @Nullable WrapperListener listener,
                              @Nullable Lifecycle lifecycle) {
        Context app = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
        this.idemStore = new IdempotencyStore(app);
        this.cancelStore = new IdempotencyStore(app, "pbw_cancel_idempotency");

        this.connector = new BillingConnector(app, config.base64LicenseKey, lifecycle);

        List<String> nonConsumables = new ArrayList<>(config.lifetimeProductIds);
        List<String> consumables = new ArrayList<>(config.consumableProductIds);

        Set<String> subIds = new HashSet<>();
        for (SubscriptionSpec spec : config.subscriptions) subIds.add(spec.productId);

        if (!nonConsumables.isEmpty()) connector.setNonConsumableIds(nonConsumables);
        if (!consumables.isEmpty()) connector.setConsumableIds(consumables);
        if (!subIds.isEmpty()) connector.setSubscriptionIds(new ArrayList<>(subIds));

        if (config.autoAcknowledge) connector.autoAcknowledge();
        if (!consumables.isEmpty()) connector.autoConsume();
        if (config.enableLogging) connector.enableLogging();

        if (config.obfuscatedAccountId != null) connector.setObfuscatedAccountId(config.obfuscatedAccountId);
        if (config.obfuscatedProfileId != null) connector.setObfuscatedProfileId(config.obfuscatedProfileId);

        connector.setBillingEventListener(this);
    }

    // ---------------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------------

    public void setListener(@Nullable WrapperListener listener) {
        this.listener = listener;
    }

    public void connect() {
        connector.connect();
        lastRestoreAt = System.currentTimeMillis();
    }

    public void release() {
        connector.release();
    }

    /** Force a full product + purchases refresh. */
    public void restorePurchases() {
        connector.connect();
        lastRestoreAt = System.currentTimeMillis();
    }

    /**
     * Throttled refresh. Useful from {@code Activity.onResume()} to catch web-redeemed
     * promo codes, cross-device sync, etc. without hitting Play on every navigation.
     *
     * @param minIntervalMs minimum millis between refreshes. Typical values: 30_000L for
     *                      onResume, 300_000L for a periodic background check.
     * @return {@code true} if a refresh was actually dispatched.
     */
    public boolean restorePurchases(long minIntervalMs) {
        long now = System.currentTimeMillis();
        if (now - lastRestoreAt < minIntervalMs) return false;
        restorePurchases();
        return true;
    }

    // ---------------------------------------------------------------------
    //  Generic purchase API (works for any configured product)
    // ---------------------------------------------------------------------

    /**
     * Launch the Play flow for any configured non-consumable product (lifetime / launch
     * discount / legacy remove-ads / etc.). The id must appear in
     * {@link BillingConfig#lifetimeProductIds}.
     */
    public void purchaseProduct(@NonNull Activity activity, @NonNull String productId) {
        if (!config.lifetimeProductIds.contains(productId)) {
            reportError("purchaseProduct(): '" + productId + "' is not a registered lifetime product id.");
            return;
        }
        connector.purchase(activity, productId);
    }

    /**
     * Launch the Play flow for a consumable product (coins, gems, lives). The id must
     * appear in {@link BillingConfig#consumableProductIds}. Grant the in-game resource
     * from {@link WrapperListener#onConsumablePurchased} once Play confirms the consume.
     */
    public void purchaseConsumable(@NonNull Activity activity, @NonNull String productId) {
        if (!config.consumableProductIds.contains(productId)) {
            reportError("purchaseConsumable(): '" + productId + "' is not a registered consumable product id.");
            return;
        }
        connector.purchase(activity, productId);
    }

    /**
     * Upgrade / downgrade / swap an active subscription.
     *
     * @param activity         foreground Activity
     * @param oldProductId     currently-owned subscription product id (only used to look up the token)
     * @param newProductId     target subscription product id
     * @param newBasePlanId    target base plan id
     * @param mode             proration mode; see {@link com.playbillingwrapper.type.ChangeMode}
     */
    public void changeSubscription(@NonNull Activity activity,
                                   @NonNull String oldProductId,
                                   @NonNull String newProductId,
                                   @NonNull String newBasePlanId,
                                   @NonNull com.playbillingwrapper.type.ChangeMode mode) {
        String oldToken = null;
        for (PurchaseInfo p : connector.getPurchasedProductsList()) {
            if (oldProductId.equals(p.getProduct())
                    && p.getSkuProductType() == SkuProductType.SUBSCRIPTION
                    && p.isPurchased()) {
                oldToken = p.getPurchaseToken();
                break;
            }
        }
        if (oldToken == null) {
            reportError("changeSubscription(): no active subscription found for '" + oldProductId + "'");
            return;
        }

        SubscriptionSpec spec = findSpec(newProductId, newBasePlanId);
        if (spec == null) return;
        ProductDetails details = findProductDetails(spec.productId);
        if (details == null) {
            reportError("Product details not fetched yet for " + spec.productId + " -- did you call connect()?");
            return;
        }
        String offerToken = OfferSelector.pick(details, spec.basePlanId, spec.preferredOfferId, spec.preferTrial);
        if (offerToken == null) {
            reportError("No offer token resolved for " + spec.productId + " / " + spec.basePlanId);
            return;
        }
        connector.changeSubscription(activity, spec.productId, offerToken, oldToken, mode.playReplacementMode);
    }

    /**
     * Launch the Play flow for any registered subscription + base plan combination. The
     * wrapper picks the offer token using the matching {@link SubscriptionSpec} (trial
     * preference, optional explicit offer id). Pass (productId, basePlanId) that matches an
     * entry registered via {@link BillingConfig.Builder#addSubscription}.
     */
    public void subscribe(@NonNull Activity activity,
                          @NonNull String productId,
                          @NonNull String basePlanId) {
        subscribeInternal(activity, findSpec(productId, basePlanId));
    }

    /**
     * Like {@link #subscribe(Activity, String, String)} but lets the caller override the
     * trial preference for this one invocation. Useful when the registered spec says
     * {@code preferTrial=true} but the caller knows the user is ineligible.
     */
    public void subscribe(@NonNull Activity activity,
                          @NonNull String productId,
                          @NonNull String basePlanId,
                          boolean preferTrial) {
        SubscriptionSpec base = findSpec(productId, basePlanId);
        if (base == null) return;
        SubscriptionSpec override = SubscriptionSpec.builder()
                .productId(base.productId)
                .basePlanId(base.basePlanId)
                .preferTrial(preferTrial)
                .preferredOfferId(base.preferredOfferId)
                .tag(base.tag)
                .build();
        subscribeInternal(activity, override);
    }

    /**
     * Launch the Play flow with a specific {@link SubscriptionSpec}. Bypasses catalog
     * registration; useful for A/B tests that target a spec that was not known at
     * configuration time. The product id still needs to be registered in
     * {@link BillingConfig.Builder#addSubscription}, otherwise Play will return
     * "product not available".
     */
    public void subscribe(@NonNull Activity activity, @NonNull SubscriptionSpec spec) {
        subscribeInternal(activity, spec);
    }

    // ---------------------------------------------------------------------
    //  Sugar API (backward-compatible)
    // ---------------------------------------------------------------------

    public void buyLifetime(@NonNull Activity activity) {
        String id = config.defaultLifetimeProductId;
        if (id == null) {
            reportError("buyLifetime(): no defaultLifetimeProductId configured. " +
                    "Either call .defaultLifetimeProductId(...) on the builder or use purchaseProduct(activity, id).");
            return;
        }
        purchaseProduct(activity, id);
    }

    public void subscribeMonthly(@NonNull Activity activity) {
        if (config.defaultMonthlySpec == null) {
            reportError("subscribeMonthly(): no defaultMonthly(...) configured. Use subscribe(activity, productId, basePlanId) instead.");
            return;
        }
        subscribeInternal(activity, config.defaultMonthlySpec);
    }

    public void subscribeYearlyWithTrial(@NonNull Activity activity) {
        if (config.defaultYearlySpec == null) {
            reportError("subscribeYearlyWithTrial(): no defaultYearly(...) configured.");
            return;
        }
        subscribeInternal(activity, config.defaultYearlySpec);
    }

    public void openManageSubscription(@NonNull Activity activity, @NonNull String productId) {
        connector.openManageSubscription(activity, productId);
    }

    // ---------------------------------------------------------------------
    //  Ownership queries
    // ---------------------------------------------------------------------

    /** True if the given product id is currently owned and in PURCHASED state. */
    public boolean isOwned(@NonNull String productId) {
        for (PurchaseInfo p : connector.getPurchasedProductsList()) {
            if (productId.equals(p.getProduct()) && p.isPurchased()) return true;
        }
        return false;
    }

    /** True if the default lifetime product is owned. */
    public boolean hasLifetime() {
        return config.defaultLifetimeProductId != null && isOwned(config.defaultLifetimeProductId);
    }

    /**
     * Subscription state for a specific {@code (productId, basePlanId)}. Returns
     * {@link SubscriptionState#EXPIRED} if the user has no purchase for this product.
     */
    @NonNull
    public SubscriptionState subscriptionState(@NonNull String productId, @NonNull String basePlanId) {
        return subscriptionStateFor(productId);
    }

    /** Shortcut when you only need the productId-level state (first matching purchase wins). */
    @NonNull
    public SubscriptionState subscriptionState(@NonNull String productId) {
        return subscriptionStateFor(productId);
    }

    /** State for the default monthly spec, if configured. */
    @NonNull
    public SubscriptionState monthlyState() {
        return subscriptionStateFor(config.defaultMonthlySpec == null ? null : config.defaultMonthlySpec.productId);
    }

    /** State for the default yearly spec, if configured. */
    @NonNull
    public SubscriptionState yearlyState() {
        return subscriptionStateFor(config.defaultYearlySpec == null ? null : config.defaultYearlySpec.productId);
    }

    /**
     * True if the user currently owns any registered subscription that is entitling
     * (ACTIVE, CANCELED_ACTIVE).
     */
    public boolean isSubscribed() {
        Set<String> seen = new HashSet<>();
        for (SubscriptionSpec spec : config.subscriptions) {
            if (!seen.add(spec.productId)) continue;
            if (hasEntitlement(subscriptionStateFor(spec.productId))) return true;
        }
        return false;
    }

    /**
     * True if the user owns any entitling product -- lifetime or subscription. A single
     * call for "should we show premium features?".
     */
    public boolean isPremium() {
        for (String id : config.lifetimeProductIds) {
            if (isOwned(id)) return true;
        }
        return isSubscribed();
    }

    /**
     * The list of product ids the user currently holds an entitlement for. Useful for
     * analytics ("which SKU converted?") and for PaywallCoordinator routing.
     */
    @NonNull
    public List<String> getActiveEntitlements() {
        List<String> out = new ArrayList<>();
        for (PurchaseInfo p : connector.getPurchasedProductsList()) {
            if (p.isPending() || !p.isPurchased()) continue;
            if (p.getSkuProductType() == SkuProductType.SUBSCRIPTION) {
                SubscriptionState s = computeSubscriptionState(p);
                if (hasEntitlement(s)) out.add(p.getProduct());
            } else {
                out.add(p.getProduct());
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------------
    //  Trial + offer queries
    // ---------------------------------------------------------------------

    /**
     * True if the given subscription base plan has at least one eligible offer with a
     * free-trial pricing phase. Play silently omits ineligible offers from
     * {@code ProductDetails}, so this is the correct eligibility check.
     */
    public boolean isTrialEligible(@NonNull String productId, @NonNull String basePlanId) {
        ProductDetails details = findProductDetails(productId);
        if (details == null) return false;
        return OfferSelector.isTrialEligible(details, basePlanId);
    }

    /** Legacy: trial eligibility for the default yearly spec. */
    public boolean isTrialEligibleForYearly() {
        if (config.defaultYearlySpec == null) return false;
        return isTrialEligible(config.defaultYearlySpec.productId, config.defaultYearlySpec.basePlanId);
    }

    /**
     * Returns the free-trial billing period (ISO 8601, e.g. {@code "P3D"}, {@code "P7D"},
     * {@code "P14D"}) for the first trial offer on {@code (productId, basePlanId)}, or
     * {@code null} if no trial offer is configured / eligible.
     * <p>
     * Use the returned period to schedule a trial-ending reminder:
     * <pre>
     * String period = billing.getTrialPeriodIso(productId, basePlanId);
     * long trialEndMillis = billing.getTrialEndMillis(purchase);
     * reminderScheduler.at(trialEndMillis - HOUR);
     * </pre>
     */
    @Nullable
    public String getTrialPeriodIso(@NonNull String productId, @NonNull String basePlanId) {
        ProductDetails details = findProductDetails(productId);
        if (details == null) return null;
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null) return null;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (!basePlanId.equals(offer.getBasePlanId())) continue;
            if (offer.getOfferId() == null) continue;
            for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
                if (phase.getPriceAmountMicros() == 0L) {
                    return phase.getBillingPeriod();
                }
            }
        }
        return null;
    }

    /**
     * Estimated trial-end wall clock millis for a subscription purchase. Computes
     * {@code purchase.getPurchaseTime() + isoPeriodMillis(trialPeriod)}. Returns -1 if the
     * purchase is not a subscription, is in PENDING, or the product has no trial offer.
     * This is a client-side estimate; for authoritative expiry use the Play Developer API.
     */
    public long getTrialEndMillis(@NonNull PurchaseInfo purchase) {
        if (purchase.getSkuProductType() != SkuProductType.SUBSCRIPTION) return -1;
        if (!purchase.isPurchased()) return -1;
        ProductDetails details = purchase.getProductInfo().getProductDetails();
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null) return -1;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (offer.getOfferId() == null) continue;
            for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
                if (phase.getPriceAmountMicros() == 0L) {
                    long periodMs = parseIso8601DurationMillis(phase.getBillingPeriod());
                    if (periodMs <= 0) return -1;
                    return purchase.getPurchaseTime() + periodMs;
                }
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    //  Price + offer display helpers
    // ---------------------------------------------------------------------

    /**
     * Formatted price for a one-time product (lifetime or consumable), e.g. {@code "$4.99"}.
     * Returns {@code null} if products haven't fetched yet or the id isn't registered.
     * The string is Play-computed in the user's Play account country + currency -- show it
     * verbatim.
     */
    @Nullable
    public String getFormattedPrice(@NonNull String productId) {
        ProductDetails details = findProductDetails(productId);
        if (details == null) return null;
        ProductDetails.OneTimePurchaseOfferDetails one = details.getOneTimePurchaseOfferDetails();
        return one == null ? null : one.getFormattedPrice();
    }

    /**
     * Formatted price for the first (non-trial) pricing phase of a subscription offer on
     * {@code (productId, basePlanId)}. Returns {@code null} if no offer is available yet.
     * Use {@link #getPricingPhases(String, String)} if you need to render every phase
     * (intro + recurring) separately.
     */
    @Nullable
    public String getFormattedPrice(@NonNull String productId, @NonNull String basePlanId) {
        List<ProductDetails.PricingPhase> phases = getPricingPhases(productId, basePlanId);
        if (phases == null || phases.isEmpty()) return null;
        for (ProductDetails.PricingPhase p : phases) {
            if (p.getPriceAmountMicros() > 0L) return p.getFormattedPrice();
        }
        return phases.get(phases.size() - 1).getFormattedPrice();
    }

    /**
     * Every pricing phase of the best offer on {@code (productId, basePlanId)} (trial auto-
     * preferred when eligible, otherwise the base plan offer). Returns {@code null} if
     * products haven't fetched yet. Useful for intro-pricing UI:
     * {@code "Free for 3 days, then $3.99/month"}.
     */
    @Nullable
    public List<ProductDetails.PricingPhase> getPricingPhases(@NonNull String productId,
                                                              @NonNull String basePlanId) {
        ProductDetails details = findProductDetails(productId);
        if (details == null) return null;
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null) return null;

        SubscriptionSpec spec = findSpecOrNull(productId, basePlanId);
        String preferredOfferId = spec == null ? null : spec.preferredOfferId;
        boolean preferTrial = spec != null && spec.preferTrial;

        ProductDetails.SubscriptionOfferDetails chosen = null;
        if (preferredOfferId != null) {
            for (ProductDetails.SubscriptionOfferDetails o : offers) {
                if (basePlanId.equals(o.getBasePlanId())
                        && preferredOfferId.equals(o.getOfferId())) {
                    chosen = o;
                    break;
                }
            }
        }
        if (chosen == null && preferTrial) {
            for (ProductDetails.SubscriptionOfferDetails o : offers) {
                if (!basePlanId.equals(o.getBasePlanId())) continue;
                if (o.getOfferId() == null) continue;
                for (ProductDetails.PricingPhase ph : o.getPricingPhases().getPricingPhaseList()) {
                    if (ph.getPriceAmountMicros() == 0L) { chosen = o; break; }
                }
                if (chosen != null) break;
            }
        }
        if (chosen == null) {
            for (ProductDetails.SubscriptionOfferDetails o : offers) {
                if (basePlanId.equals(o.getBasePlanId()) && o.getOfferId() == null) {
                    chosen = o; break;
                }
            }
        }
        if (chosen == null) {
            for (ProductDetails.SubscriptionOfferDetails o : offers) {
                if (basePlanId.equals(o.getBasePlanId())) { chosen = o; break; }
            }
        }
        return chosen == null ? null : chosen.getPricingPhases().getPricingPhaseList();
    }

    @Nullable
    private SubscriptionSpec findSpecOrNull(@NonNull String productId, @NonNull String basePlanId) {
        for (SubscriptionSpec s : config.subscriptions) {
            if (s.productId.equals(productId) && s.basePlanId.equals(basePlanId)) return s;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    //  Connection state
    // ---------------------------------------------------------------------

    /** True if {@code BillingClient} is connected AND product details have been fetched. */
    public boolean isReady() {
        return connector.isReady();
    }

    /** True once both the INAPP and SUBS {@code queryPurchasesAsync} callbacks have completed. */
    public boolean isPurchaseReconciliationComplete() {
        return connector.isPurchaseReconciliationComplete();
    }

    // ---------------------------------------------------------------------
    //  Accessors
    // ---------------------------------------------------------------------

    @NonNull
    public List<PurchaseInfo> getOwnedPurchases() {
        return Collections.unmodifiableList(connector.getPurchasedProductsList());
    }

    @NonNull
    public BillingConnector rawConnector() { return connector; }

    @NonNull
    public BillingConfig getConfig() { return config; }

    // ---------------------------------------------------------------------
    //  BillingEventListener bridge
    // ---------------------------------------------------------------------

    @Override
    public void onProductsFetched(@NonNull List<ProductInfo> productDetails) { }

    @Override
    public void onPurchasedProductsFetched(@NonNull ProductType productType, @NonNull List<PurchaseInfo> purchases) {
        dispatchPurchases(purchases);
        if (connector.isPurchaseReconciliationComplete()) {
            WrapperListener l = listener;
            if (l != null) l.onReady();
        }
    }

    @Override
    public void onProductsPurchased(@NonNull List<PurchaseInfo> purchases) {
        dispatchPurchases(purchases);
    }

    @Override public void onPurchaseAcknowledged(@NonNull PurchaseInfo purchase) { }

    @Override
    public void onPurchaseConsumed(@NonNull PurchaseInfo purchase) {
        WrapperListener l = listener;
        if (l == null) return;
        // Consumable was bought AND consumed on Play's side. Grant the resource now.
        // NOTE: idempotency ledger uses the Play purchaseToken; a second purchase of the same
        // SKU generates a fresh token, so this callback fires per transaction as expected.
        l.onConsumablePurchased(purchase.getProduct(), purchase.getQuantity(), purchase);
    }

    @Override
    public void onBillingError(@NonNull BillingConnector billingConnector, @NonNull BillingResponse response) {
        WrapperListener l = listener;
        if (l == null) return;
        if (response.getErrorType() == ErrorType.USER_CANCELED) l.onUserCancelled();
        else l.onError(response);
    }

    @Override
    public void onProductQueryError(@NonNull String productId, @NonNull BillingResponse response) {
        WrapperListener l = listener;
        if (l != null) l.onError(response);
    }

    // ---------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------

    private void subscribeInternal(@NonNull Activity activity, @Nullable SubscriptionSpec spec) {
        if (spec == null) return;
        ProductDetails details = findProductDetails(spec.productId);
        if (details == null) {
            reportError("Product details not fetched yet for " + spec.productId + " -- did you call connect()?");
            return;
        }
        String token = OfferSelector.pick(details, spec.basePlanId, spec.preferredOfferId, spec.preferTrial);
        if (token == null) {
            reportError("No offer token resolved for subscription " + spec.productId +
                    " basePlan=" + spec.basePlanId + " (preferTrial=" + spec.preferTrial +
                    ", preferredOfferId=" + spec.preferredOfferId + ")");
            return;
        }
        connector.purchaseSubscription(activity, spec.productId, token);
    }

    @Nullable
    private SubscriptionSpec findSpec(@NonNull String productId, @NonNull String basePlanId) {
        for (SubscriptionSpec s : config.subscriptions) {
            if (s.productId.equals(productId) && s.basePlanId.equals(basePlanId)) return s;
        }
        reportError("No SubscriptionSpec registered for productId='" + productId +
                "' basePlanId='" + basePlanId + "'. Call .addSubscription(SubscriptionSpec.of(...)) on the builder.");
        return null;
    }

    private void dispatchPurchases(List<PurchaseInfo> purchases) {
        WrapperListener l = listener;
        if (l == null) return;

        for (PurchaseInfo p : purchases) {
            if (p.isPending()) {
                l.onPending(p);
                continue;
            }
            if (!p.isPurchased()) continue;

            // Subscription-cancellation edge: fires once per token when auto-renew flips off.
            if (p.getSkuProductType() == SkuProductType.SUBSCRIPTION
                    && !p.getPurchase().isAutoRenewing()) {
                String key = "cancel:" + p.getPurchaseToken();
                if (!cancelStore.isHandled(key)) {
                    cancelStore.markHandled(key);
                    l.onSubscriptionCancelled(p.getProduct(), p);
                }
            }

            // First-time delivery dedupe.
            if (idemStore.isHandled(p.getPurchaseToken())) continue;
            idemStore.markHandled(p.getPurchaseToken());

            if (p.getSkuProductType() == SkuProductType.SUBSCRIPTION) {
                l.onSubscriptionActivated(p.getProduct(), computeSubscriptionState(p), p);
            } else {
                l.onLifetimePurchased(p);
            }
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
        return SubscriptionState.ACTIVE;
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

    /**
     * Minimal ISO 8601 duration parser. Supports {@code PnY}, {@code PnM}, {@code PnW},
     * {@code PnD} (the forms Play uses for subscription billing periods). Returns -1 on
     * malformed input.
     */
    static long parseIso8601DurationMillis(@Nullable String iso) {
        if (iso == null || iso.length() < 3 || iso.charAt(0) != 'P') return -1;
        try {
            long n = Long.parseLong(iso.substring(1, iso.length() - 1));
            char unit = iso.charAt(iso.length() - 1);
            switch (unit) {
                case 'D': return n * 86_400_000L;
                case 'W': return n * 7L * 86_400_000L;
                case 'M': return n * 30L * 86_400_000L;   // Play uses calendar months; 30d is a sufficient client-side estimate
                case 'Y': return n * 365L * 86_400_000L;  // ditto
                default:  return -1;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void reportError(String message) {
        WrapperListener l = listener;
        if (l == null) return;
        l.onError(new BillingResponse(ErrorType.DEVELOPER_ERROR, message, 99));
    }
}
