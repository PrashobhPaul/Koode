# TripPulse --- Product Requirements

## 1. Product statement

TripPulse is a private, trip-scoped live journey and wellbeing-context
system. One driver creates a trip and shares a secret trip credential.
Any number of authorized people who possess valid credentials can
monitor the same trip through the same Android app.

The product answers:

-   Where is the driver now?
-   Is the location actually live?
-   How long since the last update?
-   How is the journey progressing?
-   What is the realistic arrival window?
-   When did the driver last stop?
-   Did they record food, water, toilet or rest?
-   Was there a long/overnight stop?
-   Did someone join the journey?
-   Did the driver add a note?
-   Was an incident/SOS recorded?
-   Is the phone currently online/offline/stale?

## 2. Actors

### Driver

Creates and controls a trip, provides confirmations and notes, and can
activate SOS.

### Viewer

Uses trip credentials to read a trip. Viewer is read-only.

### Emergency contact

Optional recipient for high-priority SOS information. Emergency contacts
are separate from ordinary viewers.

## 3. Trip lifecycle

`DRAFT → READY → ACTIVE → PAUSED/OVERNIGHT → RESUMED → ARRIVED → EXPIRED`

A trip may contain multiple driving segments.

## 4. Driver journey

1.  Create trip.
2.  Configure destination and optional emergency contacts.
3.  Receive trip ID + secret.
4.  Share credentials privately.
5.  Start trip.
6.  Phone enters active tracking mode.
7.  Drive without interacting.
8.  Stop detection occurs automatically.
9.  At a safe stationary checkpoint, answer one compact break question.
10. Continue driving.
11. Add quick notes whenever safely stationary.
12. Activate SOS if required.
13. Complete trip.
14. Live credentials are revoked/expired.

## 5. Viewer journey

1.  Open app.
2.  Select Join Trip.
3.  Enter/scan credentials.
4.  Authenticate.
5.  Receive current state immediately.
6.  View map, ETA, status, wellbeing timeline and notes permitted by
    trip settings.
7.  Receive live updates.
8.  If stale/offline, see explicit freshness state.
9.  On arrival, see completion.
10. After expiration, live access is revoked.

## 6. Core features

### P0

-   Trip creation
-   Trip credential generation
-   Multiple viewers
-   Live location
-   Local-first event storage
-   Offline synchronization
-   Stop detection
-   Restart detection
-   Break checkpoint
-   Water/food/toilet/rest confirmation
-   Current-state synchronization
-   Realistic ETA
-   Long-stop/overnight handling
-   Secure trip expiry
-   Crash/restart recovery
-   Stale/offline indication
-   SOS

### P1

-   Quick notes
-   Passenger joined/left
-   Medicine note
-   Fuel/charging
-   Route deviation
-   Notifications
-   Trip timeline
-   Trip replay
-   Trip summary

### P2

-   Personalized break forecasting
-   Intelligent stop suggestions
-   AI journey summary
-   Advanced incident detection

## 7. Non-goals

Do not:

-   claim medical hydration status
-   infer toilet use from location
-   infer food consumption from a POI
-   claim an accident solely from accelerometer data
-   monitor audio/camera continuously
-   track outside an explicitly active trip
-   require interaction while driving
-   expose trip data publicly

## 8. Product truth model

Every important datum must carry provenance:

-   `SENSOR_OBSERVED`
-   `SYSTEM_INFERRED`
-   `DRIVER_CONFIRMED`
-   `DRIVER_MANUAL`
-   `SERVER_DERIVED`

A driver-confirmed fact must never be represented as sensor certainty.

## 9. Definition of success

A complete trip can run through network loss, long breaks, app restart
and overnight halt without losing journey events, while authorized
viewers continue receiving the best available current state and a
truthful indication of freshness.
