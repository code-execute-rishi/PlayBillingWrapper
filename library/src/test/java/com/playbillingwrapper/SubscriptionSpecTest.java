package com.playbillingwrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.playbillingwrapper.model.SubscriptionSpec;

import org.junit.Test;

public class SubscriptionSpecTest {

    @Test
    public void of_defaults_to_no_trial_no_offer() {
        SubscriptionSpec s = SubscriptionSpec.of("prod", "monthly");
        assertEquals("prod", s.productId);
        assertEquals("monthly", s.basePlanId);
        assertFalse(s.preferTrial);
        assertNull(s.preferredOfferId);
        assertNull(s.tag);
    }

    @Test
    public void withTrial_sets_preferTrial_true() {
        SubscriptionSpec s = SubscriptionSpec.withTrial("prod", "yearly");
        assertTrue(s.preferTrial);
        assertNull(s.preferredOfferId);
    }

    @Test
    public void withIntro_sets_preferredOfferId() {
        SubscriptionSpec s = SubscriptionSpec.withIntro("prod", "yearly", "intro_1w_1usd");
        assertEquals("prod", s.productId);
        assertEquals("yearly", s.basePlanId);
        assertFalse(s.preferTrial);
        assertEquals("intro_1w_1usd", s.preferredOfferId);
    }

    @Test(expected = NullPointerException.class)
    public void withIntro_requires_offerId() {
        SubscriptionSpec.withIntro("prod", "yearly", null);
    }

    @Test
    public void builder_populates_every_field() {
        SubscriptionSpec s = SubscriptionSpec.builder()
                .productId("p")
                .basePlanId("b")
                .preferTrial(true)
                .preferredOfferId("winback")
                .tag("monthly_discount")
                .build();
        assertEquals("p", s.productId);
        assertEquals("b", s.basePlanId);
        assertTrue(s.preferTrial);
        assertEquals("winback", s.preferredOfferId);
        assertEquals("monthly_discount", s.tag);
    }

    @Test
    public void equality_covers_every_field() {
        SubscriptionSpec a = SubscriptionSpec.of("p", "b");
        SubscriptionSpec b = SubscriptionSpec.of("p", "b");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, SubscriptionSpec.of("p", "y"));
        assertNotEquals(a, SubscriptionSpec.withTrial("p", "b"));
        assertNotEquals(a, SubscriptionSpec.builder()
                .productId("p").basePlanId("b").preferredOfferId("x").build());
    }

    @Test(expected = NullPointerException.class)
    public void productId_is_required() {
        SubscriptionSpec.builder().basePlanId("b").build();
    }

    @Test(expected = NullPointerException.class)
    public void basePlanId_is_required() {
        SubscriptionSpec.builder().productId("p").build();
    }
}
