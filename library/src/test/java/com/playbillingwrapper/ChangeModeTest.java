package com.playbillingwrapper;

import static org.junit.Assert.assertEquals;

import com.android.billingclient.api.BillingFlowParams;
import com.playbillingwrapper.type.ChangeMode;

import org.junit.Test;

public class ChangeModeTest {

    @Test
    public void upgrade_prorate_maps_to_charge_prorated_price() {
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE,
                ChangeMode.UPGRADE_PRORATE_NOW.playReplacementMode);
    }

    @Test
    public void upgrade_charge_full_maps() {
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE,
                ChangeMode.UPGRADE_CHARGE_FULL.playReplacementMode);
    }

    @Test
    public void swap_with_time_credit_maps() {
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION,
                ChangeMode.SWAP_WITH_TIME_CREDIT.playReplacementMode);
    }

    @Test
    public void swap_without_proration_maps() {
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION,
                ChangeMode.SWAP_WITHOUT_PRORATION.playReplacementMode);
    }

    @Test
    public void downgrade_deferred_maps() {
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED,
                ChangeMode.DOWNGRADE_DEFERRED.playReplacementMode);
    }

    @Test
    public void all_modes_have_distinct_play_codes() {
        int[] codes = new int[ChangeMode.values().length];
        for (int i = 0; i < codes.length; i++) codes[i] = ChangeMode.values()[i].playReplacementMode;
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertEquals("modes " + ChangeMode.values()[i] + " and " + ChangeMode.values()[j] + " must map to distinct codes",
                        false, codes[i] == codes[j]);
            }
        }
    }
}
