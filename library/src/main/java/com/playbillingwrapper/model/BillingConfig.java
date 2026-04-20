package com.playbillingwrapper.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Per-app configuration for {@code PlayBillingWrapper}.
 * <p>
 * Three product shapes are first-class citizens:
 * <ul>
 *     <li>Lifetime one-time unlock ({@link #lifetimeProductId})</li>
 *     <li>Monthly auto-renewing subscription ({@link #monthlySubProductId} + {@link #monthlyBasePlanId})</li>
 *     <li>Yearly auto-renewing subscription with a free trial ({@link #yearlySubProductId} +
 *         {@link #yearlyBasePlanId}, optionally filtered by {@link #yearlyTrialOfferId})</li>
 * </ul>
 * Any field may be null if the app does not offer that shape.
 */
public final class BillingConfig {

    @Nullable public final String lifetimeProductId;

    @Nullable public final String monthlySubProductId;
    @Nullable public final String monthlyBasePlanId;

    @Nullable public final String yearlySubProductId;
    @Nullable public final String yearlyBasePlanId;
    @Nullable public final String yearlyTrialOfferId;

    @Nullable public final String obfuscatedAccountId;
    @Nullable public final String obfuscatedProfileId;

    @Nullable public final String base64LicenseKey;

    public final boolean enableLogging;
    public final boolean autoAcknowledge;

    private BillingConfig(Builder b) {
        this.lifetimeProductId = b.lifetimeProductId;
        this.monthlySubProductId = b.monthlySubProductId;
        this.monthlyBasePlanId = b.monthlyBasePlanId;
        this.yearlySubProductId = b.yearlySubProductId;
        this.yearlyBasePlanId = b.yearlyBasePlanId;
        this.yearlyTrialOfferId = b.yearlyTrialOfferId;
        this.obfuscatedAccountId = b.obfuscatedAccountId;
        this.obfuscatedProfileId = b.obfuscatedProfileId;
        this.base64LicenseKey = b.base64LicenseKey;
        this.enableLogging = b.enableLogging;
        this.autoAcknowledge = b.autoAcknowledge;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String lifetimeProductId;
        private String monthlySubProductId;
        private String monthlyBasePlanId;
        private String yearlySubProductId;
        private String yearlyBasePlanId;
        private String yearlyTrialOfferId;
        private String obfuscatedAccountId;
        private String obfuscatedProfileId;
        private String base64LicenseKey;
        private boolean enableLogging = false;
        private boolean autoAcknowledge = true;

        /** The Play Console product id for a non-consumable lifetime unlock. */
        public Builder lifetimeProductId(@Nullable String id) { this.lifetimeProductId = id; return this; }

        /** The Play Console product id for the monthly subscription. */
        public Builder monthlySubProductId(@Nullable String id) { this.monthlySubProductId = id; return this; }

        /** The base plan id of the monthly subscription (configured in Play Console). */
        public Builder monthlyBasePlanId(@Nullable String id) { this.monthlyBasePlanId = id; return this; }

        /** The Play Console product id for the yearly subscription. */
        public Builder yearlySubProductId(@Nullable String id) { this.yearlySubProductId = id; return this; }

        /** The base plan id of the yearly subscription (configured in Play Console). */
        public Builder yearlyBasePlanId(@Nullable String id) { this.yearlyBasePlanId = id; return this; }

        /**
         * Optional: explicit offer id to select for the yearly trial. If null, the wrapper
         * auto-picks any offer with a free-trial pricing phase (first one wins) when the
         * user is still trial-eligible.
         */
        public Builder yearlyTrialOfferId(@Nullable String id) { this.yearlyTrialOfferId = id; return this; }

        /**
         * Hashed, stable user id sent to Play as {@code obfuscatedAccountId}. Required for
         * fraud detection, trial-per-account enforcement, and server-side token mapping.
         * Never pass raw PII.
         */
        public Builder userId(@NonNull String hashedUserId) { this.obfuscatedAccountId = hashedUserId; return this; }

        /** Optional profile identifier for apps that support multiple profiles per account. */
        public Builder profileId(@Nullable String hashedProfileId) { this.obfuscatedProfileId = hashedProfileId; return this; }

        /**
         * Optional base64 public key from Play Console -> Monetization Setup -> Licensing.
         * If non-null, client-side purchase signatures are verified.
         * <p>
         * Security note: Google recommends moving signature verification to your server.
         * Passing the key here is convenient but means it is shipped in your APK.
         */
        public Builder base64LicenseKey(@Nullable String key) { this.base64LicenseKey = key; return this; }

        /** Turn on verbose Logcat output. Off by default. */
        public Builder enableLogging(boolean enable) { this.enableLogging = enable; return this; }

        /** Automatically acknowledge non-consumable + subscription purchases. On by default. */
        public Builder autoAcknowledge(boolean enable) { this.autoAcknowledge = enable; return this; }

        public BillingConfig build() {
            return new BillingConfig(this);
        }
    }
}
