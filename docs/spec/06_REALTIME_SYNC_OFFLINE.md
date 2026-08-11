# TripPulse --- Realtime, Offline and Reconciliation Specification

## 1. Primary guarantee

If an event was successfully written to the driver's local event store,
it must eventually be synchronized when a valid network path returns,
unless the event is explicitly deleted by a documented retention policy.

## 2. Write path

``` text
Input
 ↓
Validate
 ↓
Local transaction
 ├── event
 └── current state
 ↓
Sync queue
 ↓
Network?
 ├── yes → upload
 └── no → retain
```

## 3. Reconnection priority

When network returns:

1.  current location
2.  current journey state
3.  SOS pending events
4.  latest ETA inputs
5.  recent critical events
6.  historical backlog

This prevents a large backlog from delaying current visibility.

## 4. Historical ordering

Every event contains:

-   eventTime
-   receivedAt

Viewer timeline sorts primarily by `eventTime`.

Late-arriving events are inserted into historical order.

## 5. Duplicate prevention

Use deterministic client event IDs.

Server must make ingestion idempotent.

## 6. Conflict policy

For current state:

-   newest trusted state wins based on server-reconciled event
    time/version.

For immutable events:

-   append; never overwrite.

For manual corrections:

-   create correction event.

## 7. Offline break example

``` text
Stop → local STOP_STARTED
Break → local BREAK_CHECKPOINT
Food → local FOOD_REPORTED
Toilet → local TOILET_REPORTED
Resume → local STOP_ENDED
```

When online, all are synced with original timestamps.

## 8. Freshness

Viewer receives:

``` text
lastLocationAt
lastServerSyncAt
deviceConnectivity
```

Freshness is computed from current server time, not the device clock
alone.

## 9. Device clock errors

Do not blindly trust device time.

Maintain:

-   client event time
-   server received time
-   optional server timestamp on ingestion

Flag extreme clock drift.

## 10. Offline queue durability

Queue must survive:

-   app process death
-   reboot
-   service restart
-   network transitions

## 11. Backpressure

If offline for many hours:

-   compact redundant location samples where safe
-   never compact critical events
-   preserve stop/break/note/SOS events
-   prioritize latest location

## 12. Viewer opening after long offline period

Viewer should see:

``` text
Last confirmed:
10:14 AM

Current state:
Unknown / Offline

Historical events:
available through last sync
```

When backlog arrives, timeline updates chronologically.

## 13. No false live state

Never extrapolate a GPS coordinate and label it as current.

If predictive dead reckoning is ever used, label it explicitly as
estimated.
