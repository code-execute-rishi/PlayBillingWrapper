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
     *     <li>Otherwise, the first promo offer on the base plan (any offer with a
     *         non-null {@code offerId} -- i.e. Play Console-configured promos like
     *         winbacks, intro pricing, audience-tagged offers).</li>
     *     <li>As a last resort, the base plan offer itself ({@code offerId == null} --
     *         the un-promoted recurring price).</li>
     * </ol>
     * <p>
     * Rationale for "promo before base plan": Play silently omits promos the current
     * account fails the eligibility filter for, so any promo that survives into
     * {@code ProductDetails} is one Play will honour at checkout. Surfacing the best
     * available promo by default is the safer, revenue-positive choice. Callers who
     * specifically want the un-promoted base plan at checkout can call the exact-offer
     * overload {@code PlayBillingWrapper.subscribe(activity, productId, basePlanId,
     * null)} -- {@code null} for {@code offerId} resolves via
     * {@link #findByOfferId} to the base plan offer (which Play returns with
     * {@code offerId == null}). There is no spec-level "always pick base plan" flag in
     * v0.4; setting {@code SubscriptionSpec.preferredOfferId = null} means "no
     * preference", not "prefer the base plan offer".
     *
     * @return an offer token, or {@code null} if no offer on the base plan can be resolved.
     */
    @Nullable
    public static String pick(@NonNull ProductDetails details,
                              @NonNull String basePlanId,
                              @Nullable String preferredOfferId,
                              boolean preferTrial) {
        ProductDetails.SubscriptionOfferDetails chosen = pickOffer(details, basePlanId, preferredOfferId, preferTrial);
        return chosen == null ? null : chosen.getOfferToken();
    }

    /**
     * Same selection logic as {@link #pick} but returns the chosen offer details object
     * instead of just its token. Used by post-purchase code paths that need to inspect
     * the offer's pricing phases (e.g. resolving the intro phase attached to a purchase).
     */
    @Nullable
    static ProductDetails.SubscriptionOfferDetails pickOffer(@NonNull ProductDetails details,
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
                if (preferredOfferId.equals(o.getOfferId())) return o;
            }
        }

        if (preferTrial) {
            for (ProductDetails.SubscriptionOfferDetails o : onBasePlan) {
                if (o.getOfferId() == null) continue; // base plan itself, not a promo
                if (hasFreeTrialPhase(o)) return o;
            }
        }

        // Prefer the first promo offer on the base plan over the un-promoted base plan
        // offer. Play omits ineligible promos, so any surviving promo is sellable to
        // this account and surfacing it by default is revenue-positive.
        for (ProductDetails.SubscriptionOfferDetails o : onBasePlan) {
            if (o.getOfferId() != null) return o;
        }

        // Last resort: the un-promoted base plan offer (offerId == null). Every offer
        // in onBasePlan has either a non-null or null offerId, so combined with the
        // promo loop above this is exhaustive on a non-empty onBasePlan -- no further
        // fallback needed.
        for (ProductDetails.SubscriptionOfferDetails o : onBasePlan) {
            if (o.getOfferId() == null) return o;
        }
        return null;
    }

    /**
     * True if the given product has at least one eligible offer on {@code basePlanId}
     * that contains a free-trial pricing phase (any phase with {@code priceAmountMicros == 0}).
     * <p>
     * Note: Google Play silently omits ineligible offers from {@code getSubscriptionOfferDetails()},
     * so this is also a reliable proxy for "is the current Play account still trial-eligible".
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

    /**
     * True if the given product has at least one offer on {@code basePlanId} that contains
     * an intro pricing phase (non-zero price with {@code RecurrenceMode.FINITE_RECURRING},
     * e.g. "$1 for the first week").
     * <p>
     * Google Play omits offers the current Play account fails the eligibility filter for
     * (e.g. first-time-redeemer offers for repeat buyers, audience-tag-gated offers, promo
     * codes), so this is a reliable signal for "the current account can be sold this intro
     * offer right now". It does <i>not</i> distinguish between offer-eligibility-filter
     * variants (first-time vs audience-tag vs promo); callers that need to know <i>why</i>
     * an offer is/isn't visible must inspect {@link ProductDetails.SubscriptionOfferDetails#getOfferTags()}
     * or query the Play Developer API.
     */
    public static boolean isIntroEligible(@NonNull ProductDetails details, @NonNull String basePlanId) {
        return findOfferWithIntroPhase(details, basePlanId) != null;
    }

    /**
     * Returns the first offer on {@code basePlanId} that contains an intro pricing phase,
     * or {@code null} if none exists. Skips the base-plan offer (offerId == null), which
     * by definition has only the recurring phase.
     */
    @Nullable
    static ProductDetails.SubscriptionOfferDetails findOfferWithIntroPhase(
            @NonNull ProductDetails details, @NonNull String basePlanId) {
        List<ProductDetails.SubscriptionOfferDetails> all = details.getSubscriptionOfferDetails();
        if (all == null) return null;
        for (ProductDetails.SubscriptionOfferDetails o : all) {
            if (!basePlanId.equals(o.getBasePlanId())) continue;
            if (o.getOfferId() == null) continue;
            if (hasIntroPhase(o)) return o;
        }
        return null;
    }

    /**
     * Look up an offer on {@code basePlanId} by its exact {@code offerId}. Returns
     * {@code null} when no offer matches -- typically because Play omitted it under the
     * offer-eligibility filter (first-time-redeemer offer for a repeat buyer, missing
     * audience tag, expired promo). Pass the {@code offerId} configured in Play Console.
     * <p>
     * Pass {@code null} to target the base plan offer itself (Play returns
     * {@code offerId == null} for the un-promoted base plan price), useful when callers
     * want to bypass any promo offers and route to recurring pricing directly.
     */
    @Nullable
    public static ProductDetails.SubscriptionOfferDetails findByOfferId(
            @NonNull ProductDetails details,
            @NonNull String basePlanId,
            @Nullable String offerId) {
        List<ProductDetails.SubscriptionOfferDetails> all = details.getSubscriptionOfferDetails();
        if (all == null) return null;
        for (ProductDetails.SubscriptionOfferDetails o : all) {
            if (!basePlanId.equals(o.getBasePlanId())) continue;
            if (java.util.Objects.equals(offerId, o.getOfferId())) return o;
        }
        return null;
    }

    private static boolean hasFreeTrialPhase(@NonNull ProductDetails.SubscriptionOfferDetails offer) {
        for (ProductDetails.PricingPhase p : offer.getPricingPhases().getPricingPhaseList()) {
            if (p.getPriceAmountMicros() == 0L) return true;
        }
        return false;
    }

    public static boolean hasIntroPhase(@NonNull ProductDetails.SubscriptionOfferDetails offer) {
        for (ProductDetails.PricingPhase p : offer.getPricingPhases().getPricingPhaseList()) {
            if (p.getPriceAmountMicros() > 0L
                    && p.getRecurrenceMode() == ProductDetails.RecurrenceMode.FINITE_RECURRING) {
                return true;
            }
        }
        return false;
    }
}
