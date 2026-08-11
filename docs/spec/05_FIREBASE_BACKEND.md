# TripPulse --- Firebase Backend Specification

## 1. Services

Use:

-   Firebase Realtime Database
-   Firebase Authentication
-   Cloud Functions where necessary
-   Firebase Cloud Messaging
-   Crashlytics
-   App Check where compatible
-   Remote Config for safe threshold tuning

## 2. Authentication

Two concepts:

### Driver identity

Optional anonymous/authenticated Firebase user backing the device.

### Trip viewer authorization

Trip ID + secret establishes access to a specific trip.

Do not expose raw trip secrets in database paths.

## 3. Trip credential design

Generate high-entropy random trip credentials.

Store only a secure verifier/hash where possible.

Never log secrets.

After trip completion:

-   revoke live access
-   invalidate viewer tokens
-   mark trip expired after retention policy

## 4. RTDB role

RTDB stores:

-   active trip state
-   recent locations
-   event records
-   viewer presence if needed
-   trip access metadata

Use current state as a compact read model.

## 5. Cloud Functions

Functions may handle:

-   trip expiration
-   server-side validation
-   high-priority notification fanout
-   cleanup
-   trip completion processing
-   suspicious credential activity

Do not put latency-sensitive location processing behind a function if
direct client → RTDB synchronization is sufficient.

## 6. FCM

Notifications:

-   trip started
-   meaningful break
-   arrival
-   stale/offline warning
-   SOS
-   overnight confirmation

Do not send every location update as a notification.

## 7. Cost discipline

Avoid:

-   high-volume database reads for every screen
-   unnecessary server-side fanout
-   storing redundant high-frequency data forever
-   analytics on every raw GPS point

Keep active-state reads small and history queryable.

## 8. Retention

During active trip: high-resolution operational data.

After trip: configurable retention.

Credentials: revoke promptly.

SOS/incident records may use a separate longer retention policy.

## 9. Migration readiness

Create repository interfaces:

``` text
TripRepository
EventRepository
RealtimeStateRepository
AuthRepository
NotificationRepository
```

Do not couple UI/domain code directly to Firebase SDK APIs.
