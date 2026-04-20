package com.playbillingwrapper.type;

/**
 * Classification for billing-error surfaces. Every {@code BillingResponse} produced by the
 * library carries one. Most values map directly to {@code BillingClient.BillingResponseCode}
 * (USER_CANCELED, ITEM_UNAVAILABLE, BILLING_UNAVAILABLE, DEVELOPER_ERROR, ERROR,
 * SERVICE_UNAVAILABLE, NETWORK_ERROR, ITEM_ALREADY_OWNED, ITEM_NOT_OWNED). The remaining
 * values describe library-layer conditions that don't have a one-to-one Play equivalent.
 */
public enum ErrorType {

    /** BillingClient has not finished {@code startConnection} or its setup returned non-OK. */
    CLIENT_NOT_READY,

    /** Play Billing service link dropped; the library is retrying with exponential backoff. */
    CLIENT_DISCONNECTED,

    /** The product id does not appear in any of the three configured lists. */
    PRODUCT_NOT_EXIST,

    /** Play returned OK from {@code queryProductDetailsAsync} but one specific product id was missing. */
    PRODUCT_ID_QUERY_FAILED,

    /** {@code consumeAsync} call failed; the consumable purchase still owes a consume attempt. */
    CONSUME_ERROR,

    /** A caller tried to consume a purchase that is still in PENDING state. */
    CONSUME_WARNING,

    /** {@code acknowledgePurchase} call failed; the 72h auto-refund window is still ticking. */
    ACKNOWLEDGE_ERROR,

    /** A caller tried to acknowledge a purchase that is still in PENDING state. */
    ACKNOWLEDGE_WARNING,

    /** {@code queryPurchasesAsync} call returned a non-OK result. */
    FETCH_PURCHASED_PRODUCTS_ERROR,

    /** Catch-all billing failure the library could not classify more specifically. */
    BILLING_ERROR,

    /** Play Billing reports {@code NETWORK_ERROR}. */
    NETWORK_ERROR,

    /** The user cancelled the Play purchase dialog. */
    USER_CANCELED,

    /** Play Billing reports {@code SERVICE_UNAVAILABLE}. */
    SERVICE_UNAVAILABLE,

    /** Play Billing reports {@code BILLING_UNAVAILABLE} — Play Store missing, outdated, or country unsupported. */
    BILLING_UNAVAILABLE,

    /** The product exists but cannot be purchased right now (region-locked, inactive, etc.). */
    ITEM_UNAVAILABLE,

    /** The library caller passed invalid arguments (null offer token, unknown base plan id, etc.). */
    DEVELOPER_ERROR,

    /** Play Billing reports a generic {@code ERROR}. */
    ERROR,

    /** Play Billing reports {@code ITEM_ALREADY_OWNED} — the caller should restore instead of re-purchasing. */
    ITEM_ALREADY_OWNED,

    /** Play Billing reports {@code ITEM_NOT_OWNED} — attempted to consume something the user doesn't own. */
    ITEM_NOT_OWNED,

    /** {@code com.android.vending} is not installed or cannot handle Play Store URLs on this device. */
    PLAY_STORE_NOT_INSTALLED,

    /** Caller invoked {@code retryPendingPurchase(productId)} but there is no pending purchase for that id. */
    NOT_PENDING,

    /** A previously PENDING purchase disappeared from Play's records (user cancelled the slow payment). */
    PENDING_PURCHASE_CANCELED,

    /** Pending-purchase retry loop exhausted the configured number of in-app retries; token is preserved for the next reconnect. */
    PENDING_PURCHASE_RETRY_ERROR,
}
