# Security

Play Billing is only as secure as your verification path. The wrapper ships two layers of
verification. Both are opt-in -- by default no verification runs; server-side is the
recommended layer.

## Layer 1 — Client-side signature check (opt-in)

Off by default. When the builder's `base64LicenseKey(...)` is null or empty the wrapper
skips signature verification entirely (every purchase is accepted on its Play response
alone). Pass the key to enable the check; when enabled, the wrapper rejects purchases
whose signature doesn't match:

```java
BillingConfig.builder()
    .base64LicenseKey("BASE64_KEY_FROM_PLAY_CONSOLE")
    ...
```

The key is found at Play Console → Monetize → Monetization setup → Licensing.

**This is weak security.** The key sits in your APK, where any attacker can extract it with
`apktool`. A determined attacker on a rooted device can hook the `BillingClient` and forge
signed purchases. Don't ship the key alone.

## Layer 2 — Server-side verification (recommended)

For any purchase worth protecting, verify server-side before granting the entitlement.
PlayBillingWrapper leaves this step to you — the wiring is a few lines in your existing
backend.

### Flow

```
Client                                    Server                           Google Play
  |                                          |                                  |
  | -- POST /verify { token, productId } --> |                                  |
  |                                          | -- GET /purchases/... -->        |
  |                                          | <-- {state, expiry, ...} --      |
  |                                          | -- acknowledge (if needed) -->   |
  | <-- 200 { entitled: true } -------------- |                                  |
```

### Step 1 — Create a service account

1. Google Cloud Console → IAM → Service accounts → Create.
2. Role: none at first.
3. Play Console → Settings → API access → Link your Cloud project → Grant the service account
   **Finance** permission.

### Step 2 — Call the Google Play Developer API

#### Subscriptions

```bash
GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/
    {packageName}/purchases/subscriptionsv2/tokens/{purchaseToken}
```

Response includes `subscriptionState` (`SUBSCRIPTION_STATE_ACTIVE`,
`SUBSCRIPTION_STATE_IN_GRACE_PERIOD`, `SUBSCRIPTION_STATE_ON_HOLD`,
`SUBSCRIPTION_STATE_PAUSED`, `SUBSCRIPTION_STATE_CANCELED`, `SUBSCRIPTION_STATE_EXPIRED`),
`lineItems[].expiryTime`, `latestOrderId`, `acknowledgementState`,
`externalAccountIdentifiers.obfuscatedExternalAccountId`.

#### One-time products

```bash
GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/
    {packageName}/purchases/products/{productId}/tokens/{purchaseToken}
```

Response includes `purchaseState` (0 = PURCHASED, 1 = CANCELED, 2 = PENDING),
`consumptionState`, `acknowledgementState`, `obfuscatedAccountId`.

### Step 3 — Verify invariants before granting

```python
# Python / FastAPI-style pseudocode
def verify(token, product_id, user_id_hash):
    data = playApi.get_purchase(token, product_id)

    # Bind purchase to user
    if data.obfuscated_account_id != user_id_hash:
        raise Forbidden('obfuscatedAccountId mismatch')

    # Check state
    if data.subscription_state not in ACTIVE_STATES:
        raise Forbidden('not active')

    # Ensure we haven't granted this before
    if tokens_collection.exists(token):
        return existing_entitlement(token)

    # Grant + acknowledge server-side
    grant_entitlement(user_id_hash, product_id, data.expiry_time)
    playApi.acknowledge(token, product_id)
    tokens_collection.insert(token, user_id_hash, product_id)
```

### Step 4 — Acknowledge server-side

If your server handles the acknowledge call, flip `autoAcknowledge(false)` in the builder so
the client doesn't race you. The acknowledge window is 72 hours from the `PURCHASED`
transition, not from the billing flow, so take your time.

## Fraud signals

The wrapper sets `obfuscatedAccountId` and `obfuscatedProfileId` on every
`BillingFlowParams`. Google uses these to:

- Flag anomalies like "50 devices buying on one account" or "200 accounts buying to one user".
- Enforce "one free trial per obfuscatedAccountId".
- Let your server reject a token whose obfuscatedAccountId doesn't match the caller.

Always pass a hash, never a raw email or phone number. A leak turns into a privacy incident.

## Play Integrity

For purchases worth protecting (large in-app stores, high-value subscriptions), also gate
your `/verify` endpoint behind a Play Integrity token:

1. Client requests a nonce from your server.
2. Client calls `IntegrityManager.requestIntegrityToken(nonce)` → integrity token.
3. Client POSTs `{purchaseToken, integrityToken, nonce}` to `/verify`.
4. Server decodes the integrity token, checks `appRecognitionVerdict`, `deviceRecognitionVerdict`, and `nonce`.
5. Reject if the app is unrecognised or the device fails basic integrity.

See [Play Integrity API](https://developer.android.com/google/play/integrity) for details.

## What to log

- Every `purchaseToken` on receipt — you need it for refund disputes.
- Every `obfuscatedAccountId` mismatch — signal of fraud.
- Every 72h window you miss — signal of infrastructure issues.

Don't log:

- Raw user PII.
- The base64 license key (it's supposed to be public, but there's no reason to put it in
  logs).
- Full `originalJson` bodies unless you redact `obfuscatedAccountId` first.
