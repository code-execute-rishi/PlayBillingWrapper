package com.playbillingwrapper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AutoRenewStateStoreTest {

    private AutoRenewStateStore store;

    @Before
    public void setUp() {
        store = new AutoRenewStateStore(ApplicationProvider.getApplicationContext());
        store.clearAll();
    }

    @Test
    public void first_observation_of_auto_renewing_does_not_report_transition() {
        assertFalse(store.recordAndDetectCancellation("tok-a", true));
    }

    @Test
    public void first_observation_of_already_cancelled_does_not_report_transition() {
        // This is the key fix: we must NOT fire onSubscriptionCancelled on first-load
        // of a purchase that is already in the cancelled-but-entitled state.
        assertFalse(store.recordAndDetectCancellation("tok-a", false));
    }

    @Test
    public void prepaid_non_renewing_plan_never_reports_transition() {
        // Prepaid plans always have isAutoRenewing == false. Multiple observations should
        // never report a cancellation.
        for (int i = 0; i < 10; i++) {
            assertFalse("observation " + i, store.recordAndDetectCancellation("prepaid-tok", false));
        }
    }

    @Test
    public void true_then_false_reports_transition_exactly_once() {
        // Recorded as renewing once.
        store.recordAndDetectCancellation("tok-a", true);
        // First observation of non-renewing AFTER renewing fires.
        assertTrue(store.recordAndDetectCancellation("tok-a", false));
        // Subsequent observations do NOT fire again.
        assertFalse(store.recordAndDetectCancellation("tok-a", false));
        assertFalse(store.recordAndDetectCancellation("tok-a", false));
    }

    @Test
    public void resubscribe_then_cancel_fires_again() {
        store.recordAndDetectCancellation("tok-a", true);
        assertTrue(store.recordAndDetectCancellation("tok-a", false));
        // User resubscribes -- Play flips auto-renew back on.
        assertFalse("resubscribe is not a cancellation",
                store.recordAndDetectCancellation("tok-a", true));
        // User cancels again -- this is a new transition.
        assertTrue(store.recordAndDetectCancellation("tok-a", false));
    }

    @Test
    public void independent_tokens_track_independently() {
        store.recordAndDetectCancellation("tok-a", true);
        store.recordAndDetectCancellation("tok-b", true);

        // cancel tok-a
        assertTrue(store.recordAndDetectCancellation("tok-a", false));
        // tok-b still auto-renewing; observation must not fire
        assertFalse(store.recordAndDetectCancellation("tok-b", true));

        // cancel tok-b
        assertTrue(store.recordAndDetectCancellation("tok-b", false));
    }

    @Test
    public void forget_clears_record_so_next_observation_is_treated_as_first() {
        store.recordAndDetectCancellation("tok-a", true);
        store.forget("tok-a");
        // After forgetting, a subsequent non-renewing observation is a first-load again,
        // no transition fires.
        assertFalse(store.recordAndDetectCancellation("tok-a", false));
    }

    @Test
    public void state_persists_across_new_instance() {
        store.recordAndDetectCancellation("tok-a", true);
        // Simulate app restart.
        AutoRenewStateStore other = new AutoRenewStateStore(ApplicationProvider.getApplicationContext());
        assertTrue("persisted auto-renewing state must survive restarts",
                other.recordAndDetectCancellation("tok-a", false));
    }
}
