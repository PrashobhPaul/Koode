# TripPulse --- Domain, Event Model and State Machines

## 1. Event envelope

Every event:

``` json
{
  "eventId": "UUID",
  "tripId": "trip-id",
  "type": "EVENT_TYPE",
  "eventTime": "ISO-8601",
  "receivedAt": "ISO-8601|null",
  "latitude": 0.0,
  "longitude": 0.0,
  "accuracyM": 0.0,
  "source": "DRIVER_CONFIRMED",
  "payload": {},
  "schemaVersion": 1
}
```

`eventTime` is when it happened. `receivedAt` is when backend received
it. Never substitute one for the other.

## 2. Event taxonomy

### Trip

`TRIP_CREATED`, `TRIP_STARTED`, `TRIP_PAUSED`, `TRIP_RESUMED`,
`TRIP_COMPLETED`, `TRIP_EXPIRED`, `DESTINATION_CHANGED`

### Location

`LOCATION_UPDATE`, `LOCATION_GAP_STARTED`, `LOCATION_GAP_RESOLVED`

### Movement

`DRIVING_STARTED`, `DRIVING_CONTINUED`, `STOP_STARTED`,
`STOP_CONTINUED`, `STOP_ENDED`, `RESTART_DETECTED`

### Breaks

`BREAK_CHECKPOINT`, `WATER_REPORTED`, `FOOD_REPORTED`,
`TOILET_REPORTED`, `REST_REPORTED`, `FUEL_REPORTED`, `CHARGE_REPORTED`

### Overnight

`LONG_STOP_DETECTED`, `OVERNIGHT_PROMPTED`, `OVERNIGHT_CONFIRMED`,
`OVERNIGHT_DECLINED`, `OVERNIGHT_RESUMED`

### Context

`QUICK_NOTE`, `PASSENGER_JOINED`, `PASSENGER_LEFT`, `MEDICINE_NOTE`,
`VEHICLE_ISSUE`, `ROUTE_CHANGE`

### Safety

`POSSIBLE_INCIDENT`, `SOS_ACTIVATED`, `SOS_DELIVERED`, `SOS_RESOLVED`

### System

`NETWORK_OFFLINE`, `NETWORK_ONLINE`, `BATTERY_LOW`, `SYNC_STARTED`,
`SYNC_ACKNOWLEDGED`

## 3. Journey state machine

``` text
READY
 ↓
DRIVING
 ↓
POSSIBLE_STOP
 ↓
STOPPED
 ├── short stop → RESTART_DETECTED → DRIVING
 ├── break → BREAK_CHECKPOINT → DRIVING
 └── >2h → LONG_STOP → OVERNIGHT decision
                         ├── confirmed → OVERNIGHT → RESUMED
                         └── declined → STOPPED → DRIVING
```

## 4. SOS state machine

``` text
NORMAL
 ↓
SOS_ACTIVATED
 ↓
SOS_PENDING_DELIVERY
 ↓
SOS_DELIVERED
 ↓
SOS_RESOLVED
```

If offline:

`SOS_ACTIVATED → SOS_PENDING_DELIVERY → local persistence → network return → SOS_DELIVERED`

## 5. Provenance

### Sensor observed

Examples: GPS position, speed, battery.

### System inferred

Examples: long stop, possible incident, route deviation.

### Driver confirmed

Examples: food, water, toilet, rest.

### Driver manual

Examples: passenger joined, medicine note, free-text note.

### Server derived

Examples: ETA range, remaining distance.

## 6. Event immutability

Raw events are append-only. Corrections create new events:

`CORRECTION_CREATED`, `EVENT_SUPERSEDED`

Never silently edit raw history.

## 7. Idempotency

Each client event has a unique `eventId`. Backend must accept duplicate
delivery safely and return an ACK without creating duplicate records.

## 8. Location rules

Location event stores:

-   coordinate
-   accuracy
-   speed when available
-   bearing when available
-   timestamp
-   source/provider
-   battery/network context where useful

Do not claim precision better than the reported accuracy.
