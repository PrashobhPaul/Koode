# TripPulse --- Firebase RTDB Schema and Rules

## 1. Logical schema

``` text
/trips/{tripId}
    metadata
    currentState
    access
    settings
    createdAt
    expiresAt

/trips/{tripId}/locations/{eventId}
/trips/{tripId}/events/{eventId}
/trips/{tripId}/timeline/{eventId}

/tripAccess/{tripId}
/viewerSessions/{sessionId}
/notifications/{tripId}

/serverIndexes/...
```

## 2. Current state

Keep compact.

``` json
{
  "status": "DRIVING",
  "connectivity": "ONLINE",
  "lat": 10.123,
  "lng": 76.123,
  "accuracyM": 8,
  "lastLocationAt": 123456789,
  "etaLow": 123456789,
  "etaHigh": 123456789,
  "distanceRemainingKm": 341
}
```

## 3. Events

Use unique event IDs.

Never allow client overwrite of an existing immutable event.

## 4. Access model

Conceptually:

``` text
Trip owner
  → read/write active trip

Viewer
  → read only

Unauthenticated
  → no access

Expired trip
  → no live access
```

## 5. Secrets

Do not store plaintext trip secret in a publicly readable path.

Prefer a secure verification mechanism through authenticated backend
logic.

## 6. Server timestamps

Use server timestamps for:

-   received time
-   expiry
-   notification creation

Retain client event time separately.

## 7. Rules

Rules must enforce:

-   authorized trip read
-   driver-only writes to event paths
-   viewer read-only
-   no arbitrary trip creation by viewers
-   no access after expiry
-   no modification of immutable events
-   validation of required fields

## 8. Testing

Create Firebase Emulator tests for:

-   unauthorized read
-   authorized read
-   viewer write rejection
-   expired access
-   malformed payload
-   immutable event overwrite
-   trip enumeration
-   secret misuse

## 9. Data indexing

Optimize for:

-   current state lookup
-   latest events
-   timeline range
-   active trip

Avoid unbounded queries.

## 10. Retention/cleanup

Cloud Functions or scheduled cleanup should remove/restrict expired live
data according to retention policy.

Do not delete incident records prematurely if policy requires longer
retention.
