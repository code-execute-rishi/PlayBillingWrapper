package com.playbillingwrapper;

import static com.android.billingclient.api.BillingClient.BillingResponseCode.BILLING_UNAVAILABLE;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.DEVELOPER_ERROR;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.ERROR;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_NOT_OWNED;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_UNAVAILABLE;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.NETWORK_ERROR;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.OK;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_DISCONNECTED;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE;
import static com.android.billingclient.api.BillingClient.BillingResponseCode.USER_CANCELED;
import static com.android.billingclient.api.BillingClient.FeatureType.SUBSCRIPTIONS;
import static com.android.billingclient.api.BillingClient.ProductType.INAPP;
import static com.android.billingclient.api.BillingClient.ProductType.SUBS;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.playbillingwrapper.type.ErrorType;
import com.playbillingwrapper.type.ProductType;
import com.playbillingwrapper.status.PurchasedResult;
import com.playbillingwrapper.type.SkuProductType;
import com.playbillingwrapper.status.SupportState;
import com.playbillingwrapper.listener.AcknowledgeEventListener;
import com.playbillingwrapper.listener.BillingEventListener;
import com.playbillingwrapper.listener.ConsumeEventListener;
import com.playbillingwrapper.model.BillingResponse;
import com.playbillingwrapper.model.ProductInfo;
import com.playbillingwrapper.model.PurchaseInfo;

public class BillingConnector implements DefaultLifecycleObserver {

    private final Handler uiHandler;

    private static final String TAG = "BillingConnector";
    private static final int defaultResponseCode = 99; // Custom response code not used by the official BillingClient API

    private static final int notAnOffer = -1;

    private static final long RECONNECT_TIMER_START_MILLISECONDS = 1000L;
    private static final long RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L;
    private final AtomicLong reconnectMilliseconds = new AtomicLong(RECONNECT_TIMER_START_MILLISECONDS);

    private static final int DEFAULT_MAX_PENDING_RETRIES = 60;
    private static final long INITIAL_RETRY_DELAY_MS = 1000L;
    private static final long MAX_RETRY_DELAY_MS = 60_000L;
    // Real PENDING (cash, bank transfer) may take hours/days. Default is effectively unbounded.
    private static final long DEFAULT_MAX_PENDING_DURATION_MS = Long.MAX_VALUE;

    private int maxPendingRetries = DEFAULT_MAX_PENDING_RETRIES;
    private long maxPendingDurationMs = DEFAULT_MAX_PENDING_DURATION_MS;

    private String obfuscatedAccountId;
    private String obfuscatedProfileId;

    private final String base64Key;

    private final Context context;
    private Lifecycle lifecycle;

    private BillingClient billingClient;
    private BillingEventListener billingEventListener;

    private List<String> consumableIds;
    private List<String> nonConsumableIds;
    private List<String> subscriptionIds;

    private final List<QueryProductDetailsParams.Product> allProductList = new ArrayList<>();

    private final List<ProductInfo> fetchedProductInfoList = new CopyOnWriteArrayList<>();
    private final List<PurchaseInfo> purchasedProductsList = new ArrayList<>();

    private final Object purchasedProductsSync = new Object(); // Object for thread safety

    private final AtomicInteger productDetailsQueriesPending = new AtomicInteger(0);
    private final AtomicInteger purchaseQueriesPending = new AtomicInteger(0);

    private boolean shouldAutoAcknowledge = false;
    private boolean shouldAutoConsume = false;
    private boolean shouldEnableLogging = false;

    private volatile boolean isConnected = false;
    private volatile boolean fetchedPurchasedProducts = false;
    private volatile boolean released = false;

    /** Drop-in no-op listener used after release() so posted lambdas cannot NPE. */
    private static final BillingEventListener NOOP_LISTENER = new BillingEventListener() {
        @Override public void onProductsFetched(@NonNull List<ProductInfo> productDetails) { }
        @Override public void onPurchasedProductsFetched(@NonNull ProductType productType, @NonNull List<PurchaseInfo> purchases) { }
        @Override public void onProductsPurchased(@NonNull List<PurchaseInfo> purchases) { }
        @Override public void onPurchaseAcknowledged(@NonNull PurchaseInfo purchase) { }
        @Override public void onPurchaseConsumed(@NonNull PurchaseInfo purchase) { }
        @Override public void onBillingError(@NonNull BillingConnector billingConnector, @NonNull BillingResponse response) { }
        @Override public void onProductQueryError(@NonNull String productId, @NonNull BillingResponse response) { }
    };

    /**
     * Returns a never-null listener for dispatching events from callbacks. If the connector
     * has been released or no listener was ever registered, a no-op is returned so posted
     * lambdas cannot dereference a null and crash.
     */
    @NonNull
    private BillingEventListener safe() {
        if (released) return NOOP_LISTENER;
        BillingEventListener l = billingEventListener;
        return l == null ? NOOP_LISTENER : l;
    }

    /**
     * BillingConnector public constructor
     *
     * @param context   - is the application context
     * @param base64Key - is the public developer key from Play Console
     * @param lifecycle - (optional) the lifecycle object to automatically manage the BillingConnector's
     *                  lifecycle. If provided, the connector will automatically handle connection
     *                  cleanup when the lifecycle owner is destroyed. Can be null if manual lifecycle
     *                  management is preferred.
     */
    public BillingConnector(@NonNull Context context, @Nullable String base64Key, @Nullable Lifecycle lifecycle) {
        this.context = context.getApplicationContext();
        this.base64Key = base64Key;
        if (lifecycle != null) {
            this.lifecycle = lifecycle;
            lifecycle.addObserver(this);
        }
        this.uiHandler = new Handler(Looper.getMainLooper());
        this.init();
    }

    /**
     * To initialize BillingConnector
     */
    private void init() {
        billingClient = BillingClient.newBuilder(context)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enablePrepaidPlans().enableOneTimeProducts().build())
                .setListener(this::onPurchasesUpdated)
                .build();
    }

    private void onPurchasesUpdated(@NonNull BillingResult billingResult, List<Purchase> purchases) {
        switch (billingResult.getResponseCode()) {
            case OK:
                if (purchases != null) {
                    processPurchases(ProductType.COMBINED, purchases, false);
                }
                break;
            case USER_CANCELED:
                Log("User pressed back or canceled a dialog." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.USER_CANCELED, billingResult)));
                break;
            case SERVICE_UNAVAILABLE:
                Log("Network connection is down." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.SERVICE_UNAVAILABLE, billingResult)));
                break;
            case BILLING_UNAVAILABLE:
                Log("Billing API version is not supported for the type requested." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.BILLING_UNAVAILABLE, billingResult)));
                break;
            case ITEM_UNAVAILABLE:
                Log("Requested product is not available for purchase." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.ITEM_UNAVAILABLE, billingResult)));
                break;
            case DEVELOPER_ERROR:
                Log("Invalid arguments provided to the API." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.DEVELOPER_ERROR, billingResult)));
                break;
            case ERROR:
                Log("Fatal error during the API action." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.ERROR, billingResult)));
                break;
            case ITEM_ALREADY_OWNED:
                Log("Failure to purchase since item is already owned." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.ITEM_ALREADY_OWNED, billingResult)));
                break;
            case ITEM_NOT_OWNED:
                Log("Failure to consume since item is not owned." + " Response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.ITEM_NOT_OWNED, billingResult)));
                break;
            case SERVICE_DISCONNECTED:
                Log("Initialization error: service disconnected/timeout. Trying to reconnect...");
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.CLIENT_DISCONNECTED, billingResult)));
                break;
            case NETWORK_ERROR:
                Log("Initialization error: service network error. Trying to reconnect...");
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.NETWORK_ERROR, billingResult)));
                break;
            default:
                Log("Initialization error: " + new BillingResponse(ErrorType.BILLING_ERROR, billingResult));
                break;
        }
    }

    /**
     * To attach an event listener to establish a bridge with the caller
     */
    public final void setBillingEventListener(BillingEventListener billingEventListener) {
        this.billingEventListener = billingEventListener;
    }

    /**
     * To set consumable products IDs
     */
    public final BillingConnector setConsumableIds(List<String> consumableIds) {
        this.consumableIds = consumableIds;
        return this;
    }

    /**
     * To set non-consumable products IDs
     */
    public final BillingConnector setNonConsumableIds(List<String> nonConsumableIds) {
        this.nonConsumableIds = nonConsumableIds;
        return this;
    }

    /**
     * To set subscription products IDs
     */
    public final BillingConnector setSubscriptionIds(List<String> subscriptionIds) {
        this.subscriptionIds = subscriptionIds;
        return this;
    }

    /**
     * To auto acknowledge the purchase
     */
    public final BillingConnector autoAcknowledge() {
        shouldAutoAcknowledge = true;
        return this;
    }

    /**
     * To auto consume the purchase
     */
    public final BillingConnector autoConsume() {
        shouldAutoConsume = true;
        return this;
    }

    /**
     * To enable logging for debugging
     */
    public final BillingConnector enableLogging() {
        shouldEnableLogging = true;
        return this;
    }

    /**
     * Attach an obfuscated account id (hashed stable user id, never PII).
     * Required by Google for fraud detection, trial-per-account enforcement,
     * and for mapping purchaseToken -> your user id on the server.
     *
     * @param obfuscatedAccountId one-way hash of the application's user id
     */
    public final BillingConnector setObfuscatedAccountId(String obfuscatedAccountId) {
        this.obfuscatedAccountId = obfuscatedAccountId;
        return this;
    }

    /**
     * Optional: attach an obfuscated profile id (hashed profile identifier for apps
     * that support multiple profiles per account).
     */
    public final BillingConnector setObfuscatedProfileId(String obfuscatedProfileId) {
        this.obfuscatedProfileId = obfuscatedProfileId;
        return this;
    }

    /**
     * Configure maximum number of pending-purchase retry attempts.
     * Default: 60 retries with exponential backoff (capped at 60s per delay).
     */
    public final BillingConnector setMaxPendingRetries(int maxPendingRetries) {
        this.maxPendingRetries = maxPendingRetries;
        return this;
    }

    /**
     * Configure maximum total duration to keep retrying a pending purchase.
     * Default: Long.MAX_VALUE (effectively unbounded, since real PENDING can take days).
     *
     * @param maxPendingDurationMs total wall-clock millis before abandoning retries
     */
    public final BillingConnector setMaxPendingDurationMs(long maxPendingDurationMs) {
        this.maxPendingDurationMs = maxPendingDurationMs;
        return this;
    }

    /**
     * Returns the state of the billing client
     */
    public final boolean isReady() {
        if (!isConnected) {
            Log("Billing client is not ready because no connection is established yet");
        }

        if (!billingClient.isReady()) {
            Log("Billing client is not ready yet");
        }

        return isConnected && billingClient.isReady() && !fetchedProductInfoList.isEmpty();
    }

    /**
     * Returns a boolean state of the product
     *
     * @param productId - is the product ID that has to be checked
     */
    private boolean checkProductBeforeInteraction(String productId) {
        if (!isReady()) {
            findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.CLIENT_NOT_READY,
                    "Client is not ready yet", defaultResponseCode)));
            return false;
        }

        boolean productExists = false;
        if (productId != null) {
            for (ProductInfo productInfo : fetchedProductInfoList) {
                if (productInfo.getProduct().equals(productId)) {
                    productExists = true;
                    break;
                }
            }
        }

        if (productId != null && !productExists) {
            findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.PRODUCT_NOT_EXIST,
                    "The product ID: " + productId + " doesn't seem to exist on Play Console", defaultResponseCode)));
            return false;
        }
        return true;
    }

    /**
     * To connect the billing client with Play Console
     */
    public final BillingConnector connect() {
        if (!isPlayStoreInstalled(context)) {
            findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.PLAY_STORE_NOT_INSTALLED,
                    "Google Play Store is not installed", BILLING_UNAVAILABLE)));
            return this;
        }

        List<QueryProductDetailsParams.Product> productInAppList = new ArrayList<>();
        List<QueryProductDetailsParams.Product> productSubsList = new ArrayList<>();

        // Set the empty list to null so we only have to deal with lists that are null or not empty
        if (consumableIds == null || consumableIds.isEmpty()) {
            consumableIds = null;
        } else {
            for (String id : consumableIds) {
                productInAppList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(INAPP).build());
            }
        }

        if (nonConsumableIds == null || nonConsumableIds.isEmpty()) {
            nonConsumableIds = null;
        } else {
            for (String id : nonConsumableIds) {
                productInAppList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(INAPP).build());
            }
        }

        if (subscriptionIds == null || subscriptionIds.isEmpty()) {
            subscriptionIds = null;
        } else {
            for (String id : subscriptionIds) {
                productSubsList.add(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(SUBS).build());
            }
        }

        // Clear the list to prevent duplicates during a reconnection attempt
        allProductList.clear();

        allProductList.addAll(productInAppList);
        allProductList.addAll(productSubsList);

        // Check if any list is provided
        if (allProductList.isEmpty()) {
            throw new IllegalArgumentException("At least one list of consumables, non-consumables or subscriptions is needed");
        }

        // Check for duplicates product IDs
        int allIdsSize = allProductList.size();
        int allIdsSizeDistinct = new HashSet<>(allProductList).size();
        if (allIdsSize != allIdsSizeDistinct) {
            throw new IllegalArgumentException("The product ID must appear only once in a list. Also, it must not be in different lists");
        }

        Log("Billing service: connecting...");
        if (!billingClient.isReady()) {
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingServiceDisconnected() {
                    isConnected = false;

                    findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.CLIENT_DISCONNECTED,
                            "Billing service: disconnected", defaultResponseCode)));

                    Log("Billing service: Trying to reconnect...");
                    retryBillingClientConnection();
                }

                @Override
                public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                    switch (billingResult.getResponseCode()) {
                        case OK:
                            isConnected = true;
                            Log("Billing service: connected");

                            // Reset the reconnect timer on successful connection
                            reconnectMilliseconds.set(RECONNECT_TIMER_START_MILLISECONDS);

                            runProductAndPurchaseQueries(productInAppList, productSubsList);
                            break;
                        case BILLING_UNAVAILABLE:
                            Log("Billing service: unavailable");
                            retryBillingClientConnection();
                            break;
                        default:
                            Log("Billing service: error");
                            retryBillingClientConnection();
                            break;
                    }
                }
            });
        } else {
            // Already connected -- re-run queries so that restorePurchases() / onResume refresh actually works.
            runProductAndPurchaseQueries(productInAppList, productSubsList);
        }

        return this;
    }

    /**
     * Kicks off product-detail and purchase queries. Safe to call multiple times -- the
     * fetched product cache is reset at the start so sibling results don't stale-clobber.
     */
    private void runProductAndPurchaseQueries(
            @NonNull List<QueryProductDetailsParams.Product> productInAppList,
            @NonNull List<QueryProductDetailsParams.Product> productSubsList) {
        // Reset the fetched cache so concurrent INAPP/SUBS callbacks both have a clean slate
        // to append into -- never wipe the sibling's results.
        fetchedProductInfoList.clear();

        int queryCount = 0;
        if (!productInAppList.isEmpty()) queryCount++;
        if (!productSubsList.isEmpty()) queryCount++;
        productDetailsQueriesPending.set(queryCount);

        if (!productInAppList.isEmpty()) {
            queryProductDetails(INAPP, productInAppList);
        }
        if (!productSubsList.isEmpty()) {
            queryProductDetails(SUBS, productSubsList);
        }
    }

    /**
     * Retries the billing client connection with exponential backoff
     * Max out at the time specified by RECONNECT_TIMER_MAX_TIME_MILLISECONDS (15 minutes)
     */
    private void retryBillingClientConnection() {
        long currentDelay = reconnectMilliseconds.get();
        findUiHandler().postDelayed(this::connect, currentDelay);

        long currentVal, newVal;
        do {
            currentVal = reconnectMilliseconds.get();
            newVal = Math.min(currentVal * 2, RECONNECT_TIMER_MAX_TIME_MILLISECONDS);
        } while (!reconnectMilliseconds.compareAndSet(currentVal, newVal));
    }

    /**
     * Fires a query in Play Console to show products available to purchase
     */
    private void queryProductDetails(String productType, List<QueryProductDetailsParams.Product> productList) {
        QueryProductDetailsParams productDetailsParams = QueryProductDetailsParams.newBuilder().setProductList(productList).build();

        billingClient.queryProductDetailsAsync(productDetailsParams, (billingResult, productDetailsResult) -> {
            if (billingResult.getResponseCode() == OK) {
                List<ProductDetails> productDetailsList = productDetailsResult.getProductDetailsList();

                HashSet<String> foundProductIds = new HashSet<>();
                for (ProductDetails details : productDetailsList) {
                    foundProductIds.add(details.getProductId());
                }

                for (QueryProductDetailsParams.Product requestedProduct : productList) {
                    String productId = requestedProduct.zza(); // .zza() gets the product ID string
                    if (!foundProductIds.contains(productId)) {
                        Log("Error: Product ID '" + productId + "' not found. " +
                                "Make sure it is configured correctly in the Play Console");
                        findUiHandler().post(() -> safe().onProductQueryError(productId, new BillingResponse(ErrorType.PRODUCT_ID_QUERY_FAILED,
                                "Product ID '" + productId + "' not found", defaultResponseCode)
                        ));
                    }
                }

                if (productDetailsList.isEmpty()) {
                    Log("Query Product Details: No valid products found. Make sure product IDs are configured on Play Console");
                    findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.BILLING_ERROR,
                            "No products found", defaultResponseCode)));
                    // Still decrement so the sibling INAPP/SUBS query can drive purchase reconciliation.
                    if (productDetailsQueriesPending.decrementAndGet() == 0) {
                        fetchPurchasedProducts();
                    }
                } else {
                    Log("Query Product Details: data found for " + productDetailsList.size() + " products");

                    List<ProductInfo> fetchedProductInfo = new ArrayList<>();
                    for (ProductDetails productDetails : productDetailsList) {
                        fetchedProductInfo.add(generateProductInfo(productDetails));
                    }

                    // Append this query group's results; do NOT clear, or the sibling
                    // (INAPP vs SUBS) query's results would be wiped.
                    fetchedProductInfoList.addAll(fetchedProductInfo);

                    switch (productType) {
                        case INAPP:
                        case SUBS:
                            findUiHandler().post(() -> safe().onProductsFetched(fetchedProductInfo));
                            break;
                        default:
                            throw new IllegalStateException("Product type is not implemented");
                    }

                    if (productDetailsQueriesPending.decrementAndGet() == 0) {
                        fetchPurchasedProducts();
                    }
                }
            } else {
                Log("Query Product Details: failed with response code: " + billingResult.getResponseCode());
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                        new BillingResponse(ErrorType.BILLING_ERROR, billingResult)));

                // Unblock the pipeline even if this specific query failed (API response code is not OK)
                if (productDetailsQueriesPending.decrementAndGet() == 0) {
                    fetchPurchasedProducts();
                }
            }
        });
    }

    /**
     * Returns a new ProductInfo object containing the product type and product details
     *
     * @param productDetails - is the object provided by the billing client API
     */
    @NonNull
    private ProductInfo generateProductInfo(@NonNull ProductDetails productDetails) {
        SkuProductType skuProductType;

        switch (productDetails.getProductType()) {
            case INAPP:
                boolean consumable = isProductIdConsumable(productDetails.getProductId());
                if (consumable) {
                    skuProductType = SkuProductType.CONSUMABLE;
                } else {
                    skuProductType = SkuProductType.NON_CONSUMABLE;
                }
                break;
            case SUBS:
                skuProductType = SkuProductType.SUBSCRIPTION;
                break;
            default:
                throw new IllegalStateException("Product type is not implemented correctly");
        }

        return new ProductInfo(skuProductType, productDetails);
    }

    private boolean isProductIdConsumable(String productId) {
        if (consumableIds == null) {
            return false;
        }

        return consumableIds.contains(productId);
    }

    /**
     * Returns purchases details for currently owned items without a network request
     */
    private void fetchPurchasedProducts() {
        if (billingClient.isReady()) {
            int queryCount = 1;
            if (isSubscriptionSupported() == SupportState.SUPPORTED) {
                queryCount++;
            }
            purchaseQueriesPending.set(queryCount);

            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(INAPP).build(),
                    (billingResult, purchases) -> {
                        if (billingResult.getResponseCode() == OK) {
                            Log("Query IN-APP Purchases: " + (purchases.isEmpty() ? "empty" : purchases.size() + " found"));
                            processPurchases(ProductType.INAPP, purchases, true);
                        } else {
                            Log("Query IN-APP Purchases: failed - " + billingResult.getDebugMessage());
                            notifyBillingResultError(billingResult);
                            completePurchaseQuery();
                        }
                    }
            );

            // Query subscription purchases for supported devices
            if (isSubscriptionSupported() == SupportState.SUPPORTED) {
                billingClient.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder().setProductType(SUBS).build(),
                        (billingResult, purchases) -> {
                            if (billingResult.getResponseCode() == OK) {
                                Log("Query SUBS Purchases: " + (purchases.isEmpty() ? "empty" : purchases.size() + " found"));
                                processPurchases(ProductType.SUBS, purchases, true);
                            } else {
                                Log("Query SUBS Purchases: failed - " + billingResult.getDebugMessage());
                                notifyBillingResultError(billingResult);
                                completePurchaseQuery();
                            }
                        }
                );
            }

        } else {
            findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.FETCH_PURCHASED_PRODUCTS_ERROR,
                    "Billing client is not ready yet", defaultResponseCode)));
        }
    }

    /**
     * Before using subscriptions, device-support must be checked
     * Not all devices support subscriptions
     */
    public SupportState isSubscriptionSupported() {
        BillingResult response = billingClient.isFeatureSupported(SUBSCRIPTIONS);
        SupportState state;

        switch (response.getResponseCode()) {
            case OK:
                Log("Subscriptions support check: success");
                state = SupportState.SUPPORTED;
                break;
            case SERVICE_DISCONNECTED:
                Log("Subscriptions support check: disconnected. Trying to reconnect...");
                state = SupportState.DISCONNECTED;
                break;
            default:
                Log("Subscriptions support check: error -> " + response.getResponseCode() + " " + response.getDebugMessage());
                state = SupportState.NOT_SUPPORTED;
                break;
        }
        return state;
    }

    /**
     * Checks purchases signature for more security
     */
    private void processPurchases(ProductType productType, @NonNull List<Purchase> allPurchases, boolean purchasedProductsFetched) {
        List<PurchaseInfo> signatureValidPurchases = new ArrayList<>();

        List<Purchase> validPurchases = new ArrayList<>();
        for (Purchase purchase : allPurchases) {
            if (isPurchaseSignatureValid(purchase)) {
                validPurchases.add(purchase);
            }
        }

        Map<String, ProductInfo> productInfoMap = new HashMap<>();
        for (ProductInfo productInfo : fetchedProductInfoList) {
            productInfoMap.put(productInfo.getProduct(), productInfo);
        }

        for (Purchase purchase : validPurchases) {
            for (String productId : purchase.getProducts()) {
                ProductInfo foundProductInfo = productInfoMap.get(productId);
                if (foundProductInfo != null) {
                    PurchaseInfo purchaseInfo = new PurchaseInfo(foundProductInfo, purchase);
                    signatureValidPurchases.add(purchaseInfo);
                }
            }
        }

        // Synchronize access to purchasedProductsList
        synchronized (purchasedProductsSync) {
            // Clear existing purchases of this type when fetching (to avoid duplicates)
            if (purchasedProductsFetched) {
                Iterator<PurchaseInfo> iterator = purchasedProductsList.iterator();
                while (iterator.hasNext()) {
                    PurchaseInfo purchaseInfo = iterator.next();
                    boolean isSubscription = purchaseInfo.getSkuProductType() == SkuProductType.SUBSCRIPTION;

                    if (productType == ProductType.SUBS && isSubscription) {
                        iterator.remove();
                    } else if (productType == ProductType.INAPP && !isSubscription) {
                        iterator.remove();
                    } else if (productType == ProductType.COMBINED) {
                        iterator.remove();
                    }
                }
            }

            // Add new purchases
            purchasedProductsList.addAll(signatureValidPurchases);
        }

        if (purchasedProductsFetched) {
            findUiHandler().post(() -> safe().onPurchasedProductsFetched(productType, signatureValidPurchases));
            completePurchaseQuery();
        } else {
            findUiHandler().post(() -> safe().onProductsPurchased(signatureValidPurchases));
            // Also honor any PENDING entries that came in via PurchasesUpdatedListener so
            // the caller doesn't need to manually call retryPendingPurchase().
            for (PurchaseInfo info : signatureValidPurchases) {
                if (info.isPending()) {
                    Log("Auto-scheduling pending-purchase retry (live flow) for: " + info.getProduct());
                    retryPurchaseWithBackoff(info, 0, System.currentTimeMillis());
                }
            }
        }

        for (PurchaseInfo purchaseInfo : signatureValidPurchases) {
            if (shouldAutoConsume) {
                consumePurchase(purchaseInfo);
            }
            if (shouldAutoAcknowledge) {
                boolean isProductConsumable = purchaseInfo.getSkuProductType() == SkuProductType.CONSUMABLE;
                if (!isProductConsumable) {
                    acknowledgePurchase(purchaseInfo);
                }
            }
        }
    }

    /**
     * Consume consumable products so that the user can buy the item again
     * <p>
     * Consumable products might be bought/consumed by users multiple times (for e.g. diamonds, coins etc.)
     * They have to be consumed within 3 days, otherwise Google will refund the products
     */
    public void consumePurchase(@NonNull PurchaseInfo purchaseInfo) {
        if (checkProductBeforeInteraction(purchaseInfo.getProduct())) {
            if (purchaseInfo.getSkuProductType() == SkuProductType.CONSUMABLE) {
                if (purchaseInfo.getPurchase().getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    ConsumeParams consumeParams = ConsumeParams.newBuilder()
                            .setPurchaseToken(purchaseInfo.getPurchase().getPurchaseToken()).build();

                    billingClient.consumeAsync(consumeParams, (billingResult, purchaseToken) -> {
                        if (billingResult.getResponseCode() == OK) {
                            synchronized (purchasedProductsSync) {
                                purchasedProductsList.remove(purchaseInfo);
                            }
                            findUiHandler().post(() -> safe().onPurchaseConsumed(purchaseInfo));
                        } else {
                            Log("Handling consumables: error during consumption attempt: " + billingResult.getDebugMessage());

                            findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                                    new BillingResponse(ErrorType.CONSUME_ERROR, billingResult)));
                        }
                    });
                } else if (purchaseInfo.getPurchase().getPurchaseState() == Purchase.PurchaseState.PENDING) {
                    Log("Handling consumables: purchase can not be consumed because the state is PENDING. " +
                            "A purchase can be consumed only when the state is PURCHASED");

                    findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.CONSUME_WARNING,
                            "Warning: purchase can not be consumed because the state is PENDING. Please consume the purchase later", defaultResponseCode)));
                }
            }
        }
    }

    /**
     * Acknowledge non-consumable products & subscriptions
     * <p>
     * This will avoid refunding for these products to users by Google
     */
    public void acknowledgePurchase(@NonNull PurchaseInfo purchaseInfo) {
        if (checkProductBeforeInteraction(purchaseInfo.getProduct())) {
            switch (purchaseInfo.getSkuProductType()) {
                case NON_CONSUMABLE:
                case SUBSCRIPTION:
                    if (purchaseInfo.getPurchase().getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                        if (!purchaseInfo.getPurchase().isAcknowledged()) {
                            AcknowledgePurchaseParams acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchaseInfo.getPurchase().getPurchaseToken()).build();

                            billingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult -> {
                                if (billingResult.getResponseCode() == OK) {
                                    findUiHandler().post(() -> safe().onPurchaseAcknowledged(purchaseInfo));
                                } else {
                                    Log("Handling acknowledges: error during acknowledgment attempt: " + billingResult.getDebugMessage());

                                    findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                                            new BillingResponse(ErrorType.ACKNOWLEDGE_ERROR, billingResult)));
                                }
                            });
                        }
                    } else if (purchaseInfo.getPurchase().getPurchaseState() == Purchase.PurchaseState.PENDING) {
                        Log("Handling acknowledges: purchase can not be acknowledged because the state is PENDING. " +
                                "A purchase can be acknowledged only when the state is PURCHASED");

                        findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.ACKNOWLEDGE_WARNING,
                                "Warning: purchase can not be acknowledged because the state is PENDING. Please acknowledge the purchase later", defaultResponseCode)));
                    }
                    break;
            }
        }
    }

    /**
     * Called to purchase a one-time product (consumable or non-consumable).
     * For subscriptions prefer {@link #purchaseSubscription(Activity, String, String)}.
     */
    public final void purchase(Activity activity, String productId) {
        purchaseInternal(activity, productId, null, notAnOffer);
    }

    /**
     * Launches the billing flow with an explicit subscription offer token.
     * Callers should pick the token via {@link OfferSelector} rather than hard-coding an index.
     *
     * @param activity   foreground Activity to host the Play Billing dialog
     * @param productId  the subscription product id (must match a Play Console product)
     * @param offerToken the selected offer's token from {@code ProductDetails.getSubscriptionOfferDetails()}
     */
    public final void purchaseSubscription(Activity activity, String productId, String offerToken) {
        if (offerToken == null || offerToken.isEmpty()) {
            findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                    new BillingResponse(ErrorType.DEVELOPER_ERROR,
                            "offerToken must not be null/empty for subscription purchases. " +
                                    "Use OfferSelector to pick a token.", defaultResponseCode)));
            return;
        }
        purchaseInternal(activity, productId, offerToken, -1);
    }

    private void purchaseInternal(Activity activity, String productId, String offerToken, int legacyOfferIndex) {
        if (checkProductBeforeInteraction(productId)) {
            ProductInfo foundProductInfo = null;
            for (ProductInfo productInfo : fetchedProductInfoList) {
                if (productInfo.getProduct().equals(productId)) {
                    foundProductInfo = productInfo;
                    break;
                }
            }

            if (foundProductInfo != null) {
                ProductDetails productDetails = foundProductInfo.getProductDetails();
                List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList;

                if (productDetails.getProductType().equals(SUBS)) {
                    List<ProductDetails.SubscriptionOfferDetails> offerDetails = productDetails.getSubscriptionOfferDetails();
                    String resolvedToken = offerToken;

                    if (resolvedToken == null && offerDetails != null
                            && legacyOfferIndex >= 0 && legacyOfferIndex < offerDetails.size()) {
                        resolvedToken = offerDetails.get(legacyOfferIndex).getOfferToken();
                    }

                    if (resolvedToken == null) {
                        Log("No offer token resolved for subscription: " + productId);
                        findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                                new BillingResponse(ErrorType.DEVELOPER_ERROR,
                                        "No valid offer token for subscription " + productId, defaultResponseCode)));
                        return;
                    }

                    productDetailsParamsList = Collections.singletonList(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(productDetails)
                                    .setOfferToken(resolvedToken)
                                    .build()
                    );
                } else {
                    productDetailsParamsList = Collections.singletonList(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(productDetails)
                                    .build()
                    );
                }

                BillingFlowParams.Builder flowBuilder = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList);

                if (obfuscatedAccountId != null) flowBuilder.setObfuscatedAccountId(obfuscatedAccountId);
                if (obfuscatedProfileId != null) flowBuilder.setObfuscatedProfileId(obfuscatedProfileId);

                BillingResult launchResult = billingClient.launchBillingFlow(activity, flowBuilder.build());
                if (launchResult.getResponseCode() != OK) {
                    // Play can refuse the launch synchronously (e.g. stale offer token, bad
                    // Activity state). In that case onPurchasesUpdated will not fire, so we
                    // must surface the error ourselves.
                    Log("launchBillingFlow returned non-OK: " + launchResult.getResponseCode()
                            + " — " + launchResult.getDebugMessage());
                    findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                            new BillingResponse(ErrorType.BILLING_ERROR, launchResult)));
                }
            } else {
                Log("Billing client can not launch billing flow because product details are missing for product: " + productId);
                findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.PRODUCT_NOT_EXIST,
                        "Product details not found for " + productId, defaultResponseCode)));
            }
        }
    }

    /**
     * Verifies if a purchase still exists in the purchased products list
     *
     * @param purchaseInfo - the purchase to verify
     * @return true if purchase exists and is still pending, false otherwise
     */
    private boolean verifyPurchaseState(PurchaseInfo purchaseInfo) {
        synchronized (purchasedProductsSync) {
            for (PurchaseInfo info : purchasedProductsList) {
                if (info.getPurchase().getPurchaseToken().equals(purchaseInfo.getPurchase().getPurchaseToken())) {
                    return true;
                }
            }
        }

        Log("Pending purchase no longer exists: " + purchaseInfo.getProduct());
        notifyBillingError(ErrorType.PENDING_PURCHASE_CANCELED,
                "Pending purchase was removed");
        return false;
    }

    /**
     * Retries a pending purchase for the given product ID
     * <p>
     * Checks if the product is in a pending state
     * <p>
     * Retries with exponential backoff (max 3 retries)
     * <p>
     * Notifies listener of success/failure
     *
     * @param productId - the product ID to retry
     */
    public void retryPendingPurchase(String productId) {
        if (!isReady()) {
            Log("Cannot retry pending purchase: Billing client is not ready");
            notifyBillingError(ErrorType.CLIENT_NOT_READY, "Billing client is not ready");
            return;
        }

        // Synchronize the entire check to prevent races
        PurchaseInfo pendingPurchase = null;
        synchronized (purchasedProductsSync) {
            for (PurchaseInfo purchaseInfo : purchasedProductsList) {
                if (purchaseInfo.getProduct().equals(productId) && purchaseInfo.isPending()) {
                    pendingPurchase = purchaseInfo;
                    break;
                }
            }
        }

        if (pendingPurchase == null || !pendingPurchase.isPending()) {
            Log("No pending purchase found for product: " + productId);
            notifyBillingError(ErrorType.NOT_PENDING, "No pending purchase for: " + productId);
            return;
        }

        retryPurchaseWithBackoff(pendingPurchase, 0, System.currentTimeMillis());
    }

    /**
     * Retries a pending purchase with exponential backoff
     * Includes acknowledgment and consume retry logic for completed purchases
     *
     * @param purchaseInfo - the pending purchase to retry
     * @param retryCount   - current retry attempt (starts at 0)
     */
    private void retryPurchaseWithBackoff(PurchaseInfo purchaseInfo, int retryCount, long startTime) {
        if (shouldStopRetrying(purchaseInfo, retryCount, startTime)) {
            handleRetryFailure(purchaseInfo);
            return;
        }

        long delayMs = calculateRetryDelay(retryCount);
        Log("Retrying pending purchase (attempt " + (retryCount + 1) +
                " of " + maxPendingRetries + ") for: " + purchaseInfo.getProduct());

        findUiHandler().postDelayed(() -> {
            boolean shouldContinue = verifyPurchaseState(purchaseInfo);
            if (!shouldContinue) return;

            queryPurchasesForRetry(purchaseInfo, retryCount, startTime);
        }, delayMs);
    }

    /**
     * Queries purchases from Google Play for retry attempt
     *
     * @param purchaseInfo - the pending purchase being retried
     * @param retryCount   - current number of retry attempts
     * @param startTime    - timestamp when retries began (in milliseconds)
     */
    private void queryPurchasesForRetry(@NonNull PurchaseInfo purchaseInfo, int retryCount, long startTime) {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(purchaseInfo.getSkuProductType() ==
                                SkuProductType.SUBSCRIPTION ? SUBS : INAPP)
                        .build(),
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() != OK) {
                        Log("Failed to query purchases during retry: " +
                                billingResult.getDebugMessage());
                        retryPurchaseWithBackoff(purchaseInfo,
                                retryCount + 1,
                                startTime);
                        return;
                    }

                    handlePurchaseQueryResult(purchaseInfo, purchases, retryCount, startTime);
                });
    }

    /**
     * Handles the result of a purchase query during retry attempt
     *
     * @param originalInfo - the original pending purchase info
     * @param purchases    - list of purchases returned from query
     * @param retryCount   - current number of retry attempts
     * @param startTime    - timestamp when retries began (in milliseconds)
     */
    private void handlePurchaseQueryResult(PurchaseInfo originalInfo, @NonNull List<Purchase> purchases, int retryCount, long startTime) {
        Purchase completedPurchase = null;
        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseToken().equals(originalInfo.getPurchase().getPurchaseToken())) {
                completedPurchase = purchase;
                break;
            }
        }

        if (completedPurchase == null) {
            Log("Pending purchase not found, may have been canceled: " +
                    originalInfo.getProduct());
            notifyBillingError(ErrorType.PENDING_PURCHASE_CANCELED,
                    "Pending purchase may have been canceled");
            return;
        }

        if (completedPurchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            Log("Pending purchase completed: " + originalInfo.getProduct());
            handleCompletedPurchase(originalInfo, completedPurchase);
        } else {
            retryPurchaseWithBackoff(originalInfo, retryCount + 1, startTime);
        }
    }

    /**
     * Acknowledges a purchase with retry logic
     *
     * @param purchaseInfo - the purchase to acknowledge
     * @param retryCount   - current retry attempt
     * @param maxRetries   - maximum number of retries
     * @param listener     - to handle success/failure
     */
    private void acknowledgePurchaseWithRetry(@NonNull PurchaseInfo purchaseInfo, int retryCount, int maxRetries, AcknowledgeEventListener listener) {
        if (retryCount >= maxRetries) {
            Log("Max retries reached for acknowledgment: " + purchaseInfo.getProduct());
            listener.onFailure();
            return;
        }

        long delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, retryCount), MAX_RETRY_DELAY_MS);

        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseInfo.getPurchase().getPurchaseToken())
                .build();

        billingClient.acknowledgePurchase(params, billingResult -> {
            if (billingResult.getResponseCode() == OK) {
                Log("Acknowledgment successful for: " + purchaseInfo.getProduct());
                listener.onSuccess();
            } else {
                Log("Acknowledgment failed (attempt " + (retryCount + 1) +
                        "/" + maxRetries + ") for: " + purchaseInfo.getProduct() +
                        " - " + billingResult.getDebugMessage());

                findUiHandler().postDelayed(() -> acknowledgePurchaseWithRetry(purchaseInfo, retryCount + 1, maxRetries, listener), delayMs);
            }
        });
    }

    /**
     * Consumes a purchase with retry logic
     *
     * @param purchaseInfo - the purchase to consume
     * @param retryCount   - current retry attempt
     * @param maxRetries   - maximum number of retries
     * @param listener     - to handle success/failure
     */
    private void consumeWithRetry(@NonNull PurchaseInfo purchaseInfo, int retryCount, int maxRetries, @NonNull ConsumeEventListener listener) {
        if (retryCount >= maxRetries) {
            Log("Max consume retries reached for: " + purchaseInfo.getProduct());
            listener.onFailure();
            return;
        }

        long delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, retryCount), MAX_RETRY_DELAY_MS);

        ConsumeParams params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchaseInfo.getPurchase().getPurchaseToken())
                .build();

        billingClient.consumeAsync(params, (billingResult, purchaseToken) -> {
            if (billingResult.getResponseCode() == OK) {
                Log("Consume success for: " + purchaseInfo.getProduct());
                listener.onSuccess();
            } else {
                Log("Consume failed (attempt " + (retryCount + 1) +
                        "/" + maxRetries + "): " + billingResult.getDebugMessage());

                findUiHandler().postDelayed(() -> consumeWithRetry(purchaseInfo, retryCount + 1, maxRetries, listener), delayMs);
            }
        });
    }

    /**
     * Handles a completed purchase (state changed from PENDING to PURCHASED)
     * Includes consume & acknowledgment retry logic with strict state validation
     */
    private void handleCompletedPurchase(@NonNull PurchaseInfo originalInfo, @NonNull Purchase completedPurchase) {
        // Initial state verification
        if (completedPurchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            Log("Attempted to handle NON-PURCHASED item: " + completedPurchase.getPurchaseState() +
                    " for product: " + originalInfo.getProduct());
            return;
        }

        // Verify the purchase token matches
        if (!completedPurchase.getPurchaseToken().equals(originalInfo.getPurchase().getPurchaseToken())) {
            Log("Purchase token mismatch for product: " + originalInfo.getProduct());
            notifyBillingError(ErrorType.DEVELOPER_ERROR, "Purchase verification failed");
            return;
        }

        // Verify signature before proceeding
        if (!isPurchaseSignatureValid(completedPurchase)) {
            Log("Signature validation failed for completed pending purchase: " + originalInfo.getProduct());
            notifyBillingError(ErrorType.DEVELOPER_ERROR, "Signature validation failed");
            return;
        }

        PurchaseInfo completedPurchaseInfo = new PurchaseInfo(originalInfo.getProductInfo(), completedPurchase);

        // Synchronized block for thread-safe processing
        synchronized (purchasedProductsSync) {
            // Ensure original pending entry is removed when a pending purchase completes
            Iterator<PurchaseInfo> iterator = purchasedProductsList.iterator();
            while (iterator.hasNext()) {
                PurchaseInfo purchaseInfo = iterator.next();
                if (purchaseInfo.getPurchase().getPurchaseToken().equals(originalInfo.getPurchase().getPurchaseToken()) && purchaseInfo.isPending()) {
                    iterator.remove();
                    break;
                }
            }

            // Re-verify state after synchronization
            if (completedPurchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                Log("Purchase state changed during processing: " +
                        completedPurchase.getPurchaseState() +
                        " for product: " + originalInfo.getProduct());
                return;
            }

            // Manually add the newly completed purchase to the list
            purchasedProductsList.add(completedPurchaseInfo);
        }

        // Notify the listener that the purchase was successful
        findUiHandler().post(() -> safe().onProductsPurchased(Collections.singletonList(completedPurchaseInfo)));

        // Handle auto-consume for consumables
        if (shouldAutoConsume && originalInfo.getSkuProductType() == SkuProductType.CONSUMABLE) {
            consumeWithRetry(completedPurchaseInfo, 0, 3, new ConsumeEventListener() {
                @Override
                public void onSuccess() {
                    synchronized (purchasedProductsSync) {
                        purchasedProductsList.remove(completedPurchaseInfo);
                    }
                    findUiHandler().post(() ->
                            safe().onPurchaseConsumed(completedPurchaseInfo));
                }

                @Override
                public void onFailure() {
                    handleConsumeFailure(completedPurchaseInfo);
                }
            });
        }
        // Handle auto-acknowledge for non-consumables and subscriptions
        else if (shouldAutoAcknowledge && !completedPurchase.isAcknowledged()) {
            acknowledgePurchaseWithRetry(completedPurchaseInfo, 0, 3, new AcknowledgeEventListener() {
                @Override
                public void onSuccess() {
                    findUiHandler().post(() ->
                            safe().onPurchaseAcknowledged(completedPurchaseInfo));
                }

                @Override
                public void onFailure() {
                    handleAcknowledgeFailure(completedPurchaseInfo);
                }
            });
        }
    }

    /**
     * Calculates the next retry delay using exponential backoff
     *
     * @param retryCount - current number of retry attempts
     * @return delay in milliseconds before next retry attempt
     */
    private long calculateRetryDelay(int retryCount) {
        return Math.min(INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, retryCount), MAX_RETRY_DELAY_MS);
    }

    /**
     * Determines if pending purchase retries should stop based on retry count and duration
     *
     * @param purchaseInfo - the purchase being retried
     * @param retryCount   - current number of retry attempts
     * @param startTime    - timestamp when retries began (in milliseconds)
     * @return true if retries should stop, false otherwise
     */
    private boolean shouldStopRetrying(PurchaseInfo purchaseInfo, int retryCount, long startTime) {
        if (retryCount >= maxPendingRetries) {
            Log("Max retry attempts reached for: " + purchaseInfo.getProduct());
            return true;
        }

        if (System.currentTimeMillis() - startTime > maxPendingDurationMs) {
            Log("Max retry duration exceeded for: " + purchaseInfo.getProduct());
            return true;
        }

        return false;
    }

    /**
     * Handles consumption failure events after all retry attempts are exhausted
     *
     * @param purchaseInfo - contains details about the purchase that failed consumption
     */
    private void handleConsumeFailure(@NonNull PurchaseInfo purchaseInfo) {
        Log("Consume failed for: " + purchaseInfo.getProduct());
        findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.CONSUME_ERROR,
                "Failed to consume purchase", defaultResponseCode)));
    }

    /**
     * Handles acknowledgment failure events after all retry attempts are exhausted
     *
     * @param purchaseInfo - contains details about the purchase that failed acknowledgment
     */
    private void handleAcknowledgeFailure(@NonNull PurchaseInfo purchaseInfo) {
        Log("Acknowledge failed for: " + purchaseInfo.getProduct());
        findUiHandler().post(() -> safe().onBillingError(BillingConnector.this, new BillingResponse(ErrorType.ACKNOWLEDGE_ERROR,
                "Failed to acknowledge purchase", defaultResponseCode)));
    }

    /**
     * Handles failure case when max retries for a pending purchase are reached
     * <p>
     * Removes the failed purchase from the purchased products list and notifies listener
     *
     * @param purchaseInfo - the purchase that failed to complete
     */
    private void handleRetryFailure(@NonNull PurchaseInfo purchaseInfo) {
        Log("Max retries reached for pending purchase: " + purchaseInfo.getProduct() +
                ". Token is preserved; it will be reconciled on next connect() / queryPurchasesAsync.");
        // Intentionally do NOT remove the token from purchasedProductsList. Real-world PENDING
        // (cash, bank transfer) can take hours or days. The token will be reconciled on the
        // next fetch. Removing it would cause the caller to forget a purchase that is still
        // live in Google Play -- a silent revenue leak.
        notifyBillingError(ErrorType.PENDING_PURCHASE_RETRY_ERROR,
                "Pending purchase still not completed after " + maxPendingRetries + " in-app retries");
    }

    /**
     * Notifies billing event listener about an error on the UI thread
     *
     * @param errorType - type of error that occurred
     * @param message   - descriptive error message
     */
    private void notifyBillingError(ErrorType errorType, String message) {
        findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                new BillingResponse(errorType, message, defaultResponseCode)));
    }

    /**
     * Route an underlying {@link BillingResult} back to the listener as a generic billing
     * error. Used in purchase-query failure paths where we only have the Play result.
     */
    private void notifyBillingResultError(@NonNull BillingResult billingResult) {
        findUiHandler().post(() -> safe().onBillingError(BillingConnector.this,
                new BillingResponse(ErrorType.BILLING_ERROR, billingResult)));
    }

    /**
     * Called from each purchase-query completion path (success or failure). Decrements the
     * outstanding counter. When all queries have completed, sets {@code fetchedPurchasedProducts}
     * so callers can rely on {@code isPurchased(...)}, and auto-schedules retries for any
     * purchases still in PENDING state.
     */
    private void completePurchaseQuery() {
        if (purchaseQueriesPending.decrementAndGet() == 0) {
            fetchedPurchasedProducts = true;
            autoRetryPendingPurchases();
        }
    }

    /**
     * Schedule the pending-purchase retry loop for every PURCHASED-awaiting entry found in
     * the reconciled purchase list. Safe to call multiple times -- the retry loop is idempotent
     * via {@code verifyPurchaseState} + token equality.
     */
    private void autoRetryPendingPurchases() {
        List<PurchaseInfo> snapshot;
        synchronized (purchasedProductsSync) {
            snapshot = new ArrayList<>(purchasedProductsList);
        }
        for (PurchaseInfo info : snapshot) {
            if (info.isPending()) {
                Log("Auto-scheduling pending-purchase retry for: " + info.getProduct());
                retryPurchaseWithBackoff(info, 0, System.currentTimeMillis());
            }
        }
    }


    /**
     * Deep-links the user to Google Play's manage-subscription page for the given product.
     * Note: this does NOT cancel the subscription; it opens the manage UI so the user can cancel.
     */
    public final void openManageSubscription(Activity activity, String productId) {
        try {
            String url = "https://play.google.com/store/account/subscriptions?package=" + activity.getPackageName() + "&sku=" + productId;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception e) {
            Log("Failed to open manage-subscription page: " + e.getMessage());
            notifyBillingError(ErrorType.BILLING_ERROR,
                    "Could not open Play manage-subscription page: " + e.getMessage());
        }
    }

    /**
     * Legacy alias. Prefer {@link #openManageSubscription(Activity, String)} which reflects
     * that this method only opens Play's management page -- it does not cancel the subscription.
     */
    @Deprecated
    public final void unsubscribe(Activity activity, String productId) {
        openManageSubscription(activity, productId);
    }

    /**
     * Checks if a subscription is currently auto-renewing. For richer lifecycle state
     * (grace period, on hold, paused) use {@code PlayBillingWrapper.subscriptionState(productId)}.
     *
     * @param productId subscription product ID to check
     */
    public boolean isSubscriptionActive(String productId) {
        synchronized (purchasedProductsSync) {
            for (PurchaseInfo purchaseInfo : purchasedProductsList) {
                if (purchaseInfo.getProduct().equals(productId))
                    return purchaseInfo.getPurchase().isAutoRenewing();
            }
        }
        return false;
    }

    /**
     * Checks if a purchase is in pending state
     * <p>
     * Pending purchases require completion through the Google Play Store
     * and will eventually transition to PURCHASED or canceled state
     *
     * @param productId - is the product ID to check
     */
    public boolean isPurchasePending(String productId) {
        synchronized (purchasedProductsSync) {
            for (PurchaseInfo purchaseInfo : purchasedProductsList) {
                if (purchaseInfo.getProduct().equals(productId))
                    return purchaseInfo.getPurchase().getPurchaseState() == Purchase.PurchaseState.PENDING;
            }
        }
        return false;
    }

    /**
     * Checks if Google Play Store is installed on the device using a two-step verification:
     * 1. Checks for the Play Store package ("com.android.vending")
     * 2. Verifies if any app can handle Play Store URLs (fallback)
     * <p>
     * Will trigger both PLAY_STORE_NOT_INSTALLED and BILLING_UNAVAILABLE
     *
     * @param context - the application context
     * @return true if Play Store is installed, false otherwise
     */
    public boolean isPlayStoreInstalled(@NonNull Context context) {
        if (isPlayStoreInstalledByPackage(context)) {
            return true;
        }
        return canHandlePlayStoreUrl(context);
    }

    /**
     * Checks if Google Play Store is installed by verifying the existence of its package
     *
     * @param context - the application context
     * @return true if Play Store package exists, false otherwise
     */
    private boolean isPlayStoreInstalledByPackage(@NonNull Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo("com.android.vending", PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log("Google Play Store is not installed");
            return false;
        }
    }

    /**
     * Checks if any app (ideally Play Store) can handle Play Store URLs as a fallback verification
     *
     * @param context - the application context
     * @return true if an app can handle Play Store URLs and is the actual Play Store, false otherwise
     */
    private boolean canHandlePlayStoreUrl(@NonNull Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"));
        PackageManager pm = context.getPackageManager();
        ResolveInfo resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);

        if (resolveInfo == null) {
            Log("Google Play Store is not installed");
            return false;
        }

        // Verify if the resolver is actually the Play Store
        return "com.android.vending".equals(resolveInfo.activityInfo.packageName);
    }

    /**
     * Returns a list of all purchased products.
     */
    /**
     * True once both the INAPP and SUBS {@code queryPurchasesAsync} callbacks have completed
     * (success or failure). Use this to avoid reporting "ready" to higher layers before the
     * owned-products list is authoritative.
     */
    public boolean isPurchaseReconciliationComplete() {
        return fetchedPurchasedProducts;
    }

    public List<PurchaseInfo> getPurchasedProductsList() {
        synchronized (purchasedProductsSync) {
            return Collections.unmodifiableList(new ArrayList<>(purchasedProductsList));
        }
    }

    /**
     * Returns the immutable list of {@link ProductInfo} objects fetched from Play Console.
     */
    @NonNull
    public List<ProductInfo> getFetchedProductInfoList() {
        return Collections.unmodifiableList(new ArrayList<>(fetchedProductInfoList));
    }

    /**
     * Looks up a fetched {@link ProductInfo} by product id, or returns {@code null} if the
     * product has not been fetched yet.
     */
    @Nullable
    public ProductInfo findFetchedProductInfo(@NonNull String productId) {
        for (ProductInfo info : fetchedProductInfoList) {
            if (productId.equals(info.getProduct())) return info;
        }
        return null;
    }

    /**
     * Checks purchase state synchronously
     */
    public final PurchasedResult isPurchased(@NonNull ProductInfo productInfo) {
        return checkPurchased(productInfo.getProduct());
    }

    private PurchasedResult checkPurchased(String productId) {
        if (!isReady()) {
            return PurchasedResult.CLIENT_NOT_READY;
        } else if (!fetchedPurchasedProducts) {
            return PurchasedResult.PURCHASED_PRODUCTS_NOT_FETCHED_YET;
        } else {
            synchronized (purchasedProductsSync) {
                for (PurchaseInfo purchaseInfo : purchasedProductsList) {
                    if (purchaseInfo.getProduct().equals(productId)) {
                        return PurchasedResult.YES;
                    }
                }
            }
            return PurchasedResult.NO;
        }
    }

    /**
     * Checks purchase signature validity. When no license key is configured the check is
     * skipped (and the purchase is treated as valid). Google recommends moving signature
     * verification to your server anyway -- the client-side key is trivial to extract from
     * an APK and offers only weak protection.
     */
    private boolean isPurchaseSignatureValid(@NonNull Purchase purchase) {
        if (base64Key == null || base64Key.isEmpty()) return true;
        return Security.verifyPurchase(base64Key, purchase.getOriginalJson(), purchase.getSignature());
    }

    /**
     * Returns the main thread for operations that need to be executed on the UI thread
     * <p>
     * BillingEventListener runs on it
     */
    @NonNull
    private Handler findUiHandler() {
        return uiHandler;
    }

    /**
     * To print a log while debugging BillingConnector
     */
    private void Log(String debugMessage) {
        if (shouldEnableLogging) {
            Log.d(TAG, debugMessage);
        }
    }

    /**
     * Called to release the BillingClient instance
     * <p>
     * To avoid leaks this method should be called when BillingConnector is no longer needed
     */
    public void release() {
        released = true;
        if (billingClient != null && billingClient.isReady()) {
            Log("BillingConnector instance release: ending connection...");
            billingClient.endConnection();
        }

        // Prevent memory leaks and NPEs from pending exponential backoff tasks after the lifecycle is destroyed
        uiHandler.removeCallbacksAndMessages(null);

        billingEventListener = null;
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onDestroy(owner);
        release();
        if (lifecycle != null) {
            lifecycle.removeObserver(this);
        }
    }
}
