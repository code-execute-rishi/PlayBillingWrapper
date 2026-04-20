package com.playbillingwrapper.sample;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.playbillingwrapper.PlayBillingWrapper;
import com.playbillingwrapper.listener.WrapperListener;
import com.playbillingwrapper.model.BillingResponse;
import com.playbillingwrapper.model.PurchaseInfo;
import com.playbillingwrapper.status.SubscriptionState;

public class MainActivity extends AppCompatActivity implements WrapperListener {

    private PlayBillingWrapper billing;
    private TextView status;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);

        billing = SampleApp.billing(this);
        billing.setListener(this);

        status = findViewById(R.id.status);

        Button buyLifetime = findViewById(R.id.btn_lifetime);
        Button subMonthly  = findViewById(R.id.btn_monthly);
        Button subYearly   = findViewById(R.id.btn_yearly);
        Button restore     = findViewById(R.id.btn_restore);
        Button manage      = findViewById(R.id.btn_manage);

        buyLifetime.setOnClickListener(v -> billing.buyLifetime(this));
        subMonthly.setOnClickListener(v -> billing.subscribeMonthly(this));
        subYearly.setOnClickListener(v -> billing.subscribeYearlyWithTrial(this));
        restore.setOnClickListener(v -> billing.restorePurchases());
        manage.setOnClickListener(v -> {
            // Deep-link to whichever subscription the user currently owns.
            SubscriptionState y = billing.yearlyState();
            SubscriptionState m = billing.monthlyState();
            if (y != SubscriptionState.EXPIRED) {
                billing.openManageSubscription(this, /*productId=*/"com.your.app.premium_yearly");
            } else if (m != SubscriptionState.EXPIRED) {
                billing.openManageSubscription(this, /*productId=*/"com.your.app.premium_monthly");
            }
        });

        refresh();
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("Lifetime: ").append(billing.hasLifetime() ? "OWNED" : "not owned").append('\n');
        sb.append("Monthly:  ").append(billing.monthlyState()).append('\n');
        sb.append("Yearly:   ").append(billing.yearlyState()).append('\n');
        sb.append("Trial eligible: ").append(billing.isTrialEligibleForYearly()).append('\n');
        sb.append("Premium (any): ").append(billing.isPremium());
        status.setText(sb.toString());
    }

    @Override
    protected void onDestroy() {
        if (billing != null) billing.setListener(null);
        super.onDestroy();
    }

    @Override public void onReady() { runOnUiThread(this::refresh); }
    @Override public void onLifetimePurchased(@NonNull PurchaseInfo purchase) { runOnUiThread(this::refresh); }
    @Override public void onSubscriptionActivated(@NonNull String productId, @NonNull SubscriptionState state, @NonNull PurchaseInfo purchase) { runOnUiThread(this::refresh); }
    @Override public void onPending(@NonNull PurchaseInfo purchase) { runOnUiThread(this::refresh); }
    @Override public void onUserCancelled() { /* no-op */ }
    @Override public void onError(@NonNull BillingResponse response) {
        runOnUiThread(() -> status.setText("Error: " + response.getErrorType() + " - " + response.getDebugMessage()));
    }
}
