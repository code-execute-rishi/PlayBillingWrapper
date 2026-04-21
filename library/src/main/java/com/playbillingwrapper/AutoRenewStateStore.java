package com.playbillingwrapper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Persistent record of which subscription {@code purchaseToken}s were last observed as
 * {@code isAutoRenewing() == true}. Used to detect the true→false transition so
 * {@code onSubscriptionCancelled} only fires for an actual cancel, not for first-load
 * observations of already-cancelled subscriptions, and not for prepaid plans that are
 * non-renewing by definition.
 */
final class AutoRenewStateStore {

    private static final String PREFS_NAME = "pbw_auto_renew";
    private static final String KEY_AUTO_RENEWING_TOKENS = "auto_renewing_tokens";

    private final SharedPreferences prefs;

    /**
     * Static lock shared across every {@code AutoRenewStateStore} instance in the
     * process. Each instance reads / writes the same {@code SharedPreferences} file, so
     * per-instance synchronization would let two wrappers interleave read-modify-write
     * cycles and lose tokens -- suppressing or duplicating {@code onSubscriptionCancelled}.
     */
    private static final Object LOCK = new Object();

    AutoRenewStateStore(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Record the current auto-renewing state for this purchase token. Returns {@code true}
     * if this call represents a true→false transition (previously observed renewing, now
     * observed not renewing).
     */
    boolean recordAndDetectCancellation(@NonNull String purchaseToken, boolean isAutoRenewing) {
        synchronized (LOCK) {
            Set<String> set = prefs.getStringSet(KEY_AUTO_RENEWING_TOKENS, null);
            boolean wasRenewing = set != null && set.contains(purchaseToken);
            boolean transitioned = wasRenewing && !isAutoRenewing;

            Set<String> next = (set == null) ? new HashSet<>() : new HashSet<>(set);
            if (isAutoRenewing) {
                next.add(purchaseToken);
            } else {
                next.remove(purchaseToken);
            }
            prefs.edit().putStringSet(KEY_AUTO_RENEWING_TOKENS, next).commit();
            return transitioned;
        }
    }

    /** Remove a token from the record (e.g. on refund / revoke). Tests use this too. */
    void forget(@NonNull String purchaseToken) {
        synchronized (LOCK) {
            Set<String> set = prefs.getStringSet(KEY_AUTO_RENEWING_TOKENS, null);
            if (set == null || !set.contains(purchaseToken)) return;
            Set<String> copy = new HashSet<>(set);
            copy.remove(purchaseToken);
            prefs.edit().putStringSet(KEY_AUTO_RENEWING_TOKENS, copy).commit();
        }
    }

    void clearAll() {
        synchronized (LOCK) {
            prefs.edit().remove(KEY_AUTO_RENEWING_TOKENS).commit();
        }
    }
}
