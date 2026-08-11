# TripPulse --- Android Driver App Specification

## 1. Stack

Recommended:

-   Kotlin
-   Jetpack Compose
-   Android SDK current stable target required for Play release
-   Coroutines/Flow
-   Room/SQLite
-   Android Location APIs / Fused Location Provider
-   Activity Recognition where useful
-   Foreground Service for active trip tracking
-   Firebase Android SDK
-   FCM
-   DataStore
-   WorkManager for non-live deferred work

## 2. Active tracking

Starting a trip is an explicit user action.

On start:

1.  validate location permissions
2.  start location foreground service
3.  create local active-trip state
4.  begin adaptive location collection
5.  initialize event synchronization
6.  display persistent tracking notification
7.  show current tracking state

The tracking service must stop when the active trip ends.

## 3. Android background/foreground service constraints

The implementation must comply with current Android foreground-service
rules and Google Play location policy. For Android 14+ the correct
foreground-service type/permission must be declared. Background location
usage must be justified as core functionality and handled through Play's
required declaration/disclosure process where applicable.

Do not work around Android restrictions.

## 4. Permission UX

Request only permissions actually needed.

Explain:

-   precise location is needed for live journey sharing
-   tracking occurs only during an explicitly active trip
-   location is not used for advertising
-   trip ends tracking

If background location is required by the chosen implementation, provide
prominent disclosure before permission flow.

## 5. Local database

Room tables:

``` text
ActiveTripEntity
EventEntity
LocationSampleEntity
SyncQueueEntity
TripStateEntity
BreakRecordEntity
NoteEntity
```

All writes relevant to journey state occur transactionally.

## 6. Location collection

Use adaptive sampling rather than a fixed high-frequency interval.

Factors:

-   movement
-   speed
-   stationary duration
-   battery
-   connectivity
-   trip mode
-   SOS mode

The implementation must be benchmarked on real devices.

## 7. Stop detection

Combine:

-   GPS speed
-   displacement
-   activity recognition
-   stationary duration
-   optional vehicle/Bluetooth signals

Traffic-light/traffic-jam protection is required.

Do not classify every zero-speed interval as a break.

## 8. Safe interaction

While moving:

-   no text input
-   no break questionnaire
-   no notes
-   no distracting dialogs

At a safe stationary checkpoint:

-   show compact multi-select
-   allow skip
-   save locally immediately

## 9. Quick Notes

Provide:

-   free text
-   structured shortcuts
-   automatic timestamp/location
-   optional sensitive flag

Disable or defer note entry while moving.

## 10. SOS

Long press.

On activation:

-   capture best current GPS
-   create immutable SOS event locally
-   switch sync priority
-   send high-priority notification when online
-   retry while offline
-   allow deliberate resolution

Do not claim delivery until backend acknowledgement exists.

## 11. Offline

The app must continue:

-   location collection
-   stop detection
-   break checkpoints
-   notes
-   SOS persistence
-   state transitions

without internet.

## 12. App/process/device restart

On restart:

1.  restore active trip
2.  restore last state
3.  recover unsynced events
4.  resume tracking if policy/platform permits
5.  resume sync

No event may disappear because the app process restarted.

## 13. Battery

Implement a visible battery state.

Use a low-power mode that reduces location frequency while retaining
journey continuity.

## 14. Driver dashboard

Show only:

-   destination
-   progress
-   realistic ETA
-   current state
-   last break
-   connectivity
-   battery
-   Quick Note
-   SOS
-   End/Pause Trip

Do not overload the driver screen.

## 15. Device compatibility

Test at minimum:

-   recent Samsung
-   recent Pixel
-   one lower/mid-range Android device
-   Android versions supported by Play release

Account for vendor battery optimizations.
