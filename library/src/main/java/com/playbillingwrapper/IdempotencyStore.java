package com.playbillingwrapper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Persistent dedupe ledger keyed on {@code purchaseToken}.
 * <p>
 * {@code BillingClient} can redeliver the same {@code PURCHASED} event across app restarts
 * (fresh {@code queryPurchasesAsync} responses, duplicate {@code PurchasesUpdatedListener}
 * callbacks, etc.). Without a persistent ledger the caller would double-grant the
 * underlying entitlement (e.g. coins added twice). This class records purchase tokens that
 * the caller has already handled and survives app-kill / device reboot.
 */
public final class IdempotencyStore {

    private static final String DEFAULT_PREFS_NAME = "pbw_idempotency";
    private static final String KEY_HANDLED_TOKENS = "handled_tokens";

    private final SharedPreferences prefs;

    public IdempotencyStore(@NonNull Context context) {
        this(context, DEFAULT_PREFS_NAME);
    }

    /**
     * Create an isolated dedupe store. Useful when a caller needs separate ledgers for
     * distinct event types (e.g. purchase delivery vs. subscription-cancellation), so that
     * forgetting one type does not affect the other.
     */
    public IdempotencyStore(@NonNull Context context, @NonNull String prefsName) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    /**
     * Mark a purchase token as fully handled (entitlement granted and, if applicable,
     * acknowledged / consumed).
     */
    public synchronized void markHandled(@NonNull String purchaseToken) {
        Set<String> handled = new HashSet<>(prefs.getStringSet(KEY_HANDLED_TOKENS, new HashSet<>()));
        handled.add(purchaseToken);
        prefs.edit().putStringSet(KEY_HANDLED_TOKENS, handled).commit();
    }

    /** True if the token has been marked handled before. */
    public synchronized boolean isHandled(@NonNull String purchaseToken) {
        Set<String> handled = prefs.getStringSet(KEY_HANDLED_TOKENS, null);
        return handled != null && handled.contains(purchaseToken);
    }

    /**
     * Remove a purchase token from the handled set. Call after a refund / void notification
     * so subsequent re-purchases with a recycled token (rare but possible) are handled afresh.
     */
    public synchronized void forget(@NonNull String purchaseToken) {
        Set<String> handled = prefs.getStringSet(KEY_HANDLED_TOKENS, null);
        if (handled == null || !handled.contains(purchaseToken)) return;
        Set<String> copy = new HashSet<>(handled);
        copy.remove(purchaseToken);
        prefs.edit().putStringSet(KEY_HANDLED_TOKENS, copy).commit();
    }

    /** Clear every recorded token. Useful for tests; do not call in production. */
    public synchronized void clearAll() {
        prefs.edit().remove(KEY_HANDLED_TOKENS).commit();
    }
}
