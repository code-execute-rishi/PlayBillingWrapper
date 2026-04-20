package com.playbillingwrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.ProductDetails;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the correct subscription offer token from a {@link ProductDetails}.
 * <p>
 * Google Play silently omits ineligible offers (e.g. a free-trial offer for a user who
 * already used their trial), so {@link #isTrialEligible(ProductDetails, String)} simply
 * checks whether any offer on the target base plan has a free (0-price) phase.
 */
public final class OfferSelector {

    private OfferSelector() { }

    /**
     * Pick the best offer token on the given base plan.
     * <ol>
     *     <li>If {@code preferredOfferId} is non-null and matches an offer, that token wins.</li>
     *     <li>Otherwise, if {@code preferTrial} is true and a free-trial offer is eligible,
     *         that token wins.</li>
     *     <li>Otherwise, the base plan offer (offerId == null) wins.</li>
     *     <li>As a last resort, the first offer on the base plan.</li>
     * </ol>
     *
     * @return an offer token, or {@code null} if no offer on the base plan can be resolved.
     */
    @Nullable
    public static String pick(@NonNull ProductDetails details,
                              @NonNull String basePlanId,
                              @Nullable String preferredOfferId,
                              boolean preferTrial) {
        List<ProductDetails.SubscriptionOfferDetails> all = details.getSubscriptionOfferDetails();
        if (all == null || all.isEmpty()) return null;

        List<ProductDetails.SubscriptionOfferDetails> onBasePlan = new ArrayList<>();
        for (ProductDetails.SubscriptionOfferDetails o : all) {
            if (basePlanId.equals(o.getBasePlanId())) onBasePlan.add(o);
        }
        if (onBasePlan.isEmpty()) return null;

        if (preferredOfferId != null) {
            for (ProductDetails.SubscriptionOfferDetails o : onBasePlan) {
                if (preferredOfferId.equals(o.getOfferId())) return o.getOfferToken();
            }
        }

        if (preferTrial) {
            for (ProductDetails.SubscriptionOfferDetails o : onBasePlan) {
                if (o.getOfferId() == null) continue; // base plan itself, not a promo
                if (hasFreeTrialPhase(o)) return o.getOfferToken();
            }
        }

        for (ProductDetails.SubscriptionOfferDetails o : onBasePlan) {
            if (o.getOfferId() == null) return o.getOfferToken();
        }

        return onBasePlan.get(0).getOfferToken();
    }

    /**
     * True if the given product has at least one eligible offer on {@code basePlanId}
     * that starts with a free-trial pricing phase.
     */
    public static boolean isTrialEligible(@NonNull ProductDetails details, @NonNull String basePlanId) {
        List<ProductDetails.SubscriptionOfferDetails> all = details.getSubscriptionOfferDetails();
        if (all == null) return false;
        for (ProductDetails.SubscriptionOfferDetails o : all) {
            if (!basePlanId.equals(o.getBasePlanId())) continue;
            if (o.getOfferId() == null) continue;
            if (hasFreeTrialPhase(o)) return true;
        }
        return false;
    }

    private static boolean hasFreeTrialPhase(@NonNull ProductDetails.SubscriptionOfferDetails offer) {
        for (ProductDetails.PricingPhase p : offer.getPricingPhases().getPricingPhaseList()) {
            if (p.getPriceAmountMicros() == 0L) return true;
        }
        return false;
    }
}
