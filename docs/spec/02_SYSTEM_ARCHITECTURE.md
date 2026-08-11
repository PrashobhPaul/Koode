# TripPulse --- System Architecture

## 1. Architecture

``` text
                    ┌──────────────────────┐
                    │ Android TripPulse    │
                    │ Driver / Viewer      │
                    └──────────┬───────────┘
                               │
                  ┌────────────┴────────────┐
                  │                         │
           Driver mode                Viewer mode
                  │                         │
        Location + sensors            Realtime listener
                  │                         │
             Local event log               │
                  │                         │
             Sync engine                   │
                  └────────────┬────────────┘
                               │
                         Internet
                               │
                    ┌──────────▼───────────┐
                    │ Firebase             │
                    │ Realtime Database    │
                    │ Authentication       │
                    │ Cloud Functions      │
                    │ FCM                  │
                    └──────────┬───────────┘
                               │
                    Authorized trip viewers
```

## 2. Why Firebase for this product

The MVP/first production release should use Firebase Realtime Database
because realtime listeners and Android offline persistence fit the
product directly. Firebase clients can continue to work with cached data
and queue writes while temporarily disconnected.

Do not introduce a custom server/database cluster unless a real
requirement appears. Keep the domain layer provider-independent so
Firebase can later be replaced.

## 3. Local-first principle

The driver device is the first durable write boundary.

``` text
Sensor/Input
  ↓
Validate
  ↓
Local transaction
  ↓
Update local state
  ↓
Queue sync
```

Network failure must never block event creation.

## 4. Two-lane synchronization

### Lane A: current state

Highest priority:

-   current location
-   current journey state
-   connectivity
-   battery
-   ETA
-   current stop/overnight state

### Lane B: event history

Reliable delivery:

-   stops
-   breaks
-   food/water/toilet/rest
-   notes
-   passenger events
-   SOS
-   incidents
-   route changes

After reconnection, current state is uploaded first, then historical
backlog.

## 5. Canonical state + immutable events

Maintain both:

``` text
event log = historical truth
currentState = fast read model
```

Every accepted event updates the read model deterministically.

## 6. Connectivity model

Connectivity is orthogonal to journey state.

``` text
Journey: DRIVING / STOPPED / OVERNIGHT / ARRIVED
Network: ONLINE / DEGRADED / OFFLINE
```

Never make `OFFLINE` a journey state.

## 7. Realtime freshness

Viewer state:

-   `LIVE`
-   `RECENT`
-   `STALE`
-   `OFFLINE`
-   `UNKNOWN`

The UI must never label an old coordinate as live.

## 8. Security boundary

The trip is the authorization boundary.

No public trip search.

Viewer token is short-lived.

Credentials are hashed/ephemeral.

Trip data is readable only through authorized access.

## 9. Battery strategy

Adaptive sampling:

-   active driving: higher frequency
-   stationary: lower frequency
-   overnight: very low frequency
-   low battery: preservation mode

Exact intervals are configuration, not hardcoded assumptions.

## 10. Deployment

Initial:

-   Firebase project
-   Android release
-   Firebase Crashlytics
-   FCM
-   RTDB
-   Cloud Functions where server-side validation/cleanup is required

No always-on custom VM/database required.

## 11. External map/routing provider

Abstract routing behind an interface:

``` text
RoutingProvider
  calculateRoute()
  estimateTravelTime()
  calculateRemainingRoute()
```

The first implementation may use a suitable Google Maps/Routes
integration or another provider compatible with licensing and cost
constraints. Do not hardwire provider-specific code throughout the
domain layer.

## 12. Architecture invariant

No feature may bypass the local event log + synchronization
architecture.
