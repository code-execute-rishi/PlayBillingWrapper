package com.playbillingwrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.billingclient.api.ProductDetails;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OfferSelectorTest {

    private static final String BASE_PLAN = "yearly";

    @Test
    public void returns_null_when_no_offers() {
        ProductDetails details = mock(ProductDetails.class);
        when(details.getSubscriptionOfferDetails()).thenReturn(null);
        assertNull(OfferSelector.pick(details, BASE_PLAN, null, false));
    }

    @Test
    public void returns_null_when_no_offer_on_base_plan() {
        ProductDetails details = withOffers(offer("other", null, "tok-other", freePhase()));
        assertNull(OfferSelector.pick(details, BASE_PLAN, null, true));
    }

    @Test
    public void picks_preferred_offer_id_when_present() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "freetrial", "tok-trial", freePhase(), paidPhase()),
                offer(BASE_PLAN, "winback50", "tok-winback", paidPhase())
        );
        assertEquals("tok-winback",
                OfferSelector.pick(details, BASE_PLAN, "winback50", false));
    }

    @Test
    public void prefers_trial_when_requested_and_eligible() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "freetrial", "tok-trial", freePhase(), paidPhase())
        );
        assertEquals("tok-trial",
                OfferSelector.pick(details, BASE_PLAN, null, true));
    }

    @Test
    public void falls_back_to_base_plan_when_trial_not_requested() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, "freetrial", "tok-trial", freePhase(), paidPhase()),
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        assertEquals("tok-base",
                OfferSelector.pick(details, BASE_PLAN, null, false));
    }

    @Test
    public void falls_back_to_base_plan_when_trial_ineligible() {
        // Only the base plan offer exists -- Play omits the trial offer for returning users.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        assertEquals("tok-base",
                OfferSelector.pick(details, BASE_PLAN, null, true));
    }

    @Test
    public void falls_back_to_first_offer_if_no_null_offer_id() {
        // All offers have non-null offerIds (no explicit "base plan" offer visible).
        ProductDetails details = withOffers(
                offer(BASE_PLAN, "standard", "tok-standard", paidPhase()),
                offer(BASE_PLAN, "promo20", "tok-promo", paidPhase())
        );
        assertEquals("tok-standard",
                OfferSelector.pick(details, BASE_PLAN, null, false));
    }

    @Test
    public void isTrialEligible_true_when_any_non_base_offer_has_free_phase() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "freetrial", "tok-trial", freePhase(), paidPhase())
        );
        assertTrue(OfferSelector.isTrialEligible(details, BASE_PLAN));
    }

    @Test
    public void isTrialEligible_false_when_no_trial_offer_present() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "promo20", "tok-promo", paidPhase())
        );
        assertFalse(OfferSelector.isTrialEligible(details, BASE_PLAN));
    }

    @Test
    public void isTrialEligible_false_when_product_has_no_offers() {
        ProductDetails details = mock(ProductDetails.class);
        when(details.getSubscriptionOfferDetails()).thenReturn(null);
        assertFalse(OfferSelector.isTrialEligible(details, BASE_PLAN));
    }

    @Test
    public void isTrialEligible_false_for_different_base_plan() {
        ProductDetails details = withOffers(
                offer("monthly", "freetrial", "tok-trial", freePhase(), paidPhase())
        );
        assertFalse(OfferSelector.isTrialEligible(details, BASE_PLAN));
    }

    @Test
    public void isIntroEligible_true_when_offer_has_finite_recurring_paid_phase() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_1w_1usd", "tok-intro", introPhase(), paidPhase())
        );
        assertTrue(OfferSelector.isIntroEligible(details, BASE_PLAN));
    }

    @Test
    public void isIntroEligible_false_when_only_base_plan_offer_exists() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        assertFalse(OfferSelector.isIntroEligible(details, BASE_PLAN));
    }

    @Test
    public void isIntroEligible_false_when_only_free_trial_offer() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "freetrial", "tok-trial", freePhase(), paidPhase())
        );
        assertFalse(OfferSelector.isIntroEligible(details, BASE_PLAN));
    }

    @Test
    public void isIntroEligible_false_for_different_base_plan() {
        ProductDetails details = withOffers(
                offer("monthly", "intro_1w_1usd", "tok-intro", introPhase(), paidPhase())
        );
        assertFalse(OfferSelector.isIntroEligible(details, BASE_PLAN));
    }

    @Test
    public void preferredOfferId_picks_intro_offer() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_1w_1usd", "tok-intro", introPhase(), paidPhase())
        );
        assertEquals("tok-intro",
                OfferSelector.pick(details, BASE_PLAN, "intro_1w_1usd", false));
    }

    @Test
    public void falls_back_to_base_plan_when_intro_offer_omitted_by_play() {
        // Play hides the intro offer from repeat redeemers -- wrapper must still resolve a token.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        assertEquals("tok-base",
                OfferSelector.pick(details, BASE_PLAN, "intro_1w_1usd", false));
    }

    // ---- helpers ----

    private static ProductDetails withOffers(ProductDetails.SubscriptionOfferDetails... offers) {
        ProductDetails details = mock(ProductDetails.class);
        when(details.getSubscriptionOfferDetails()).thenReturn(Arrays.asList(offers));
        return details;
    }

    private static ProductDetails.SubscriptionOfferDetails offer(
            String basePlanId, String offerId, String token, ProductDetails.PricingPhase... phases) {
        ProductDetails.SubscriptionOfferDetails o = mock(ProductDetails.SubscriptionOfferDetails.class);
        when(o.getBasePlanId()).thenReturn(basePlanId);
        when(o.getOfferId()).thenReturn(offerId);
        when(o.getOfferToken()).thenReturn(token);
        when(o.getOfferTags()).thenReturn(Collections.emptyList());

        ProductDetails.PricingPhases phasesContainer = mock(ProductDetails.PricingPhases.class);
        when(phasesContainer.getPricingPhaseList()).thenReturn(Arrays.asList(phases));
        when(o.getPricingPhases()).thenReturn(phasesContainer);
        return o;
    }

    private static ProductDetails.PricingPhase freePhase() {
        ProductDetails.PricingPhase p = mock(ProductDetails.PricingPhase.class);
        when(p.getPriceAmountMicros()).thenReturn(0L);
        when(p.getFormattedPrice()).thenReturn("Free");
        when(p.getBillingPeriod()).thenReturn("P7D");
        return p;
    }

    private static ProductDetails.PricingPhase paidPhase() {
        ProductDetails.PricingPhase p = mock(ProductDetails.PricingPhase.class);
        when(p.getPriceAmountMicros()).thenReturn(12_99_000_000L);
        when(p.getFormattedPrice()).thenReturn("₹1299.00");
        when(p.getBillingPeriod()).thenReturn("P1Y");
        when(p.getRecurrenceMode()).thenReturn(ProductDetails.RecurrenceMode.INFINITE_RECURRING);
        return p;
    }

    private static ProductDetails.PricingPhase introPhase() {
        ProductDetails.PricingPhase p = mock(ProductDetails.PricingPhase.class);
        when(p.getPriceAmountMicros()).thenReturn(1_000_000L);
        when(p.getFormattedPrice()).thenReturn("$1.00");
        when(p.getBillingPeriod()).thenReturn("P1W");
        when(p.getBillingCycleCount()).thenReturn(1);
        when(p.getRecurrenceMode()).thenReturn(ProductDetails.RecurrenceMode.FINITE_RECURRING);
        return p;
    }
}
