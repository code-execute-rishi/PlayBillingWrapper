package com.playbillingwrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.billingclient.api.ProductDetails;
import com.playbillingwrapper.model.SubscriptionOfferDetails.PricingPhases;

import org.junit.Test;

public class PricingPhasesHelperTest {

    private static final long DAY = 86_400_000L;

    @Test
    public void free_phase_detected() {
        PricingPhases p = new PricingPhases("Free", 0L, "", "P3D", 1,
                ProductDetails.RecurrenceMode.FINITE_RECURRING);
        assertTrue(p.isFree());
        assertFalse(p.isIntro());
        assertFalse(p.isRecurring());
    }

    @Test
    public void intro_phase_detected() {
        PricingPhases p = new PricingPhases("$0.99", 990_000L, "USD", "P1M", 1,
                ProductDetails.RecurrenceMode.FINITE_RECURRING);
        assertFalse(p.isFree());
        assertTrue(p.isIntro());
        assertFalse(p.isRecurring());
    }

    @Test
    public void recurring_phase_detected() {
        PricingPhases p = new PricingPhases("$4.99", 4_990_000L, "USD", "P1M", 0,
                ProductDetails.RecurrenceMode.INFINITE_RECURRING);
        assertFalse(p.isFree());
        assertFalse(p.isIntro());
        assertTrue(p.isRecurring());
    }

    @Test
    public void periodIso_and_duration_round_trip() {
        PricingPhases p = new PricingPhases("$1.00", 1_000_000L, "USD", "P7D", 0,
                ProductDetails.RecurrenceMode.INFINITE_RECURRING);
        assertEquals("P7D", p.getPeriodIso());
        assertEquals(7 * DAY, p.getPeriodDurationMillis());
    }

    @Test
    public void malformed_period_returns_minus_one() {
        PricingPhases p = new PricingPhases("x", 0L, "", "garbage", 0,
                ProductDetails.RecurrenceMode.INFINITE_RECURRING);
        assertEquals(-1L, p.getPeriodDurationMillis());
    }
}
