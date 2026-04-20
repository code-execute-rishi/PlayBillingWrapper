package com.playbillingwrapper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class IdempotencyStoreTest {

    private IdempotencyStore store;

    @Before
    public void setUp() {
        store = new IdempotencyStore(ApplicationProvider.getApplicationContext());
        store.clearAll();
    }

    @After
    public void tearDown() {
        store.clearAll();
    }

    @Test
    public void unknown_token_is_not_handled() {
        assertFalse(store.isHandled("never-seen"));
    }

    @Test
    public void markHandled_persists_across_new_instance() {
        store.markHandled("tok-a");
        assertTrue(store.isHandled("tok-a"));

        IdempotencyStore other = new IdempotencyStore(ApplicationProvider.getApplicationContext());
        assertTrue("token must survive across instances (shared SharedPreferences)",
                other.isHandled("tok-a"));
    }

    @Test
    public void forget_removes_single_token_only() {
        store.markHandled("tok-a");
        store.markHandled("tok-b");

        store.forget("tok-a");

        assertFalse(store.isHandled("tok-a"));
        assertTrue(store.isHandled("tok-b"));
    }

    @Test
    public void forget_unknown_token_is_noop() {
        store.markHandled("tok-a");
        store.forget("tok-unknown");
        assertTrue(store.isHandled("tok-a"));
    }

    @Test
    public void clearAll_removes_every_token() {
        store.markHandled("tok-a");
        store.markHandled("tok-b");
        store.markHandled("tok-c");

        store.clearAll();

        assertFalse(store.isHandled("tok-a"));
        assertFalse(store.isHandled("tok-b"));
        assertFalse(store.isHandled("tok-c"));
    }

    @Test
    public void duplicate_markHandled_is_idempotent() {
        store.markHandled("tok-a");
        store.markHandled("tok-a");
        store.markHandled("tok-a");
        assertTrue(store.isHandled("tok-a"));
    }
}
