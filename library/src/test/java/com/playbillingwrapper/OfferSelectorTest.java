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
    public void prefers_first_promo_over_base_plan_when_no_trial_requested() {
        // Selection order (post-v0.4): preferred > trial > first promo > base plan offer.
        // Without a trial preference, the wrapper still surfaces the available promo
        // (Play silently omits ineligible promos, so any visible promo is sellable).
        ProductDetails details = withOffers(
                offer(BASE_PLAN, "freetrial", "tok-trial", freePhase(), paidPhase()),
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        assertEquals("tok-trial",
                OfferSelector.pick(details, BASE_PLAN, null, false));
    }

    @Test
    public void falls_back_to_base_plan_when_only_base_plan_offer_exists() {
        // No promo offers at all -- step 3 (first promo) is empty, step 4 (base plan)
        // returns the un-promoted recurring price.
        ProductDetails details = withOffers(
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
    public void first_promo_wins_when_no_base_plan_offer_present() {
        // All offers have non-null offerIds (no explicit base plan offer visible).
        // Promo-loop catches the first one before the base-plan-offer loop runs.
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

    @Test
    public void isIntroEligible_true_for_combined_trial_intro_offer() {
        // Combined offer: free week -> $1 intro month -> recurring $19/yr.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, "trial_intro_combo", "tok-combo", freePhase(), introPhase(), paidPhase())
        );
        assertTrue(OfferSelector.isIntroEligible(details, BASE_PLAN));
        assertTrue(OfferSelector.isTrialEligible(details, BASE_PLAN));
    }

    @Test
    public void findOfferWithIntroPhase_returns_offer_when_present() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_1w_1usd", "tok-intro", introPhase(), paidPhase())
        );
        ProductDetails.SubscriptionOfferDetails got =
                OfferSelector.findOfferWithIntroPhase(details, BASE_PLAN);
        assertEquals("tok-intro", got.getOfferToken());
    }

    @Test
    public void findOfferWithIntroPhase_null_when_only_base_plan() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        assertNull(OfferSelector.findOfferWithIntroPhase(details, BASE_PLAN));
    }

    @Test
    public void hasIntroPhase_true_with_multi_cycle_intro() {
        ProductDetails.SubscriptionOfferDetails o = offer(
                BASE_PLAN, "intro_3m", "tok-3m", multiCycleIntroPhase(3), paidPhase());
        assertTrue(OfferSelector.hasIntroPhase(o));
    }

    @Test
    public void findByOfferId_returns_offer_when_present() {
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_variant_a", "tok-a", introPhase(), paidPhase()),
                offer(BASE_PLAN, "intro_variant_b", "tok-b", introPhase(), paidPhase())
        );
        ProductDetails.SubscriptionOfferDetails got =
                OfferSelector.findByOfferId(details, BASE_PLAN, "intro_variant_b");
        assertEquals("tok-b", got.getOfferToken());
    }

    @Test
    public void findByOfferId_null_when_offer_not_on_base_plan() {
        ProductDetails details = withOffers(
                offer("monthly", "intro_variant_a", "tok-a", introPhase(), paidPhase())
        );
        assertNull(OfferSelector.findByOfferId(details, BASE_PLAN, "intro_variant_a"));
    }

    @Test
    public void findByOfferId_null_when_offer_omitted_by_play() {
        // Play omitted intro_variant_b for this user (eligibility filter).
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_variant_a", "tok-a", introPhase(), paidPhase())
        );
        assertNull(OfferSelector.findByOfferId(details, BASE_PLAN, "intro_variant_b"));
    }

    @Test
    public void findByOfferId_null_offerId_returns_base_plan_offer() {
        // offerId == null targets the un-promoted base plan offer (Play uses null for it).
        ProductDetails details = withOffers(
                offer(BASE_PLAN, "intro_variant_a", "tok-a", introPhase(), paidPhase()),
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        ProductDetails.SubscriptionOfferDetails got =
                OfferSelector.findByOfferId(details, BASE_PLAN, null);
        assertEquals("tok-base", got.getOfferToken());
    }

    @Test
    public void findByOfferId_non_null_offerId_skips_base_plan_offer() {
        // Non-null offerId must not collapse to the base plan offer (where offerId == null).
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase())
        );
        assertNull(OfferSelector.findByOfferId(details, BASE_PLAN, "intro_variant_a"));
    }

    @Test
    public void findByOfferId_null_offerId_returns_null_when_no_base_plan_offer() {
        // No null-offerId entry on the base plan -- caller asked for the base plan slot
        // that does not exist (e.g. catalog with only promo offers).
        ProductDetails details = withOffers(
                offer(BASE_PLAN, "intro_variant_a", "tok-a", introPhase(), paidPhase())
        );
        assertNull(OfferSelector.findByOfferId(details, BASE_PLAN, null));
    }

    @Test
    public void preferred_offer_omitted_falls_through_to_first_promo_not_base_plan() {
        // Spec asked for "winback_25" but Play omitted it (eligibility filter). Another
        // promo is on the base plan. New order: surface the surviving promo rather
        // than dropping to the un-promoted base plan.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_variant_a", "tok-a", introPhase(), paidPhase())
        );
        assertEquals("tok-a",
                OfferSelector.pick(details, BASE_PLAN, "winback_25", false));
    }

    @Test
    public void preferTrial_true_with_no_trial_falls_through_to_first_promo_not_base_plan() {
        // preferTrial requested but no trial offer eligible. An intro promo exists.
        // New order: sell the intro promo rather than the un-promoted base plan.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_variant_a", "tok-a", introPhase(), paidPhase())
        );
        assertEquals("tok-a",
                OfferSelector.pick(details, BASE_PLAN, null, true));
    }

    @Test
    public void multiple_promos_first_wins_when_no_preferred_or_trial() {
        // First non-null-offerId offer in iteration order wins.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_variant_a", "tok-a", introPhase(), paidPhase()),
                offer(BASE_PLAN, "intro_variant_b", "tok-b", introPhase(), paidPhase())
        );
        assertEquals("tok-a",
                OfferSelector.pick(details, BASE_PLAN, null, false));
    }

    @Test
    public void combined_trial_intro_offer_with_preferTrial_false_still_picked_as_promo() {
        // Combined trial+intro offer alongside base plan. Without preferTrial it is
        // still a promo (offerId != null) and wins over the base plan offer.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "trial_intro_combo", "tok-combo", freePhase(), introPhase(), paidPhase())
        );
        assertEquals("tok-combo",
                OfferSelector.pick(details, BASE_PLAN, null, false));
    }

    @Test
    public void pick_with_preferTrial_true_picks_trial_even_when_intro_present() {
        // preferTrial wins over intro offer when both eligible.
        ProductDetails details = withOffers(
                offer(BASE_PLAN, null, "tok-base", paidPhase()),
                offer(BASE_PLAN, "intro_1w_1usd", "tok-intro", introPhase(), paidPhase()),
                offer(BASE_PLAN, "freetrial", "tok-trial", freePhase(), paidPhase())
        );
        assertEquals("tok-trial", OfferSelector.pick(details, BASE_PLAN, null, true));
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

    private static ProductDetails.PricingPhase multiCycleIntroPhase(int cycles) {
        ProductDetails.PricingPhase p = mock(ProductDetails.PricingPhase.class);
        when(p.getPriceAmountMicros()).thenReturn(2_990_000L);
        when(p.getFormattedPrice()).thenReturn("$2.99");
        when(p.getBillingPeriod()).thenReturn("P1M");
        when(p.getBillingCycleCount()).thenReturn(cycles);
        when(p.getRecurrenceMode()).thenReturn(ProductDetails.RecurrenceMode.FINITE_RECURRING);
        return p;
    }
}
