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
 *     reEngageScheduler.schedule(productId, billing.getTrialEndMillis(purchase));
 * }
 * </pre>
 */
public final class PlayBillingWrapper implements BillingEventListener {

    private final BillingConnector connector;
    private final BillingConfig config;
    private final IdempotencyStore idemStore;
    private final AutoRenewStateStore autoRenewStore;
    private WrapperListener listener;

    private final java.util.concurrent.atomic.AtomicLong lastRestoreAt = new java.util.concurrent.atomic.AtomicLong(0L);

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
        this.autoRenewStore = new AutoRenewStateStore(app);

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
        lastRestoreAt.set(System.currentTimeMillis());
    }

    /**
     * Splash / onboarding convenience. Starts a connection (if not already connected) and
     * invokes {@code callback} on the main thread as soon as {@link #isReady()} returns
     * true, OR after {@code timeoutMs} elapses -- whichever comes first. The callback
     * always fires exactly once.
     * <p>
     * Typical use: gate a "Continue" button during onboarding on billing being ready, but
     * fall through after 5s so a slow Play Services response doesn't block the app.
     *
     * <pre>
     * billing.connect(5_000L, () -&gt; {
     *     boolean ready = billing.isReady();
     *     continueButton.setEnabled(true);
     *     if (!ready) logTelemetry("billing_not_ready_timeout");
     * });
     * </pre>
     */
    public void connect(long timeoutMs, @NonNull Runnable callback) {
        connect();
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final Runnable[] tick = new Runnable[1];
        final boolean[] fired = { false };
        tick[0] = () -> {
            if (fired[0]) return;
            if (isReady() || System.currentTimeMillis() >= deadline) {
                fired[0] = true;
                callback.run();
            } else {
                handler.postDelayed(tick[0], 100L);
            }
        };
        handler.post(tick[0]);
    }

    public void release() {
        connector.release();
    }

    /** Force a full product + purchases refresh. */
    public void restorePurchases() {
        connector.connect();
        lastRestoreAt.set(System.currentTimeMillis());
    }

    /**
     * Throttled refresh. Useful from {@code Activity.onResume()} to catch web-redeemed
     * promo codes, cross-device sync, etc. without hitting Play on every navigation.
     * <p>
     * Atomic: concurrent callers race on a compare-and-set of the last-refresh timestamp,
     * so only one caller dispatches per interval even under heavy navigation.
     *
     * @param minIntervalMs minimum millis between refreshes. Typical values: 30_000L for
     *                      onResume, 300_000L for a periodic background check.
     * @return {@code true} if this caller won the race and dispatched a refresh.
     */
    public boolean restorePurchases(long minIntervalMs) {
        long now = System.currentTimeMillis();
        long previous = lastRestoreAt.get();
        if (now - previous < minIntervalMs) return false;
        if (!lastRestoreAt.compareAndSet(previous, now)) return false;
        connector.connect();
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
        purchaseProduct(activity, productId, null);
    }

    /**
     * Play Billing v8 supports multiple purchase options per one-time product (e.g. a
     * standard lifetime and a launch-discount lifetime as two options on the same SKU).
     * Pass the {@code purchaseOptionId} configured in Play Console to route to a
     * specific option; pass {@code null} to use the default option.
     */
    public void purchaseProduct(@NonNull Activity activity,
                                @NonNull String productId,
                                @Nullable String purchaseOptionId) {
        if (!config.lifetimeProductIds.contains(productId)) {
            reportError("purchaseProduct(): '" + productId + "' is not a registered lifetime product id.");
            return;
        }
        analytics(a -> a.onBeginCheckout(productId, null, purchaseOptionId));
        if (purchaseOptionId == null) {
            connector.purchase(activity, productId);
        } else {
            connector.purchase(activity, productId, purchaseOptionId);
        }
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
        analytics(a -> a.onBeginCheckout(productId, null, null));
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
        analytics(a -> a.onBeginCheckout(spec.productId, spec.basePlanId, spec.preferredOfferId));
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
     * Subscription state for {@code productId}. Play's client {@code Purchase} model
     * does not expose which base plan a user is on, so this overload is an alias for
     * {@link #subscriptionState(String)} kept for API symmetry with {@code getPricingPhases}
     * etc. The {@code basePlanId} argument is ignored. A Play subscription product has
     * at most one active purchase per account, so the product-level state is correct
     * from the client's perspective. For per-base-plan distinctions you need the
     * Google Play Developer API.
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

    /**
     * Convenience: true if ANY registered {@link SubscriptionSpec} for {@code productId}
     * has an eligible free-trial offer. Useful when the caller doesn't care about the
     * specific base plan -- 90% of paywalls want "can this SKU trial at all".
     */
    public boolean isTrialEligible(@NonNull String productId) {
        for (SubscriptionSpec spec : config.subscriptions) {
            if (!spec.productId.equals(productId)) continue;
            if (isTrialEligible(spec.productId, spec.basePlanId)) return true;
        }
        return false;
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
     * Estimated trial-end wall clock millis for a subscription purchase on a specific
     * {@code basePlanId}. Pass the base plan the user actually bought -- Play's client
     * {@code Purchase} does not expose which one was selected, so the caller must know.
     * Returns {@code -1} if the purchase is not a subscription, is in PENDING, lacks a
     * ProductInfo (legacy / inactive SKU), or has no trial offer on the given base plan.
     * Client-side estimate; authoritative expiry comes from the Play Developer API.
     */
    public long getTrialEndMillis(@NonNull PurchaseInfo purchase, @NonNull String basePlanId) {
        if (purchase.getSkuProductType() != SkuProductType.SUBSCRIPTION) return -1;
        if (!purchase.isPurchased()) return -1;
        ProductInfo info = purchase.getProductInfo();
        if (info == null) return -1;
        ProductDetails details = info.getProductDetails();
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null) return -1;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (!basePlanId.equals(offer.getBasePlanId())) continue;
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

    /**
     * Convenience overload that scans every registered {@link SubscriptionSpec} for
     * {@code purchase.getProduct()} and returns the first trial-end estimate it can
     * compute. Ambiguous when a product has multiple base plans with different trial
     * lengths -- prefer {@link #getTrialEndMillis(PurchaseInfo, String)} in that case.
     */
    public long getTrialEndMillis(@NonNull PurchaseInfo purchase) {
        for (SubscriptionSpec spec : config.subscriptions) {
            if (!spec.productId.equals(purchase.getProduct())) continue;
            long est = getTrialEndMillis(purchase, spec.basePlanId);
            if (est > 0) return est;
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    //  Intro-pricing queries (e.g. "$1 first week, then $19/yr")
    // ---------------------------------------------------------------------

    /**
     * True if the given subscription base plan has at least one eligible offer with an
     * intro pricing phase (non-zero price, {@code FINITE_RECURRING}). Play silently omits
     * ineligible offers, so this is also the correct "has this account redeemed the intro"
     * eligibility check.
     */
    public boolean isIntroEligible(@NonNull String productId, @NonNull String basePlanId) {
        ProductDetails details = findProductDetails(productId);
        if (details == null) return false;
        return OfferSelector.isIntroEligible(details, basePlanId);
    }

    /**
     * Convenience: true if ANY registered {@link SubscriptionSpec} for {@code productId}
     * has an eligible intro-pricing offer.
     */
    public boolean isIntroEligible(@NonNull String productId) {
        for (SubscriptionSpec spec : config.subscriptions) {
            if (!spec.productId.equals(productId)) continue;
            if (isIntroEligible(spec.productId, spec.basePlanId)) return true;
        }
        return false;
    }

    /**
     * Returns the first intro pricing phase on {@code (productId, basePlanId)} as the
     * library's typed wrapper, or {@code null} if no intro offer is eligible. Walks the
     * offer chosen by the registered {@link SubscriptionSpec} (preferredOfferId > trial >
     * base plan), so callers that registered their intro offer via
     * {@link SubscriptionSpec#withIntro} get the exact phase they configured.
     * <p>
     * Useful for paywall labels like {@code phase.getFormattedPrice() + " for " +
     * phase.getBillingCycleCount() + " " + phase.getPeriodIso()}.
     */
    @Nullable
    public com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases getIntroPhase(
            @NonNull String productId, @NonNull String basePlanId) {
        List<com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases> phases =
                getOfferPhases(productId, basePlanId);
        if (phases == null) return null;
        for (com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases p : phases) {
            if (p.isIntro()) return p;
        }
        return null;
    }

    /**
     * ISO 8601 billing period of the first intro pricing phase, e.g. {@code "P1W"} for
     * "$1 first week". Returns {@code null} if no intro offer is eligible.
     */
    @Nullable
    public String getIntroPeriodIso(@NonNull String productId, @NonNull String basePlanId) {
        com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases phase =
                getIntroPhase(productId, basePlanId);
        return phase == null ? null : phase.getPeriodIso();
    }

    /**
     * Estimated wall-clock end of the intro pricing phase for a subscription purchase on
     * the given {@code basePlanId}, computed as
     * {@code purchaseTime + (introPeriodMs * billingCycleCount)}. Pass the base plan the
     * user actually bought. Returns {@code -1} if the purchase is not a subscription, is
     * PENDING, lacks a ProductInfo, or has no intro offer on the given base plan.
     * <p>
     * Client-side estimate; authoritative phase transitions are only available via the
     * Play Developer API server-side. Months approximated as 30 days, years as 365 days.
     */
    public long getIntroEndMillis(@NonNull PurchaseInfo purchase, @NonNull String basePlanId) {
        if (purchase.getSkuProductType() != SkuProductType.SUBSCRIPTION) return -1;
        if (!purchase.isPurchased()) return -1;
        ProductInfo info = purchase.getProductInfo();
        if (info == null) return -1;
        ProductDetails details = info.getProductDetails();
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null) return -1;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (!basePlanId.equals(offer.getBasePlanId())) continue;
            if (offer.getOfferId() == null) continue;
            for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
                if (phase.getPriceAmountMicros() > 0L
                        && phase.getRecurrenceMode() == ProductDetails.RecurrenceMode.FINITE_RECURRING) {
                    long periodMs = parseIso8601DurationMillis(phase.getBillingPeriod());
                    if (periodMs <= 0) return -1;
                    int cycles = Math.max(1, phase.getBillingCycleCount());
                    return purchase.getPurchaseTime() + periodMs * cycles;
                }
            }
        }
        return -1;
    }

    /**
     * Convenience overload that scans every registered {@link SubscriptionSpec} for
     * {@code purchase.getProduct()} and returns the first intro-end estimate it can
     * compute. Ambiguous when a product has multiple base plans -- prefer
     * {@link #getIntroEndMillis(PurchaseInfo, String)} in that case.
     */
    public long getIntroEndMillis(@NonNull PurchaseInfo purchase) {
        for (SubscriptionSpec spec : config.subscriptions) {
            if (!spec.productId.equals(purchase.getProduct())) continue;
            long est = getIntroEndMillis(purchase, spec.basePlanId);
            if (est > 0) return est;
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
     * <p>
     * Note: for intro-pricing offers the first non-trial phase is the intro price (e.g.
     * $1), not the recurring price. Prefer {@link #getRecurringPrice(String, String)}
     * when you want the renewal price and {@link #getIntroPrice(String, String)} when
     * you want the intro price.
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
     * Formatted price of the intro pricing phase on {@code (productId, basePlanId)}
     * (e.g. {@code "$1.00"} for a "first week at $1" offer). Returns {@code null} if
     * no intro offer is eligible / configured.
     */
    @Nullable
    public String getIntroPrice(@NonNull String productId, @NonNull String basePlanId) {
        com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases phase =
                getIntroPhase(productId, basePlanId);
        return phase == null ? null : phase.getFormattedPrice();
    }

    /**
     * Formatted price of the recurring (INFINITE_RECURRING) pricing phase on
     * {@code (productId, basePlanId)} -- i.e. the renewal price the user pays after any
     * trial or intro phase ends. Returns {@code null} if no offer is available yet.
     */
    @Nullable
    public String getRecurringPrice(@NonNull String productId, @NonNull String basePlanId) {
        List<com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases> phases =
                getOfferPhases(productId, basePlanId);
        if (phases == null || phases.isEmpty()) return null;
        for (com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases p : phases) {
            if (p.isRecurring()) return p.getFormattedPrice();
        }
        return phases.get(phases.size() - 1).getFormattedPrice();
    }

    /**
     * Every pricing phase of the best offer on {@code (productId, basePlanId)} (trial auto-
     * preferred when eligible, otherwise the base plan offer). Returns {@code null} if
     * products haven't fetched yet. Useful for intro-pricing UI:
     * {@code "Free for 3 days, then $3.99/month"}.
     * <p>
     * Returns the library's typed {@link com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases}
     * wrapper so your paywall does not import {@code com.android.billingclient.api.ProductDetails.PricingPhase}
     * directly. The wrapper exposes {@code isFree()} / {@code isIntro()} /
     * {@code isRecurring()} / {@code getPeriodIso()} / {@code getPeriodDurationMillis()} helpers.
     */
    @Nullable
    public List<com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases> getOfferPhases(
            @NonNull String productId, @NonNull String basePlanId) {
        List<ProductDetails.PricingPhase> raw = getPricingPhases(productId, basePlanId);
        if (raw == null) return null;
        List<com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases> out = new java.util.ArrayList<>(raw.size());
        for (ProductDetails.PricingPhase p : raw) {
            out.add(new com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases(
                    p.getFormattedPrice(),
                    p.getPriceAmountMicros(),
                    p.getPriceCurrencyCode(),
                    p.getBillingPeriod(),
                    p.getBillingCycleCount(),
                    p.getRecurrenceMode()));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * @deprecated Prefer {@link #getOfferPhases(String, String)} which returns the
     * library's typed wrapper and exposes {@code isFree()} / {@code isIntro()} /
     * {@code isRecurring()} helpers. This method leaks the Play SDK type.
     */
    @Deprecated
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

    /**
     * Single-match shortcut. Returns the currently-owned {@link PurchaseInfo} for
     * {@code productId}, or {@code null} if the user does not own it. For subscriptions
     * with multiple base plans (same product id), returns the first matching purchase
     * in Play's order -- use {@link #getOwnedPurchases()} when you need to disambiguate.
     */
    @Nullable
    public PurchaseInfo getOwnedPurchase(@NonNull String productId) {
        for (PurchaseInfo p : connector.getPurchasedProductsList()) {
            if (productId.equals(p.getProduct())) return p;
        }
        return null;
    }

    @NonNull
    public BillingConnector rawConnector() { return connector; }

    @NonNull
    public BillingConfig getConfig() { return config; }

    /**
     * Exposes the dedupe ledger so callers can manually {@code forget(purchaseToken)}
     * after a refund / chargeback (so a future re-purchase with a recycled token is
     * delivered fresh) or reset the ledger in tests.
     */
    @NonNull
    public IdempotencyStore getIdempotencyStore() { return idemStore; }

    /**
     * Invoke a {@link com.playbillingwrapper.listener.BillingAnalytics} hook if one is
     * registered. No-op otherwise. Uses a tiny local functional interface instead of
     * {@code java.util.function.Consumer} because that requires API 24 and our minSdk
     * is 23 (core-library desugaring is not assumed in consumer apps).
     */
    private interface AnalyticsAction {
        void invoke(@NonNull com.playbillingwrapper.listener.BillingAnalytics a);
    }

    private void analytics(@NonNull AnalyticsAction action) {
        com.playbillingwrapper.listener.BillingAnalytics a = config.analyticsListener;
        if (a != null) action.invoke(a);
    }

    // ---------------------------------------------------------------------
    //  BillingEventListener bridge
    // ---------------------------------------------------------------------

    @Override
    public void onProductsFetched(@NonNull List<ProductInfo> productDetails) {
        WrapperListener l = listener;
        if (l != null) l.onProductsFetched(productDetails);
    }

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
        analytics(a -> a.onConsumablePurchased(purchase.getProduct(), purchase.getQuantity(), purchase));
        if (l == null) return;
        // Consumable was bought AND consumed on Play's side. Grant the resource now.
        // NOTE: idempotency ledger uses the Play purchaseToken; a second purchase of the same
        // SKU generates a fresh token, so this callback fires per transaction as expected.
        l.onConsumablePurchased(purchase.getProduct(), purchase.getQuantity(), purchase);
    }

    @Override
    public void onBillingError(@NonNull BillingConnector billingConnector, @NonNull BillingResponse response) {
        WrapperListener l = listener;
        if (response.getErrorType() == ErrorType.USER_CANCELED) {
            analytics(a -> a.onUserCancelled(""));
            if (l != null) l.onUserCancelled();
        } else {
            analytics(a -> a.onError("", response));
            if (l != null) l.onError(response);
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
        analytics(a -> a.onBeginCheckout(spec.productId, spec.basePlanId, spec.preferredOfferId));
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

            // Subscription-cancellation edge: fire ONLY on a true-to-false transition of
            // isAutoRenewing(). Skips first-load observations of already-cancelled
            // subscriptions and skips prepaid plans (non-renewing by definition). The store
            // persists the last-seen auto-renew bit per purchaseToken across app restarts.
            if (p.getSkuProductType() == SkuProductType.SUBSCRIPTION) {
                boolean isAutoRenewing = p.getPurchase().isAutoRenewing();
                boolean transitioned = autoRenewStore.recordAndDetectCancellation(
                        p.getPurchaseToken(), isAutoRenewing);
                if (transitioned) {
                    l.onSubscriptionCancelled(p.getProduct(), p);
                    analytics(a -> a.onSubscriptionCancelled(p.getProduct(), p));
                }
            }

            // First-time delivery dedupe.
            if (idemStore.isHandled(p.getPurchaseToken())) continue;
            idemStore.markHandled(p.getPurchaseToken());

            if (p.getSkuProductType() == SkuProductType.SUBSCRIPTION) {
                SubscriptionState state = computeSubscriptionState(p);
                l.onSubscriptionActivated(p.getProduct(), state, p);
                analytics(a -> a.onSubscriptionActivated(p.getProduct(), state, p));
                analytics(a -> a.onPurchaseCompleted(p.getProduct(), p));
                // Trial-started event fires when the purchase has a free-phase trial
                // and the purchase was just minted (first-time delivery path).
                long trialEndMs = getTrialEndMillis(p);
                if (trialEndMs > 0L) {
                    // Recover ISO period so callers get the raw string too.
                    String iso = null;
                    for (SubscriptionSpec s : config.subscriptions) {
                        if (s.productId.equals(p.getProduct())) {
                            iso = getTrialPeriodIso(s.productId, s.basePlanId);
                            if (iso != null) break;
                        }
                    }
                    final String isoFinal = iso;
                    analytics(a -> a.onTrialStarted(p.getProduct(), isoFinal, p));
                } else {
                    // Intro-started event: only fires when the purchase has no trial phase
                    // but does have an intro phase. Trial and intro are mutually exclusive
                    // from the "which event should I log" perspective -- a trial-then-intro
                    // offer still logs as onTrialStarted, which matches funnel conventions.
                    long introEndMs = getIntroEndMillis(p);
                    if (introEndMs > 0L) {
                        String iso = null;
                        int cycles = 1;
                        for (SubscriptionSpec s : config.subscriptions) {
                            if (!s.productId.equals(p.getProduct())) continue;
                            com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases phase =
                                    getIntroPhase(s.productId, s.basePlanId);
                            if (phase != null) {
                                iso = phase.getPeriodIso();
                                cycles = Math.max(1, phase.getBillingCycleCount());
                                break;
                            }
                        }
                        final String isoFinal = iso;
                        final int cyclesFinal = cycles;
                        analytics(a -> a.onIntroStarted(p.getProduct(), isoFinal, cyclesFinal, p));
                    }
                }
            } else {
                l.onLifetimePurchased(p);
                analytics(a -> a.onPurchaseCompleted(p.getProduct(), p));
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
        // PAUSED is checked BEFORE isAutoRenewing because Play keeps auto-renew=true for
        // paused subs (they resume automatically at the paused-until date).
        if (p.isSuspended()) return SubscriptionState.PAUSED;
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
    public static long parseIso8601DurationMillis(@Nullable String iso) {
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
