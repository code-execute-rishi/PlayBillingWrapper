# Keep all Play Billing classes and their members. The Billing Library uses reflection
# and will fail at runtime if members (methods/fields) are stripped by R8/ProGuard.
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.api.** { *; }

# Keep the PlayBillingWrapper public API so consumers can use it without adding their
# own keep rules.
-keep class com.playbillingwrapper.** { *; }
-keep interface com.playbillingwrapper.** { *; }

-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod
