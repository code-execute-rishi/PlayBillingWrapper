package com.playbillingwrapper.model;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AccountIdentifiers;
import com.android.billingclient.api.Purchase;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.playbillingwrapper.type.SkuProductType;

public class PurchaseInfo {

    private final SkuProductType skuProductType;
    /**
     * May be {@code null} when the user owns a product for which Play did not return
     * {@code ProductDetails} (e.g. a legacy / inactive / country-delisted SKU). In that
     * case the product id / purchase state are still valid but accessors that depend on
     * the product catalog (prices, pricing phases, trial period) are unavailable.
     */
    @androidx.annotation.Nullable
    private final ProductInfo productInfo;
    private final Purchase purchase;

    private final String product;

    private final AccountIdentifiers accountIdentifiers;
    private final List<String> products;

    private final String orderId;
    private final String purchaseToken;
    private final String originalJson;
    private final String developerPayload;
    private final String packageName;
    private final String signature;

    private final int quantity;
    private final int purchaseState;

    private final long purchaseTime;

    private final boolean isAcknowledged;
    private final boolean isAutoRenewing;
    private final boolean isPaused;

    public PurchaseInfo(@NonNull ProductInfo productInfo, @NonNull Purchase purchase) {
        this(productInfo, productInfo.getProduct(), productInfo.getSkuProductType(), purchase);
    }

    /**
     * Constructor for owned purchases whose product catalog entry ({@code ProductInfo})
     * is unavailable -- legacy, inactive, or country-delisted SKUs that Play still
     * returns from {@code queryPurchasesAsync}. The caller must classify the SKU type
     * externally (e.g. from the original registration lists).
     */
    public PurchaseInfo(@NonNull String productId, @NonNull SkuProductType type, @NonNull Purchase purchase) {
        this(null, productId, type, purchase);
    }

    private PurchaseInfo(@androidx.annotation.Nullable ProductInfo productInfo,
                         @NonNull String productId,
                         @NonNull SkuProductType type,
                         @NonNull Purchase purchase) {
        this.productInfo = productInfo;
        this.purchase = purchase;
        this.product = productId;
        this.skuProductType = type;
        this.accountIdentifiers = purchase.getAccountIdentifiers();
        this.products = purchase.getProducts();
        this.orderId = purchase.getOrderId();
        this.purchaseToken = purchase.getPurchaseToken();
        this.originalJson = purchase.getOriginalJson();
        this.developerPayload = purchase.getDeveloperPayload();
        this.packageName = purchase.getPackageName();
        this.signature = purchase.getSignature();
        this.quantity = purchase.getQuantity();
        this.purchaseState = purchase.getPurchaseState();
        this.purchaseTime = purchase.getPurchaseTime();
        this.isAcknowledged = purchase.isAcknowledged();
        this.isAutoRenewing = purchase.isAutoRenewing();
        this.isPaused = purchase.isSuspended();
    }

    public SkuProductType getSkuProductType() {
        return skuProductType;
    }

    @androidx.annotation.Nullable
    public ProductInfo getProductInfo() {
        return productInfo;
    }

    public Purchase getPurchase() {
        return purchase;
    }

    public String getProduct() {
        return product;
    }

    public AccountIdentifiers getAccountIdentifiers() {
        return accountIdentifiers;
    }

    public List<String> getProducts() {
        return Collections.unmodifiableList(products);
    }

    public String getOrderId() {
        return orderId;
    }

    public String getPurchaseToken() {
        return purchaseToken;
    }

    public String getOriginalJson() {
        return originalJson;
    }

    public String getDeveloperPayload() {
        return developerPayload;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSignature() {
        return signature;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getPurchaseState() {
        return purchaseState;
    }

    public long getPurchaseTime() {
        return purchaseTime;
    }

    /**
     * True when this subscription purchase is currently paused by the user via Play
     * (Play's {@code Purchase.isSuspended()} flag). Entitlement is revoked while paused;
     * the user can resume from Play's manage-subscription page. Only meaningful for
     * subscriptions; always false for one-time products.
     */
    public boolean isPaused() {
        return isPaused;
    }

    public boolean isAcknowledged() {
        return isAcknowledged;
    }

    public boolean isAutoRenewing() {
        return isAutoRenewing;
    }

    public boolean isPurchased() {
        return purchaseState == Purchase.PurchaseState.PURCHASED;
    }

    public boolean isPending() {
        return purchaseState == Purchase.PurchaseState.PENDING;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PurchaseInfo that = (PurchaseInfo) obj;
        return Objects.equals(purchaseToken, that.purchaseToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(purchaseToken);
    }
}