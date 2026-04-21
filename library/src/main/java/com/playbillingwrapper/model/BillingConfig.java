package com.playbillingwrapper.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-app configuration for {@code PlayBillingWrapper}.
 * <p>
 * PlayBillingWrapper supports arbitrary product catalogs. Declare every non-consumable
 * (lifetime-style) product and every subscription base plan you sell, plus whichever you
 * want treated as the "default" lifetime / monthly / yearly for the three-method sugar API.
 *
 * <pre>
 * BillingConfig cfg = BillingConfig.builder()
 *     // Lifetime-style unlocks: put every non-consumable id you sell here.
 *     .addLifetimeProductId("com.app.pro_lifetime")
 *     .addLifetimeProductId("com.app.pro_lifetime_launch")   // discount SKU
 *     .addLifetimeProductId("com.app.remove_ads")            // legacy SKU
 *
 *     // Subscriptions: one spec per (productId, basePlanId) pair.
 *     .addSubscription(SubscriptionSpec.withTrial(
 *         "com.app.premium", "monthly"))                     // monthly with 3-day trial
 *     .addSubscription(SubscriptionSpec.of(
 *         "com.app.premium", "yearly"))                      // yearly, no trial
 *
 *     // Optional "default" ids so the sugar methods (buyLifetime, subscribeMonthly,
 *     // subscribeYearlyWithTrial) keep working. All optional; you can use only the
 *     // generic purchaseProduct / subscribe methods and ignore these.
 *     .defaultLifetimeProductId("com.app.pro_lifetime")
 *     .defaultMonthly("com.app.premium", "monthly")
 *     .defaultYearlyWithTrial("com.app.premium", "yearly")
 *
 *     .userId(sha256(myUserId))
 *     .build();
 * </pre>
 *
 * Back-compat shortcuts {@link Builder#lifetimeProductId}, {@link Builder#monthlySubProductId},
 * {@link Builder#yearlySubProductId} etc. still exist and wire up the same fields.
 */
public final class BillingConfig {

    // ------------------------------------------------------------------
    // Generic catalog
    // ------------------------------------------------------------------

    /** Every non-consumable (lifetime-style) product id the app sells. */
    @NonNull
    public final Set<String> lifetimeProductIds;

    /**
     * Every consumable product id the app sells (coins, gems, lives, energy refills).
     * Consumables are auto-consumed after successful delivery so the user can buy again.
     */
    @NonNull
    public final Set<String> consumableProductIds;

    /** Every subscription (productId + basePlanId) the app sells. */
    @NonNull
    public final List<SubscriptionSpec> subscriptions;

    // ------------------------------------------------------------------
    // Optional "default" bindings for the sugar API
    // ------------------------------------------------------------------

    /** Default lifetime id used by {@code buyLifetime(activity)}. */
    @Nullable public final String defaultLifetimeProductId;

    /** Default subscription used by {@code subscribeMonthly(activity)}. */
    @Nullable public final SubscriptionSpec defaultMonthlySpec;

    /** Default subscription used by {@code subscribeYearlyWithTrial(activity)}. */
    @Nullable public final SubscriptionSpec defaultYearlySpec;

    // ------------------------------------------------------------------
    // Account binding + Play lifecycle
    // ------------------------------------------------------------------

    @Nullable public final String obfuscatedAccountId;
    @Nullable public final String obfuscatedProfileId;
    @Nullable public final String base64LicenseKey;

    public final boolean enableLogging;
    public final boolean autoAcknowledge;

    // ------------------------------------------------------------------
    // Back-compat exposed fields (populated from the sugar setters)
    // ------------------------------------------------------------------

    @Nullable public final String lifetimeProductId;   // alias for defaultLifetimeProductId
    @Nullable public final String monthlySubProductId; // alias for defaultMonthlySpec.productId
    @Nullable public final String monthlyBasePlanId;   // alias for defaultMonthlySpec.basePlanId
    @Nullable public final String yearlySubProductId;  // alias for defaultYearlySpec.productId
    @Nullable public final String yearlyBasePlanId;    // alias for defaultYearlySpec.basePlanId
    @Nullable public final String yearlyTrialOfferId;  // alias for defaultYearlySpec.preferredOfferId

    private BillingConfig(Builder b) {
        this.lifetimeProductIds = Collections.unmodifiableSet(new LinkedHashSet<>(b.lifetimeProductIds));
        this.consumableProductIds = Collections.unmodifiableSet(new LinkedHashSet<>(b.consumableProductIds));
        this.subscriptions = Collections.unmodifiableList(new ArrayList<>(b.subscriptions));
        this.defaultLifetimeProductId = b.defaultLifetimeProductId;
        this.defaultMonthlySpec = b.defaultMonthlySpec;
        this.defaultYearlySpec = b.defaultYearlySpec;
        this.obfuscatedAccountId = b.obfuscatedAccountId;
        this.obfuscatedProfileId = b.obfuscatedProfileId;
        this.base64LicenseKey = b.base64LicenseKey;
        this.enableLogging = b.enableLogging;
        this.autoAcknowledge = b.autoAcknowledge;

        this.lifetimeProductId = b.defaultLifetimeProductId;
        this.monthlySubProductId = b.defaultMonthlySpec == null ? null : b.defaultMonthlySpec.productId;
        this.monthlyBasePlanId = b.defaultMonthlySpec == null ? null : b.defaultMonthlySpec.basePlanId;
        this.yearlySubProductId = b.defaultYearlySpec == null ? null : b.defaultYearlySpec.productId;
        this.yearlyBasePlanId = b.defaultYearlySpec == null ? null : b.defaultYearlySpec.basePlanId;
        this.yearlyTrialOfferId = b.defaultYearlySpec == null ? null : b.defaultYearlySpec.preferredOfferId;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final LinkedHashSet<String> lifetimeProductIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> consumableProductIds = new LinkedHashSet<>();
        private final List<SubscriptionSpec> subscriptions = new ArrayList<>();

        private String defaultLifetimeProductId;
        private SubscriptionSpec defaultMonthlySpec;
        private SubscriptionSpec defaultYearlySpec;

        // Legacy-setter scratch state: the setters can be called in any order, so each
        // setter only stores its field here and build() finalises the spec. Not used by
        // the generic addSubscription / defaultMonthly / defaultYearly paths.
        private String legacyMonthlyProductId;
        private String legacyMonthlyBasePlanId;
        private String legacyYearlyProductId;
        private String legacyYearlyBasePlanId;
        private String legacyYearlyTrialOfferId;

        private String obfuscatedAccountId;
        private String obfuscatedProfileId;
        private String base64LicenseKey;
        private boolean enableLogging = false;
        private boolean autoAcknowledge = true;

        // ------------------------------------------------------------------
        //  Generic catalog API
        // ------------------------------------------------------------------

        /**
         * Register a non-consumable (lifetime-style) product. Call once per product id.
         * Safe to call for legacy or alternate SKUs (discount, launch-special, remove-ads)
         * -- PaywallCoordinator in the caller's code decides which one to invoke.
         */
        public Builder addLifetimeProductId(@NonNull String id) {
            this.lifetimeProductIds.add(id);
            return this;
        }

        /** Register many lifetime products in one call. */
        public Builder addLifetimeProductIds(@NonNull Iterable<String> ids) {
            for (String id : ids) this.lifetimeProductIds.add(id);
            return this;
        }

        /**
         * Register a consumable product (coins, gems, lives, energy refills).
         * Consumables are auto-consumed after successful delivery so the user can buy the
         * same SKU repeatedly. Grant the in-game resource from
         * {@code WrapperListener#onConsumablePurchased}.
         */
        public Builder addConsumableProductId(@NonNull String id) {
            this.consumableProductIds.add(id);
            return this;
        }

        /** Register many consumables in one call. */
        public Builder addConsumableProductIds(@NonNull Iterable<String> ids) {
            for (String id : ids) this.consumableProductIds.add(id);
            return this;
        }

        /** Register one subscription + base plan combination. Call once per spec. */
        public Builder addSubscription(@NonNull SubscriptionSpec spec) {
            this.subscriptions.add(spec);
            return this;
        }

        /** Register many subscription specs in one call. */
        public Builder addSubscriptions(@NonNull Iterable<SubscriptionSpec> specs) {
            for (SubscriptionSpec s : specs) this.subscriptions.add(s);
            return this;
        }

        // ------------------------------------------------------------------
        //  Default sugar bindings (optional). Only needed if you want to call
        //  buyLifetime() / subscribeMonthly() / subscribeYearlyWithTrial().
        // ------------------------------------------------------------------

        /** Pick a default lifetime id. Also registers it in the catalog if not already. */
        public Builder defaultLifetimeProductId(@Nullable String id) {
            this.defaultLifetimeProductId = id;
            if (id != null) this.lifetimeProductIds.add(id);
            return this;
        }

        /**
         * Bind a subscription as the default "monthly" sugar target, with {@code preferTrial}
         * decided by the caller. Most callers want
         * {@link #defaultMonthly(String, String)} (no trial) or
         * {@link #defaultMonthlyWithTrial(String, String)}.
         */
        public Builder defaultMonthly(@NonNull SubscriptionSpec spec) {
            this.defaultMonthlySpec = spec;
            this.subscriptions.add(spec);
            return this;
        }

        public Builder defaultMonthly(@NonNull String productId, @NonNull String basePlanId) {
            return defaultMonthly(SubscriptionSpec.of(productId, basePlanId));
        }

        public Builder defaultMonthlyWithTrial(@NonNull String productId, @NonNull String basePlanId) {
            return defaultMonthly(SubscriptionSpec.withTrial(productId, basePlanId));
        }

        /** Bind a subscription as the default "yearly" sugar target. */
        public Builder defaultYearly(@NonNull SubscriptionSpec spec) {
            this.defaultYearlySpec = spec;
            this.subscriptions.add(spec);
            return this;
        }

        public Builder defaultYearly(@NonNull String productId, @NonNull String basePlanId) {
            return defaultYearly(SubscriptionSpec.of(productId, basePlanId));
        }

        public Builder defaultYearlyWithTrial(@NonNull String productId, @NonNull String basePlanId) {
            return defaultYearly(SubscriptionSpec.withTrial(productId, basePlanId));
        }

        // ------------------------------------------------------------------
        //  Legacy 3-shape sugar setters (wire into the generic catalog).
        // ------------------------------------------------------------------

        /** Legacy alias for {@link #defaultLifetimeProductId(String)}. */
        public Builder lifetimeProductId(@Nullable String id) { return defaultLifetimeProductId(id); }

        public Builder monthlySubProductId(@Nullable String id) {
            this.legacyMonthlyProductId = id;
            return this;
        }

        public Builder monthlyBasePlanId(@Nullable String plan) {
            this.legacyMonthlyBasePlanId = plan;
            return this;
        }

        public Builder yearlySubProductId(@Nullable String id) {
            this.legacyYearlyProductId = id;
            return this;
        }

        public Builder yearlyBasePlanId(@Nullable String plan) {
            this.legacyYearlyBasePlanId = plan;
            return this;
        }

        public Builder yearlyTrialOfferId(@Nullable String offerId) {
            this.legacyYearlyTrialOfferId = offerId;
            return this;
        }

        // ------------------------------------------------------------------
        //  Account + runtime toggles
        // ------------------------------------------------------------------

        /**
         * Hashed, stable user id sent to Play as {@code obfuscatedAccountId}. Required for
         * fraud detection, trial-per-account enforcement, and server-side token mapping.
         */
        public Builder userId(@NonNull String hashedUserId) { this.obfuscatedAccountId = hashedUserId; return this; }

        public Builder profileId(@Nullable String hashedProfileId) { this.obfuscatedProfileId = hashedProfileId; return this; }

        public Builder base64LicenseKey(@Nullable String key) { this.base64LicenseKey = key; return this; }

        public Builder enableLogging(boolean enable) { this.enableLogging = enable; return this; }

        public Builder autoAcknowledge(boolean enable) { this.autoAcknowledge = enable; return this; }

        public BillingConfig build() {
            // Resolve legacy monthly / yearly setters into real specs if the generic
            // defaultMonthly(...) / defaultYearly(...) wasn't used explicitly.
            if (defaultMonthlySpec == null
                    && legacyMonthlyProductId != null && legacyMonthlyBasePlanId != null) {
                defaultMonthly(SubscriptionSpec.of(legacyMonthlyProductId, legacyMonthlyBasePlanId));
            }
            if (defaultYearlySpec == null
                    && legacyYearlyProductId != null && legacyYearlyBasePlanId != null) {
                SubscriptionSpec spec = SubscriptionSpec.builder()
                        .productId(legacyYearlyProductId)
                        .basePlanId(legacyYearlyBasePlanId)
                        .preferTrial(true)
                        .preferredOfferId(legacyYearlyTrialOfferId)
                        .build();
                defaultYearly(spec);
            }

            if (lifetimeProductIds.isEmpty() && consumableProductIds.isEmpty() && subscriptions.isEmpty()) {
                throw new IllegalArgumentException("BillingConfig must declare at least one product (lifetime, consumable, or subscription)");
            }
            return new BillingConfig(this);
        }
    }
}
