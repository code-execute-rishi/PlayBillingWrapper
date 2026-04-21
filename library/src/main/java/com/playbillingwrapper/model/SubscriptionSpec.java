package com.playbillingwrapper.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Declares one Play Console subscription that PlayBillingWrapper should manage.
 * <p>
 * One {@code SubscriptionSpec} per Play Console subscription + base plan combination.
 * Ship multiple specs for the same {@code productId} with different {@code basePlanId}
 * values if your product has both a monthly and yearly base plan.
 */
public final class SubscriptionSpec {

    /** Play Console subscription product id. */
    @NonNull
    public final String productId;

    /** Play Console base plan id (e.g. {@code "monthly"}, {@code "yearly"}). */
    @NonNull
    public final String basePlanId;

    /**
     * When true, {@link com.playbillingwrapper.PlayBillingWrapper#subscribe subscribe()}
     * auto-picks a free-trial offer on this base plan if the user is still eligible (Play
     * silently omits ineligible offers from {@code ProductDetails}). Falls back to the
     * base plan offer otherwise.
     */
    public final boolean preferTrial;

    /**
     * Optional explicit offer id to prefer over any trial auto-pick. Useful for "winback"
     * or "discount" offers configured in Play Console.
     */
    @Nullable
    public final String preferredOfferId;

    /**
     * Optional free-form tag so callers can classify specs in their own taxonomy
     * (e.g. {@code "monthly"}, {@code "yearly"}, {@code "monthly_discount"}). Never sent
     * to Play. Useful for paywall coordinators that route between SKUs.
     */
    @Nullable
    public final String tag;

    private SubscriptionSpec(Builder b) {
        this.productId = Objects.requireNonNull(b.productId, "productId");
        this.basePlanId = Objects.requireNonNull(b.basePlanId, "basePlanId");
        this.preferTrial = b.preferTrial;
        this.preferredOfferId = b.preferredOfferId;
        this.tag = b.tag;
    }

    /** Convenience for a base plan without trial preference or offer overrides. */
    @NonNull
    public static SubscriptionSpec of(@NonNull String productId, @NonNull String basePlanId) {
        return builder().productId(productId).basePlanId(basePlanId).build();
    }

    /**
     * Convenience for a base plan where the caller wants the library to auto-pick a free-
     * trial offer if the user is eligible.
     */
    @NonNull
    public static SubscriptionSpec withTrial(@NonNull String productId, @NonNull String basePlanId) {
        return builder().productId(productId).basePlanId(basePlanId).preferTrial(true).build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String productId;
        private String basePlanId;
        private boolean preferTrial = false;
        private String preferredOfferId;
        private String tag;

        public Builder productId(@NonNull String productId) { this.productId = productId; return this; }
        public Builder basePlanId(@NonNull String basePlanId) { this.basePlanId = basePlanId; return this; }
        public Builder preferTrial(boolean preferTrial) { this.preferTrial = preferTrial; return this; }
        public Builder preferredOfferId(@Nullable String preferredOfferId) { this.preferredOfferId = preferredOfferId; return this; }
        public Builder tag(@Nullable String tag) { this.tag = tag; return this; }

        public SubscriptionSpec build() { return new SubscriptionSpec(this); }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubscriptionSpec)) return false;
        SubscriptionSpec s = (SubscriptionSpec) o;
        return preferTrial == s.preferTrial
                && productId.equals(s.productId)
                && basePlanId.equals(s.basePlanId)
                && Objects.equals(preferredOfferId, s.preferredOfferId)
                && Objects.equals(tag, s.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, basePlanId, preferTrial, preferredOfferId, tag);
    }

    @NonNull
    @Override
    public String toString() {
        return "SubscriptionSpec{productId='" + productId + "', basePlanId='" + basePlanId
                + "', preferTrial=" + preferTrial
                + ", preferredOfferId='" + preferredOfferId + "', tag='" + tag + "'}";
    }
}
