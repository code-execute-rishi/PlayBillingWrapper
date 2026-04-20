package com.playbillingwrapper.sample;

import android.app.Application;

import com.playbillingwrapper.PlayBillingWrapper;
import com.playbillingwrapper.model.BillingConfig;

/**
 * Holds a single process-scoped {@link PlayBillingWrapper} instance so Activities can share it.
 */
public class SampleApp extends Application {

    /**
     * Replace these with your real Play Console ids before running the sample on a device
     * with a license-tester Google account.
     */
    private static final String LIFETIME_ID       = "com.your.app.lifetime";
    private static final String MONTHLY_SUB_ID    = "com.your.app.premium_monthly";
    private static final String MONTHLY_BASE_PLAN = "monthly";
    private static final String YEARLY_SUB_ID     = "com.your.app.premium_yearly";
    private static final String YEARLY_BASE_PLAN  = "yearly";
    private static final String YEARLY_TRIAL_OFFER = "freetrial"; // optional, may be null

    private PlayBillingWrapper billing;

    public static PlayBillingWrapper billing(android.content.Context context) {
        return ((SampleApp) context.getApplicationContext()).billing;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        BillingConfig cfg = BillingConfig.builder()
                .lifetimeProductId(LIFETIME_ID)
                .monthlySubProductId(MONTHLY_SUB_ID)
                .monthlyBasePlanId(MONTHLY_BASE_PLAN)
                .yearlySubProductId(YEARLY_SUB_ID)
                .yearlyBasePlanId(YEARLY_BASE_PLAN)
                .yearlyTrialOfferId(YEARLY_TRIAL_OFFER)
                .userId("sample-hashed-user-id")        // replace with a real hash of your user id
                .enableLogging(true)
                .autoAcknowledge(true)
                .build();

        billing = new PlayBillingWrapper(this, cfg, /*listener=*/null);
        billing.connect();
    }
}
