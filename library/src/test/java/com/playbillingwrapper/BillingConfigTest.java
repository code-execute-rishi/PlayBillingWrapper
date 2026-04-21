package com.playbillingwrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.playbillingwrapper.model.BillingConfig;
import com.playbillingwrapper.model.SubscriptionSpec;

import org.junit.Test;

import java.util.Arrays;

public class BillingConfigTest {

    @Test
    public void generic_catalog_six_skus() {
        BillingConfig cfg = BillingConfig.builder()
                .addLifetimeProductId("com.app.pro_lifetime")
                .addLifetimeProductId("com.app.pro_lifetime_launch")
                .addLifetimeProductId("com.app.remove_ads")
                .addLifetimeProductId("com.app.upgrade_to_pro")
                .addSubscription(SubscriptionSpec.withTrial("com.app.premium", "monthly"))
                .addSubscription(SubscriptionSpec.of("com.app.premium", "yearly"))
                .userId("hash")
                .build();

        assertEquals(4, cfg.lifetimeProductIds.size());
        assertTrue(cfg.lifetimeProductIds.contains("com.app.pro_lifetime_launch"));
        assertEquals(2, cfg.subscriptions.size());
    }

    @Test
    public void sugar_bindings_populate_catalog_and_legacy_fields() {
        BillingConfig cfg = BillingConfig.builder()
                .defaultLifetimeProductId("com.app.lifetime")
                .defaultMonthlyWithTrial("com.app.premium", "monthly")
                .defaultYearly("com.app.premium", "yearly")
                .userId("hash")
                .build();

        assertEquals("com.app.lifetime", cfg.lifetimeProductId);
        assertEquals("com.app.premium", cfg.monthlySubProductId);
        assertEquals("monthly", cfg.monthlyBasePlanId);
        assertEquals("com.app.premium", cfg.yearlySubProductId);
        assertEquals("yearly", cfg.yearlyBasePlanId);

        assertNotNull(cfg.defaultMonthlySpec);
        assertTrue(cfg.defaultMonthlySpec.preferTrial);
        assertNotNull(cfg.defaultYearlySpec);
        assertFalse(cfg.defaultYearlySpec.preferTrial);

        // Sugar setters also register into the generic catalog.
        assertTrue(cfg.lifetimeProductIds.contains("com.app.lifetime"));
        assertEquals(2, cfg.subscriptions.size());
    }

    @Test
    public void legacy_setters_still_produce_functional_yearly_trial_spec() {
        BillingConfig cfg = BillingConfig.builder()
                .lifetimeProductId("com.app.lifetime")
                .monthlySubProductId("com.app.premium")
                .monthlyBasePlanId("monthly")
                .yearlySubProductId("com.app.premium")
                .yearlyBasePlanId("yearly")
                .yearlyTrialOfferId("freetrial")
                .userId("hash")
                .build();

        assertNotNull(cfg.defaultYearlySpec);
        assertTrue("legacy yearly path implies trial preference", cfg.defaultYearlySpec.preferTrial);
        assertEquals("freetrial", cfg.defaultYearlySpec.preferredOfferId);
    }

    @Test
    public void addSubscriptions_iterable_registers_all() {
        BillingConfig cfg = BillingConfig.builder()
                .addLifetimeProductId("only-for-build")
                .addSubscriptions(Arrays.asList(
                        SubscriptionSpec.of("sub.a", "monthly"),
                        SubscriptionSpec.withTrial("sub.a", "yearly"),
                        SubscriptionSpec.of("sub.b", "monthly")))
                .userId("hash")
                .build();
        assertEquals(3, cfg.subscriptions.size());
    }

    @Test
    public void build_requires_at_least_one_product() {
        try {
            BillingConfig.builder().userId("hash").build();
            fail("empty catalog should throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void consumable_ids_registered_in_config() {
        BillingConfig cfg = BillingConfig.builder()
                .addConsumableProductId("com.app.coins_100")
                .addConsumableProductId("com.app.gems_500")
                .addConsumableProductId("com.app.life_refill")
                .userId("hash")
                .build();

        assertEquals(3, cfg.consumableProductIds.size());
        assertTrue(cfg.consumableProductIds.contains("com.app.coins_100"));
        assertTrue(cfg.consumableProductIds.contains("com.app.gems_500"));
        assertTrue(cfg.consumableProductIds.contains("com.app.life_refill"));
    }

    @Test
    public void consumable_only_config_builds() {
        // coin-shop app with no subscriptions and no lifetime
        BillingConfig cfg = BillingConfig.builder()
                .addConsumableProductId("com.app.coins_100")
                .userId("hash")
                .build();
        assertTrue(cfg.lifetimeProductIds.isEmpty());
        assertTrue(cfg.subscriptions.isEmpty());
        assertEquals(1, cfg.consumableProductIds.size());
    }

    @Test
    public void mixed_catalog_lifetime_consumables_subs_all_work() {
        BillingConfig cfg = BillingConfig.builder()
                .addLifetimeProductId("com.app.pro_lifetime")
                .addConsumableProductId("com.app.coins_100")
                .addConsumableProductId("com.app.gems_500")
                .addSubscription(com.playbillingwrapper.model.SubscriptionSpec.withTrial("com.app.premium", "monthly"))
                .userId("hash")
                .build();

        assertEquals(1, cfg.lifetimeProductIds.size());
        assertEquals(2, cfg.consumableProductIds.size());
        assertEquals(1, cfg.subscriptions.size());
    }

    @Test
    public void no_defaults_means_sugar_fields_are_null() {
        BillingConfig cfg = BillingConfig.builder()
                .addLifetimeProductId("only-sku")
                .userId("hash")
                .build();
        assertNull(cfg.defaultLifetimeProductId);
        assertNull(cfg.defaultMonthlySpec);
        assertNull(cfg.defaultYearlySpec);
        assertNull(cfg.monthlySubProductId);
    }
}
