# TripPulse --- Intelligent Private Road-Trip Safety & Live Journey Sharing

## Technical Product & Engineering Plan for Claude / Lovable

**Document version:** 1.0\
**Target platform:** Android-first, with a responsive web/PWA companion
for trip viewers\
**Primary use case:** Long-distance road trips such as Hyderabad →
Thrissur (\~1,100 km)\
**Core principle:** Track the journey automatically, minimize driver
interaction, and give trusted family/friends a complete picture of the
trip without requiring phone calls.

------------------------------------------------------------------------

# 1. Product Vision

TripPulse is a **private, trip-scoped live journey and driver-wellbeing
system**.

A driver starts a trip and receives a unique:

-   `trip_id`
-   secret trip password / access code
-   optional trip nickname

The driver then places the phone in the vehicle and drives normally.

The app automatically captures:

-   journey start
-   current location
-   route progression
-   speed/movement state where available
-   stops and stop duration
-   trip progress
-   estimated arrival time
-   significant route deviations
-   prolonged stops
-   driving/rest cycles
-   connectivity status
-   battery status
-   last successful synchronization
-   food break
-   toilet/bio break
-   hydration
-   meaningful rest
-   optional refuelling/charging
-   manual notes

The driver should **not need to interact with the phone while driving**.

When the vehicle appears to have stopped and the driver later resumes
the journey, the app can present a short, low-friction checkpoint such
as:

> "Break completed --- what did you do?"

with one-tap options:

-   💧 Water
-   🍛 Food
-   🚻 Toilet
-   😴 Rest
-   ⛽ Fuel / Charge
-   ☕ Other
-   Skip

Multiple answers can be selected.

The selected information is synchronized to the central backend and
becomes visible to authorized trip viewers.

------------------------------------------------------------------------

# 2. Core User Experience

## 2.1 Driver

### Before departure

Driver:

1.  Opens app.
2.  Selects **Start New Trip**.
3.  Enters:
    -   destination
    -   optional intermediate destination
    -   planned departure time
    -   expected route
    -   optional planned stops
    -   optional emergency contact
4.  App creates a unique trip.
5.  App displays:
    -   Trip ID
    -   temporary secret password
    -   QR/share option
6.  Driver shares credentials with trusted family/friends.
7.  Driver starts trip.

### During driving

The driver sees a very simple screen:

``` text
----------------------------------
        TRIP IN PROGRESS

        Hyderabad → Thrissur

        642 km completed
        458 km remaining

        ETA  : 08:35 PM

        Last update:
        2 min ago

        Driving for: 2h 17m

        ● LIVE
----------------------------------
```

No conversational interaction should occur while driving.

### When stopping

The system detects a likely stop.

It records:

``` text
STOP_STARTED
timestamp
latitude
longitude
accuracy
vehicle_stationary = true
```

The app waits rather than immediately interrupting the driver.

After the vehicle starts moving again, the app identifies:

``` text
STOP_ENDED
```

and presents a checkpoint.

Example:

``` text
Welcome back!

What did you do during the break?

☐ Water
☐ Food
☐ Toilet
☐ Rest
☐ Fuel / Charge
☐ Other

[Done]
```

This should take approximately 2--5 seconds.

------------------------------------------------------------------------

# 3. Viewer Experience

The viewer enters:

``` text
Trip ID: __________
Password: __________
```

No permanent account should be required for basic trip viewing.

After authentication:

``` text
================================================
              HYDERABAD → THRISSUR
                    ● LIVE
================================================

Current location
Near Coimbatore

Progress
██████████████░░░░░░  67%

ETA
08:35 PM

Last updated
2 minutes ago

Driving
2h 17m since last break

------------------------------------------------
HEALTH / JOURNEY STATUS

💧 Hydration     35 min ago
🍛 Food          3h 10m ago
🚻 Toilet        35 min ago
😴 Rest          35 min ago
⛽ Fuel          2h 55m ago

------------------------------------------------
RECENT EVENTS

21:05  Break completed
20:58  Toilet + Water
20:56  Vehicle stopped
18:02  Fuel stop
15:40  Food + Water + Rest
------------------------------------------------
```

The viewer should not need to call the driver for routine status.

------------------------------------------------------------------------

# 4. Product Goals

## Primary goals

1.  Private live location sharing.
2.  Automatic journey tracking.
3.  Automatic stop detection.
4.  Minimal driver interaction.
5.  Break classification.
6.  Food tracking.
7.  Toilet/bio-break tracking.
8.  Hydration tracking.
9.  Rest tracking.
10. ETA tracking.
11. Offline resilience.
12. Battery-efficient tracking.
13. Centralized trip history.
14. Automatic trip expiration.
15. Secure trip credentials.
16. Family/friend-friendly dashboard.

## Secondary goals

-   Fuel/charging tracking.
-   Route deviation detection.
-   Unexpected prolonged stop detection.
-   No-network journey continuity.
-   Trip replay.
-   Journey statistics.
-   Emergency mode.
-   Optional SOS.
-   Optional geofenced arrival notifications.
-   Optional trip summary.

------------------------------------------------------------------------

# 5. Non-Goals

The first version should NOT attempt to:

-   diagnose the driver's health
-   determine whether someone actually drank water using sensors
-   determine whether the driver actually used a toilet using sensors
-   determine fatigue medically
-   continuously stream camera/audio
-   record conversations
-   use invasive surveillance
-   require continuous phone interaction
-   guarantee that a driver is physically safe
-   encourage interaction while driving

Sensor-derived states are **inferences**, not medical or factual
guarantees.

For example:

> "Water: reported 35 minutes ago"

is preferable to:

> "Driver is hydrated."

------------------------------------------------------------------------

# 6. High-Level Architecture

``` text
                    ┌──────────────────────┐
                    │      DRIVER APP      │
                    │      Android         │
                    └──────────┬───────────┘
                               │
                 ┌─────────────┴─────────────┐
                 │                           │
        Android Location APIs       Activity / Sensor APIs
                 │                           │
                 └─────────────┬─────────────┘
                               │
                     Local Event Engine
                               │
                    Local SQLite / Room DB
                               │
                    Sync / Queue Manager
                               │
                        HTTPS / WebSocket
                               │
                    ┌──────────▼───────────┐
                    │     BACKEND API      │
                    │ Authentication       │
                    │ Trip Management      │
                    │ Event Processing     │
                    │ ETA Engine            │
                    │ Notification Engine   │
                    └──────────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
          PostgreSQL       Redis/Cache       Object Storage
              │
              │
       ┌──────▼──────────┐
       │ Viewer Web App  │
       │ / PWA           │
       └─────────────────┘
```

------------------------------------------------------------------------

# 7. Recommended Technology Stack

## Android Driver App

Recommended:

-   Kotlin
-   Jetpack Compose
-   Android Location Services
-   Fused Location Provider
-   Activity Recognition API
-   Android Sensors API
-   Foreground Service
-   WorkManager
-   Room / SQLite
-   Kotlin Coroutines
-   DataStore
-   Retrofit / Ktor client
-   Google Maps SDK or MapLibre
-   encrypted local storage where required

The application should follow Android background-location and
foreground-service rules.

## Backend

Recommended:

-   FastAPI
-   Python
-   PostgreSQL
-   PostGIS
-   Redis
-   WebSocket/SSE
-   JWT/session-based viewer authorization
-   Docker

## Viewer

Recommended:

-   React
-   TypeScript
-   Tailwind CSS
-   MapLibre GL JS or Google Maps
-   PWA support

## Deployment

Possible initial deployment:

``` text
Cloud Load Balancer
        │
        ▼
FastAPI
        │
 ┌──────┼────────┐
 ▼      ▼        ▼
Postgres Redis   Notification service
```

For a POC, Docker Compose is sufficient.

Production can later move to:

-   AWS
-   GCP
-   Azure

without changing the core domain architecture.

------------------------------------------------------------------------

# 8. Local-First Architecture

This is critical.

The driver may travel through areas with:

-   poor mobile coverage
-   no internet
-   intermittent connectivity
-   network switching
-   roaming between towers

Therefore the driver app must **not depend on continuous internet
access**.

## Local event pipeline

``` text
GPS / Sensors
     ↓
Sensor Fusion
     ↓
Event Detector
     ↓
Local Event Store
     ↓
Trip State Machine
     ↓
Sync Queue
     ↓
Internet available?
     │
   ┌─┴─┐
   │   │
  YES  NO
   │   │
   ▼   ▼
Sync   Keep locally
```

When connectivity returns:

``` text
Local Events
    ↓
Deduplicate
    ↓
Upload in batches
    ↓
Server ACK
    ↓
Mark synced
```

The server must accept events with original timestamps so that offline
events retain their correct journey chronology.

------------------------------------------------------------------------

# 9. Event-Based Data Model

Do not store only the current location.

Store an immutable event stream plus derived state.

## Event types

``` text
TRIP_CREATED
TRIP_STARTED
LOCATION_UPDATE
DRIVING_STARTED
DRIVING_CONTINUED
STOP_STARTED
STOP_ENDED
BREAK_CHECKPOINT
WATER_REPORTED
FOOD_REPORTED
TOILET_REPORTED
REST_REPORTED
FUEL_STOP
CHARGE_STOP
ROUTE_DEVIATION
ETA_UPDATED
ARRIVAL_DETECTED
TRIP_PAUSED
TRIP_RESUMED
NETWORK_OFFLINE
NETWORK_ONLINE
BATTERY_LOW
MANUAL_NOTE
TRIP_ENDED
```

------------------------------------------------------------------------

# 10. Database Design

## trips

``` sql
id UUID PRIMARY KEY
trip_id VARCHAR UNIQUE NOT NULL
destination TEXT
origin TEXT
planned_departure TIMESTAMP
actual_departure TIMESTAMP
planned_arrival TIMESTAMP
actual_arrival TIMESTAMP
status VARCHAR
created_at TIMESTAMP
expires_at TIMESTAMP
ended_at TIMESTAMP
```

## trip_credentials

``` sql
id UUID PRIMARY KEY
trip_id UUID REFERENCES trips(id)
password_hash TEXT
created_at TIMESTAMP
expires_at TIMESTAMP
revoked_at TIMESTAMP
failed_attempts INTEGER
```

Never store the raw password.

## trip_events

``` sql
id UUID PRIMARY KEY
trip_id UUID REFERENCES trips(id)

event_type VARCHAR NOT NULL

event_time TIMESTAMP NOT NULL
received_at TIMESTAMP NOT NULL

latitude DOUBLE PRECISION
longitude DOUBLE PRECISION
accuracy DOUBLE PRECISION

metadata JSONB

client_event_id UUID UNIQUE
created_at TIMESTAMP
```

## locations

For high-frequency locations:

``` sql
id BIGSERIAL PRIMARY KEY
trip_id UUID
timestamp TIMESTAMP
latitude DOUBLE PRECISION
longitude DOUBLE PRECISION
accuracy DOUBLE PRECISION
speed DOUBLE PRECISION
bearing DOUBLE PRECISION
altitude DOUBLE PRECISION
source VARCHAR
```

Use PostGIS for spatial queries.

## break_records

``` sql
id UUID PRIMARY KEY
trip_id UUID
start_time TIMESTAMP
end_time TIMESTAMP
duration_seconds INTEGER

location_lat DOUBLE PRECISION
location_lon DOUBLE PRECISION

water BOOLEAN
food BOOLEAN
toilet BOOLEAN
rest BOOLEAN
fuel BOOLEAN
charge BOOLEAN
other BOOLEAN

confirmation_source VARCHAR
```

`confirmation_source` examples:

``` text
DRIVER_CONFIRMATION
INFERRED
MANUAL
```

Never represent inferred data as driver-confirmed data.

------------------------------------------------------------------------

# 11. Trip State Machine

Use an explicit state machine.

``` text
CREATED
   ↓
READY
   ↓
DRIVING
   ↓
POSSIBLE_STOP
   ↓
STOPPED
   ↓
RESTART_DETECTED
   ↓
BREAK_CHECKPOINT
   ↓
DRIVING
   ↓
ARRIVAL
   ↓
COMPLETED
```

Additional states:

``` text
OFFLINE
PAUSED
EMERGENCY
```

------------------------------------------------------------------------

# 12. Automatic Stop Detection

Do not rely on GPS speed alone.

Combine:

-   GPS speed
-   location displacement
-   accelerometer
-   Activity Recognition
-   time stationary
-   optional Bluetooth/car connection
-   optional charging state

## Example

If:

``` text
speed < 3 km/h
AND
location displacement < threshold
AND
stationary duration > 3 minutes
```

then:

``` text
POSSIBLE_STOP
```

If stationary for a longer threshold:

``` text
STOPPED
```

Avoid declaring every traffic light or traffic jam as a break.

------------------------------------------------------------------------

# 13. Break Classification

A key principle:

**The system detects that a stop happened; the driver confirms what
happened during the stop.**

This avoids unreliable assumptions.

Example:

``` text
Vehicle stopped
      ↓
Stop duration = 17 min
      ↓
Vehicle movement resumes
      ↓
Break checkpoint
      ↓
Driver selects:
Water + Toilet
      ↓
Server stores confirmed break
```

------------------------------------------------------------------------

# 14. Intelligent Break Checkpoint

The checkpoint should be context-aware.

Instead of always showing six buttons, prioritize relevant items.

Example:

If the driver has been driving for 3 hours and has not recorded water:

``` text
Welcome back 👋

Quick check:
☐ Water
☐ Food
☐ Toilet
☐ Rest

[Done]
```

If food was recently logged:

``` text
Break completed?

☐ Water
☐ Toilet
☐ Rest
☐ Fuel

[Done]
```

The app should never repeatedly nag.

------------------------------------------------------------------------

# 15. Hydration Intelligence

Do not claim actual hydration.

Track:

``` text
last_water_time
water_events_today
estimated_time_since_water
```

The system can generate a gentle prompt at a suitable checkpoint.

Example:

> "You haven't recorded water since 2:15 PM. Did you have water during
> this break?"

Options:

``` text
Yes
No
Skip
```

Only show this at a safe interaction point such as after stopping and
before resuming.

------------------------------------------------------------------------

# 16. Food Intelligence

Track:

``` text
last_food_time
meal_events
time_since_last_food
```

At an appropriate stop:

> "Did you have food during this break?"

Buttons:

``` text
Yes
No
Snack
Skip
```

Avoid medical/nutritional claims.

------------------------------------------------------------------------

# 17. Toilet / Bio Break Intelligence

Track:

``` text
last_toilet_break
time_since_last_toilet_break
```

The app should never infer toilet usage from GPS.

Instead:

> "Toilet break?"

``` text
Yes
No
Skip
```

This is particularly valuable for long-distance journeys.

------------------------------------------------------------------------

# 18. Rest Intelligence

Rest can be partially inferred from stop duration, but should remain
distinguishable from confirmed rest.

Example:

``` text
Stop duration: 42 minutes

System inference:
LONG_STOP

Driver confirmation:
Rest = Yes
```

Viewer sees:

> Rest --- confirmed 42 min ago

rather than simply:

> Rest detected

------------------------------------------------------------------------

# 19. Adaptive Reminder Engine

The reminder engine should consider:

``` text
journey duration
time since water
time since food
time since toilet
time since meaningful rest
recent stop duration
time since last checkpoint
driver-selected preferences
time of day
trip progress
```

Example decision:

``` text
IF driving_duration > threshold
AND recent_break = false
AND water_time > threshold
THEN
show hydration suggestion at next safe checkpoint
```

The engine should be configurable rather than hardcoding medical
thresholds.

Recommended configuration:

``` json
{
  "hydration_prompt_interval_minutes": 120,
  "food_prompt_interval_minutes": 300,
  "rest_prompt_interval_minutes": 150,
  "toilet_prompt_interval_minutes": 240,
  "minimum_stop_duration_for_break": 5
}
```

These are **product defaults**, not medical recommendations.

------------------------------------------------------------------------

# 20. ETA Engine

ETA should be continuously recalculated.

Inputs:

-   current GPS
-   destination
-   route
-   historical movement
-   current speed
-   road network estimate
-   traffic provider if available
-   stop history
-   planned breaks

Display:

``` text
ETA: 08:35 PM
Confidence: Medium
```

Avoid false precision.

Use:

``` text
ETA 8:30–8:50 PM
```

when uncertainty is high.

------------------------------------------------------------------------

# 21. Route Deviation Detection

Compare actual movement against the selected route.

Trigger:

``` text
distance_from_expected_route > configurable threshold
AND
condition persists for N minutes
```

Do not immediately alert for every deviation.

Possible reasons:

-   fuel station
-   restaurant
-   toilet break
-   detour
-   traffic diversion
-   road closure

Viewer status:

> Route deviation detected --- driver may be taking a detour.

------------------------------------------------------------------------

# 22. Intelligent Stop Classification

The system can optionally classify stops using:

-   duration
-   nearby POI category
-   time of day
-   historical pattern
-   driver confirmation

For example:

``` text
Location:
Highway restaurant

Stop:
23 minutes

Driver:
Food + Water

Final classification:
FOOD_BREAK
```

The POI information should only be a hint, never the authoritative
source.

------------------------------------------------------------------------

# 23. Place Intelligence

Maintain a local place cache.

Each place can contain:

``` text
latitude
longitude
radius
category
name
confidence
visit_count
last_visit
```

Potential categories:

``` text
HOME
OFFICE
FAMILY
FRIEND
RELATIVE
RELIGIOUS_PLACE
RESTAURANT
FUEL
CHARGING
HOTEL
HIGHWAY_STOP
UNKNOWN
```

This can help reduce network dependence and improve contextual
interpretation.

------------------------------------------------------------------------

# 24. Location Sampling Strategy

Do not continuously request maximum GPS precision.

Use adaptive sampling.

## Driving

Example:

``` text
GPS: every 15–30 seconds
```

Adjust dynamically based on:

-   speed
-   road type
-   battery
-   network
-   required viewer freshness

## Stationary

Reduce frequency:

``` text
GPS: every 2–5 minutes
```

## Offline

Store locally.

## Low battery

Use a battery-preservation mode.

The exact values should be configurable and tested against real-world
battery consumption.

------------------------------------------------------------------------

# 25. Battery Strategy

The app must survive a 1,100 km journey.

Strategies:

1.  Foreground service while trip is active.
2.  Adaptive GPS sampling.
3.  Avoid unnecessary reverse geocoding.
4.  Batch server synchronization.
5.  Compress location payloads.
6.  Use local event queue.
7.  Reduce sampling when stationary.
8.  Avoid continuous accelerometer polling where unnecessary.
9.  Use Android Activity Recognition where appropriate.
10. Show driver battery status to viewer.

Viewer example:

``` text
Driver phone
Battery: 38%
Last sync: 1 min ago
Network: Online
```

------------------------------------------------------------------------

# 26. Offline Mode

Offline operation is a first-class requirement.

When internet disappears:

``` text
● OFFLINE

Journey tracking continues.

Last server update:
21:03
```

Locally store:

``` text
locations
events
breaks
trip state
ETA calculations where possible
```

When network returns:

``` text
43 offline events found

Syncing...
██████████████████ 100%

Last server update:
21:49
```

The server must preserve original event timestamps.

------------------------------------------------------------------------

# 27. Data Synchronization

Use an idempotent event model.

Every client event gets:

``` text
client_event_id = UUID
```

Server behavior:

``` text
IF client_event_id already exists:
    return existing ACK
ELSE:
    insert event
```

This prevents duplicate events when network connectivity is unreliable.

------------------------------------------------------------------------

# 28. Live Updates

Use:

-   WebSocket for active trip viewers
-   SSE as a simpler alternative
-   polling fallback

Architecture:

``` text
Driver
  │
  │ location/event
  ▼
Backend
  │
  ▼
Redis Pub/Sub
  │
  ▼
Viewer WebSocket
  │
  ▼
Live Map + Status
```

The viewer should receive:

-   current location
-   trip state
-   ETA
-   last event
-   break status
-   network status
-   battery status

------------------------------------------------------------------------

# 29. Privacy Model

This is a **secret trip-sharing system**, not a public social location
app.

Important principles:

-   no public trip search
-   no user directory
-   no permanent public URLs
-   no indexing by search engines
-   trip-scoped authorization
-   short-lived credentials
-   trip expiration
-   password hashing
-   rate limiting
-   audit logging
-   HTTPS only

------------------------------------------------------------------------

# 30. Trip Password Lifecycle

Trip credentials should be ephemeral.

Example:

``` text
Trip created
     ↓
Password generated
     ↓
Driver shares it
     ↓
Trip active
     ↓
Trip completed
     ↓
Grace period
     ↓
Credentials revoked
     ↓
Trip access destroyed
```

The trip record may optionally be retained locally by the driver, but
viewer access should automatically expire.

A configurable policy:

``` text
Trip active:
Password valid

After trip completion:
Password revoked immediately

Optional encrypted trip archive:
retained locally / explicitly exported
```

"Self-destroyed" should mean **access credentials and live sharing
capability are destroyed**, rather than relying on literal deletion of
every database record.

------------------------------------------------------------------------

# 31. Authentication

For a simple viewer:

``` text
Trip ID + Password
```

Backend:

``` text
Trip ID
   ↓
Find trip
   ↓
Verify password hash
   ↓
Issue short-lived viewer token
   ↓
Viewer receives read-only access
```

Token should contain:

``` text
trip_id
viewer_role
expiry
permissions
```

Viewer permissions:

``` text
READ_LOCATION
READ_ETA
READ_BREAKS
READ_STATUS
```

No driver-control permissions.

------------------------------------------------------------------------

# 32. Abuse Protection

Implement:

-   IP rate limiting
-   failed-login throttling
-   password attempt limits
-   temporary lockouts
-   credential rotation
-   trip expiration
-   no location API without authorization
-   no trip enumeration

Trip IDs should be sufficiently random and non-sequential.

------------------------------------------------------------------------

# 33. Driver Safety

The app must be explicitly designed around **zero driver distraction**.

Rules:

### Never

-   ask questions while the vehicle is moving
-   require typing while driving
-   show long messages while driving
-   require map interaction
-   require confirmation of routine location updates

### Safe interaction windows

Only interact when:

``` text
vehicle stationary
AND
stop duration is sufficient
```

or after movement resumes but before the next trip segment begins,
depending on the UX and Android behavior.

A stronger safety design is:

``` text
STOP DETECTED
      ↓
wait
      ↓
vehicle stopped
      ↓
checkpoint available
      ↓
driver completes
      ↓
lock interaction
      ↓
vehicle moving
```

------------------------------------------------------------------------

# 34. Emergency Mode

Optional future feature.

Viewer may have:

``` text
"Something seems wrong"
```

But avoid giving viewers arbitrary control over the driver's phone.

Possible future workflow:

``` text
Viewer flags concern
        ↓
Backend records concern
        ↓
Driver receives notification only when safe
```

Potential emergency triggers:

-   prolonged unexpected stop
-   no location update for long period
-   very low battery
-   route deviation
-   no network for prolonged period

These should be treated as **signals**, not proof of an emergency.

------------------------------------------------------------------------

# 35. Long Silence / Stale Location Logic

The viewer should clearly distinguish:

``` text
LIVE
STALE
OFFLINE
UNKNOWN
```

Example:

``` text
● LIVE
Last update: 24 sec ago
```

or:

``` text
⚠ STALE
Last update: 14 min ago
```

or:

``` text
○ OFFLINE
Last known location: 21:02
```

Do not show an old location as if it were current.

------------------------------------------------------------------------

# 36. Viewer Dashboard

Recommended sections:

## Header

``` text
Trip: Hyderabad → Thrissur
Status: LIVE
```

## Map

Large map with:

-   current position
-   route
-   origin
-   destination
-   previous stops
-   current movement
-   optional breadcrumb trail

## Journey Progress

``` text
642 / 1,100 km
58%
```

## ETA

``` text
08:35 PM
```

## Driver Status

``` text
Driving: 2h 17m
Last break: 35m ago
```

## Wellbeing

``` text
💧 Water      35m
🍛 Food       3h 10m
🚻 Toilet     35m
😴 Rest       35m
```

## Timeline

Chronological event stream.

------------------------------------------------------------------------

# 37. Driver Dashboard

Keep it extremely simple.

``` text
┌──────────────────────────────┐
│       TRIP IN PROGRESS       │
│                              │
│ Hyderabad → Thrissur         │
│                              │
│ 642 km / 1100 km             │
│                              │
│ ETA 08:35 PM                 │
│                              │
│ ● LIVE                       │
│                              │
│ Last break: 35 min ago       │
│                              │
│ [Pause Trip]                 │
│ [End Trip]                   │
└──────────────────────────────┘
```

------------------------------------------------------------------------

# 38. Trip Start Screen

``` text
Start New Trip

From:
[ Current Location ]

Destination:
[ Thrissur ]

Expected departure:
[ Now ]

Optional:
☐ Planned stops
☐ Emergency contact
☐ Share trip QR

[ CREATE TRIP ]
```

After creation:

``` text
Trip created successfully

Trip ID:
TP-7K4M-X92Q

Secret password:
••••••••••

[Copy]
[Share]
[Show QR]

Keep these credentials private.
```

------------------------------------------------------------------------

# 39. QR Sharing

Generate:

``` text
Trip ID
+
one-time secret
```

Viewer scans QR.

The QR should not contain permanent authorization.

Prefer:

``` text
short-lived invitation token
```

that exchanges for viewer access.

------------------------------------------------------------------------

# 40. Notifications to Viewer

Optional configurable notifications:

### Trip started

> Phoenix's trip has started.

### Major progress

> Trip has crossed 50%.

### Break

> Break completed --- Water + Toilet.

### Food

> Food break recorded.

### Long rest

> Driver completed a 28-minute rest break.

### ETA change

> ETA changed from 8:10 PM to 8:35 PM.

### Arrival

> Trip completed --- arrived at Thrissur.

### Stale location

> No location update received for 10 minutes.

Notifications should avoid unnecessarily alarming language.

------------------------------------------------------------------------

# 41. Intelligent Notification Prioritization

Do not send a notification for every location update.

Use notification classes:

``` text
LOW
NORMAL
IMPORTANT
CRITICAL
```

Examples:

``` text
LOCATION_UPDATE → no notification
ETA_UPDATE → no notification unless significant
BREAK → optional
ARRIVAL → important
LONG_STALE_LOCATION → important
EMERGENCY → critical
```

------------------------------------------------------------------------

# 42. Trip Timeline

A highly valuable feature.

Example:

``` text
06:20 AM  Trip started
07:52 AM  Water
09:10 AM  Toilet + Water
11:45 AM  Food + Rest
12:22 PM  Trip resumed
02:35 PM  Fuel
02:48 PM  Trip resumed
05:10 PM  Water + Toilet
07:15 PM  ETA updated
08:34 PM  Arrived
```

This becomes the complete digital journey record.

------------------------------------------------------------------------

# 43. Trip Analytics

After completion:

``` text
Trip Summary

Distance             1,103 km
Driving time         13h 22m
Total trip time      16h 05m
Stops                7
Food breaks          3
Water confirmations  6
Toilet breaks        4
Rest breaks          3
Fuel stops            2

Longest driving leg  3h 12m
Longest break        42m
```

Do not frame this as a medical assessment.

------------------------------------------------------------------------

# 44. Trip Replay

Future feature.

Allow viewer/driver to replay:

``` text
06:20 ─────────────── 20:34
       ●──●──●──●──●
           ↓
        stops
```

Playback speed:

``` text
1x
5x
20x
```

------------------------------------------------------------------------

# 45. AI / Intelligence Layer

Do not start with an LLM.

The first version should use a deterministic event/rules engine.

Recommended:

``` text
Sensor Fusion
+
State Machine
+
Rules Engine
+
ETA Engine
+
Context Engine
```

Later, an AI layer can summarize the journey.

Example:

> "You completed the first half of the trip with three breaks. Your
> longest continuous driving segment was 3 hours 12 minutes. You
> recorded water at four stops and food at two stops."

LLM should not be responsible for factual sensor interpretation.

------------------------------------------------------------------------

# 46. Future AI Capabilities

Possible future modules:

## Journey Copilot

Summarizes trip status.

## Personalized Break Prediction

Learns:

``` text
typical driving duration
typical break duration
preferred food times
usual fuel intervals
```

## Smart Stop Recommendation

Suggest:

``` text
safe stopping area
restaurant
fuel station
charging station
restroom
hotel
```

## Delay Prediction

Combine:

-   historical traffic
-   route
-   current speed
-   stop behavior

## Journey Health Pattern

Not medical diagnosis, but behavioral summary:

``` text
Long continuous driving segments detected.
Break frequency decreased compared with previous trips.
```

------------------------------------------------------------------------

# 47. Backdated / Missing Data Recovery

The local-first event model should support corrections.

Example:

Internet unavailable during a stop.

Later the driver realizes:

> "I had food at 1:20 PM but forgot to record it."

Allow:

``` text
Add Missing Event

Type:
Food

Time:
01:20 PM

Location:
Current / last known / manual

[Save]
```

Mark:

``` text
confirmation_source = MANUAL
```

Never overwrite the original raw events.

------------------------------------------------------------------------

# 48. Data Provenance

Every important fact should have provenance.

Example:

``` json
{
  "event": "WATER_REPORTED",
  "source": "DRIVER_CONFIRMATION",
  "confidence": 1.0
}
```

Inferred:

``` json
{
  "event": "LONG_STOP",
  "source": "SENSOR_INFERENCE",
  "confidence": 0.91
}
```

This is important for future AI reasoning and debugging.

------------------------------------------------------------------------

# 49. API Design

## Create Trip

``` http
POST /api/v1/trips
```

Request:

``` json
{
  "origin": "Hyderabad",
  "destination": "Thrissur",
  "planned_departure": "2026-08-10T22:00:00+05:30"
}
```

Response:

``` json
{
  "trip_id": "TP-7K4M-X92Q",
  "secret": "generated-secret",
  "expires_at": "..."
}
```

## Start Trip

``` http
POST /api/v1/trips/{trip_id}/start
```

## Upload Events

``` http
POST /api/v1/trips/{trip_id}/events/batch
```

## Current State

``` http
GET /api/v1/trips/{trip_id}/state
```

## Viewer Authentication

``` http
POST /api/v1/viewer/auth
```

## Live Stream

``` text
GET /api/v1/trips/{trip_id}/stream
```

or WebSocket:

``` text
/ws/trips/{trip_id}
```

## End Trip

``` http
POST /api/v1/trips/{trip_id}/complete
```

------------------------------------------------------------------------

# 50. Event Payload

Example:

``` json
{
  "client_event_id": "uuid",
  "event_type": "LOCATION_UPDATE",
  "event_time": "2026-08-11T04:20:10+05:30",
  "latitude": 10.1234,
  "longitude": 76.1234,
  "accuracy": 8.4,
  "speed": 72.2,
  "bearing": 185,
  "metadata": {}
}
```

Break:

``` json
{
  "client_event_id": "uuid",
  "event_type": "BREAK_CHECKPOINT",
  "event_time": "...",
  "metadata": {
    "water": true,
    "food": false,
    "toilet": true,
    "rest": true,
    "fuel": false
  }
}
```

------------------------------------------------------------------------

# 51. Security Architecture

``` text
Driver App
   │
 TLS
   ▼
API Gateway
   │
Rate Limiting
   │
Authentication
   │
Trip Authorization
   │
Backend
   │
PostgreSQL
```

Security requirements:

-   TLS
-   hashed passwords
-   secure random trip IDs
-   short-lived viewer tokens
-   no raw credentials in logs
-   no credentials in analytics
-   encrypted local sensitive storage
-   API rate limiting
-   audit logs
-   automatic credential expiry

------------------------------------------------------------------------

# 52. Data Retention

Recommended default:

### Live trip

Full resolution location.

### After completion

Optionally reduce location history to a lower-resolution trip summary.

### Credentials

Destroy/revoke immediately after trip completion.

### Raw location

Retain only if explicitly configured.

Privacy should be the default.

------------------------------------------------------------------------

# 53. Permission Strategy

Android permissions should be requested progressively.

Potential permissions:

-   location while using app
-   background location where required
-   activity recognition
-   notifications
-   Bluetooth only if needed
-   battery optimization guidance

Do not request unnecessary permissions.

Explain why each permission is required.

------------------------------------------------------------------------

# 54. Android Background Execution

Trip tracking should use an Android foreground service when an active
trip requires continuous location tracking.

The app should clearly display:

> TripPulse is tracking this active trip.

When trip ends:

``` text
Foreground service stops.
Location tracking stops.
```

This avoids accidental continuous tracking outside trips.

------------------------------------------------------------------------

# 55. Location Accuracy Strategy

Every location event should store accuracy.

Example:

``` text
accuracy = 8m
```

Viewer can show:

> Last location ±8m

Low-quality GPS should not be treated as precise.

When GPS is temporarily unavailable:

``` text
GPS unavailable
Using last known position
```

------------------------------------------------------------------------

# 56. Multi-Sensor Fusion

Potential signals:

``` text
GPS/GNSS
Accelerometer
Gyroscope
Activity Recognition
Network state
Bluetooth/car connection
Battery
Charging state
```

Use a sensor abstraction layer:

``` text
LocationProvider
ActivityProvider
MotionProvider
ConnectivityProvider
VehicleProvider
BatteryProvider
```

This keeps the architecture replaceable.

------------------------------------------------------------------------

# 57. Driver Interaction Design

The app should behave more like an intelligent background assistant than
a chat application.

### During movement

Silent.

### At stop

Observe.

### After restart / safe checkpoint

Ask one compact question.

### During prolonged journey

Use the next safe interaction opportunity.

### If driver skips

Accept it.

Never repeatedly nag.

------------------------------------------------------------------------

# 58. UX Principle: One-Tap Truth

Every driver question should be answerable with one tap.

Bad:

> "Please tell us what you did during the last break and whether you
> consumed water."

Good:

``` text
Break completed?

💧 Water
🍛 Food
🚻 Toilet
😴 Rest
⛽ Fuel
```

Multi-select + Done.

------------------------------------------------------------------------

# 59. PWA Viewer Requirements

Viewer must work well on:

-   Android
-   iPhone
-   desktop browser

No app installation required.

Viewer opens:

``` text
tripshare.example.com
```

and enters credentials.

The UI should be responsive.

------------------------------------------------------------------------

# 60. Suggested Project Structure

## Android

``` text
android/
 ├── app/
 ├── core/
 │   ├── location/
 │   ├── sensors/
 │   ├── database/
 │   ├── sync/
 │   └── security/
 ├── feature/
 │   ├── trip/
 │   ├── checkpoint/
 │   ├── dashboard/
 │   └── settings/
 └── service/
     └── TripTrackingService
```

## Backend

``` text
backend/
 ├── app/
 │   ├── api/
 │   ├── auth/
 │   ├── trips/
 │   ├── events/
 │   ├── tracking/
 │   ├── eta/
 │   ├── notifications/
 │   └── database/
 ├── tests/
 └── Dockerfile
```

## Web

``` text
web/
 ├── src/
 │   ├── pages/
 │   ├── components/
 │   ├── map/
 │   ├── trip/
 │   ├── timeline/
 │   └── api/
```

------------------------------------------------------------------------

# 61. Development Phases

## Phase 1 --- Core POC

Build:

-   Android trip creation
-   trip ID/password
-   GPS tracking
-   local SQLite/Room
-   backend API
-   PostgreSQL
-   basic viewer
-   live map
-   ETA
-   trip start/end

## Phase 2 --- Automatic Journey Engine

Add:

-   stop detection
-   movement detection
-   trip state machine
-   offline queue
-   synchronization
-   stale-location handling
-   adaptive GPS

## Phase 3 --- Wellbeing Checkpoints

Add:

-   water
-   food
-   toilet
-   rest
-   fuel
-   charging
-   one-tap checkpoint
-   event timeline

## Phase 4 --- Security & Reliability

Add:

-   rate limiting
-   credential expiration
-   audit logging
-   retries
-   idempotency
-   battery optimization
-   permission handling

## Phase 5 --- Intelligence

Add:

-   adaptive reminders
-   route deviation
-   intelligent stop classification
-   contextual checkpoint questions
-   personalized journey patterns

## Phase 6 --- Polish

Add:

-   trip replay
-   trip summary
-   notifications
-   QR sharing
-   PWA
-   installable Android APK/AAB
-   monitoring and analytics

------------------------------------------------------------------------

# 62. MVP Definition

The MVP is complete when a real-world road trip can run end-to-end.

### Driver

-   Create trip
-   Start trip
-   Lock phone
-   Drive
-   App automatically tracks location
-   Stop detected
-   Restart detected
-   Checkpoint shown
-   Driver records Water/Food/Toilet/Rest
-   Data syncs
-   Driver can continue offline
-   Trip ends

### Viewer

-   Enter Trip ID/password
-   See live location
-   See route
-   See ETA
-   See last update
-   See stops
-   See wellbeing events
-   See timeline
-   Know whether tracking is live/offline/stale
-   Lose access automatically when trip expires

------------------------------------------------------------------------

# 63. Acceptance Criteria

## Location

-   Location continues during active trip when app is
    backgrounded/locked.
-   Location survives temporary network loss.
-   Offline events synchronize later.
-   Duplicate events are not created.

## Stops

-   Normal traffic does not generate excessive break events.
-   A genuine multi-minute stop is detected.
-   Stop duration is recorded.
-   Restart is detected.

## Checkpoint

-   Driver receives checkpoint only around a safe stop/restart
    interaction.
-   Checkpoint is one-screen.
-   Multi-select works.
-   Skip works.
-   No repeated nagging.

## Viewer

-   Viewer sees current location.
-   Viewer sees last update timestamp.
-   Viewer can distinguish live/offline/stale.
-   Viewer sees confirmed wellbeing events.
-   Viewer sees ETA.

## Security

-   Incorrect passwords do not reveal trip existence unnecessarily.
-   Credentials are rate limited.
-   Password is never stored in plaintext.
-   Completed trips automatically revoke live access.

------------------------------------------------------------------------

# 64. Testing Strategy

## Unit tests

-   stop detection
-   state transitions
-   ETA calculation
-   reminder rules
-   event deduplication
-   credential expiration

## Instrumentation tests

-   background service
-   GPS handling
-   offline synchronization
-   restart detection
-   permission flows

## Backend tests

-   authentication
-   authorization
-   event ingestion
-   duplicate events
-   expired trip
-   WebSocket
-   rate limiting

## Real-world tests

Test journeys of:

-   20 km
-   100 km
-   300 km
-   1,100 km

Test:

-   no network
-   intermittent network
-   GPS disabled
-   low battery
-   app killed
-   phone reboot
-   long stop
-   short traffic stop
-   highway driving
-   route deviation
-   arrival

------------------------------------------------------------------------

# 65. Observability

Backend should monitor:

``` text
active trips
location ingestion rate
event ingestion rate
sync failures
average event latency
WebSocket connections
authentication failures
expired trips
API latency
database health
```

Driver app diagnostics:

``` text
GPS availability
network state
battery
last sync
queued events
location accuracy
tracking service state
```

A debug screen can help during development.

------------------------------------------------------------------------

# 66. Important Edge Cases

### Phone loses internet

Continue locally.

### Phone loses GPS

Use last known location and display uncertainty.

### Phone battery dies

Viewer sees:

> Last update received at 18:42. Phone may be offline.

Never pretend the driver is still moving.

### Driver forgets to end trip

Automatically end after:

``` text
arrival + configurable grace period
```

or prolonged inactivity.

### Driver stops at traffic

Do not classify as a break immediately.

### Driver takes a long break

Ask checkpoint after restart.

### Driver skips checkpoint

Record:

``` text
BREAK_CHECKPOINT_SKIPPED
```

### Driver changes destination

Allow destination update.

### Driver returns after completing

New trip must generate new credentials.

------------------------------------------------------------------------

# 67. Product Differentiator

The major differentiator is not simply:

> "Live location sharing."

Existing applications already do that.

The differentiator is:

> **Live location + journey intelligence + automatic stop detection +
> private trip-scoped access + driver wellbeing checkpointing with
> almost zero driver interaction.**

The viewer gets a richer answer than:

> "Where is he?"

They get:

> "Where is he, how long has he been driving, when did he last stop, did
> he eat, did he drink water, did he take a toilet break, did he rest,
> what is the ETA, and is the tracking currently reliable?"

------------------------------------------------------------------------

# 68. Recommended Product Name

Working name:

# TripPulse

Possible future branding:

-   TripPulse
-   JourneyPulse
-   SafeJourney
-   RoadPulse
-   TripGuardian
-   JourneyGuardian
-   DrivePulse

For development, use **TripPulse**.

------------------------------------------------------------------------

# 69. Claude / Lovable Implementation Instructions

The implementation agent should NOT attempt to build the entire
production system in one step.

Follow this sequence:

## Step 1

Create the monorepo:

``` text
/apps/android
/apps/web
/services/backend
/packages/shared
/docs
```

## Step 2

Implement the domain model and API contracts first.

## Step 3

Implement backend trip creation/authentication.

## Step 4

Implement Android local trip state.

## Step 5

Implement GPS event collection.

## Step 6

Implement local event queue.

## Step 7

Implement backend synchronization.

## Step 8

Implement viewer dashboard.

## Step 9

Implement stop/restart detection.

## Step 10

Implement wellbeing checkpoint.

## Step 11

Implement ETA and live updates.

## Step 12

Implement security, expiry and production hardening.

Do not add AI until deterministic functionality works reliably.

------------------------------------------------------------------------

# 70. Engineering Principles for the AI Coding Agent

The coding agent should follow these rules:

1.  **Local-first.**
2.  **Offline-first.**
3.  **Event-driven.**
4.  **Battery-aware.**
5.  **Privacy-first.**
6.  **Driver-safety-first.**
7.  **Never fabricate sensor certainty.**
8.  **Keep raw events separate from inferred events.**
9.  **Keep driver-confirmed data separate from system inference.**
10. **Make every sensor-derived decision explainable.**
11. **Use configurable thresholds.**
12. **Make synchronization idempotent.**
13. **Never expose live location without valid trip authorization.**
14. **Never ask the driver to interact while driving.**
15. **Do not introduce an LLM where deterministic logic is sufficient.**

------------------------------------------------------------------------

# 71. Future Architecture

Eventually the system can evolve into:

``` text
                 ┌──────────────────┐
                 │   Android App    │
                 └────────┬─────────┘
                          │
                   Sensor Fusion
                          │
                    Event Stream
                          │
             ┌────────────▼────────────┐
             │ Journey Intelligence    │
             │                         │
             │ State Machine           │
             │ Context Engine          │
             │ Reminder Engine         │
             │ ETA Engine              │
             │ Safety Signals          │
             └────────────┬────────────┘
                          │
                    Event Platform
                          │
          ┌───────────────┼───────────────┐
          │               │               │
       Live API       Analytics       AI Summary
          │               │               │
          ▼               ▼               ▼
       Viewer         Trip History     Journey Copilot
```

------------------------------------------------------------------------

# 72. Final Product Definition

TripPulse should feel like a **silent digital companion for a
long-distance driver**.

The driver should be able to:

> Start trip → lock phone → drive → stop → answer one tiny checkpoint →
> continue → arrive.

The family member should be able to:

> Enter trip credentials → see location → see ETA → see journey progress
> → see breaks → see food/water/toilet/rest confirmations → know whether
> tracking is live → wait without repeatedly calling.

The architecture should make this possible even when:

-   the internet disappears,
-   GPS becomes temporarily inaccurate,
-   the app is backgrounded,
-   the phone battery becomes low,
-   events are delayed,
-   the driver forgets to record something.

The system should always clearly distinguish:

**Observed by sensor**\
vs.\
**Inferred by software**\
vs.\
**Confirmed by driver**.

That distinction is fundamental to making the application trustworthy.

------------------------------------------------------------------------

# 73. Immediate Build Target

For the first implementation, build this exact vertical slice:

``` text
CREATE TRIP
    ↓
TRIP ID + SECRET
    ↓
START TRIP
    ↓
ANDROID FOREGROUND TRACKING
    ↓
GPS → LOCAL ROOM DB
    ↓
SYNC → FASTAPI
    ↓
POSTGRESQL
    ↓
WEB VIEWER
    ↓
LIVE MAP + ETA
    ↓
STOP DETECTION
    ↓
VEHICLE RESTART
    ↓
BREAK CHECKPOINT
    ↓
WATER / FOOD / TOILET / REST
    ↓
SYNC
    ↓
VIEWER TIMELINE
    ↓
ARRIVAL
    ↓
TRIP COMPLETION
    ↓
CREDENTIAL REVOCATION
```

This vertical slice should be implemented and tested with a real short
drive before adding advanced AI, analytics, recommendations, or
additional integrations.

------------------------------------------------------------------------

# 74. Critical Revision --- Live-First Architecture for the Kerala Test Trip

The first field test is the **Hyderabad → Thrissur Kerala road trip**,
with the user acting as the driver and family/friends monitoring the
same trip.

This changes the priority of the architecture:

> **The viewer experience must be live whenever connectivity permits,
> and must degrade gracefully to the freshest possible state when
> connectivity is unavailable.**

The app should therefore optimize for **freshness**, not merely eventual
synchronization.

The central question for the viewer is:

> "What is happening with the journey right now?"

not merely:

> "What was the last location eventually uploaded?"

## 74.1 Freshness states

Every viewer session must expose one of these states:

``` text
LIVE
RECENT
OFFLINE
STALE
TRIP COMPLETED
```

Example:

``` text
🟢 LIVE
Location updated 18 seconds ago
```

``` text
🟡 RECENT
Location updated 2 minutes ago
Network currently intermittent
```

``` text
🟠 STALE
Last confirmed location 14 minutes ago
Driver device has not synchronized recently
```

``` text
🔴 OFFLINE
Last confirmed location 31 minutes ago
No current connection from driver device
```

The UI must never imply that a stale location is the driver's current
location.

------------------------------------------------------------------------

# 75. Live Data Architecture

For this project, the initial backend should be simplified to a
**Firebase-first architecture** rather than operating a custom
FastAPI/PostgreSQL infrastructure.

The primary reason is the combination of:

-   realtime synchronization
-   Android support
-   offline persistence
-   low operational complexity
-   generous initial no-cost usage
-   no server to maintain
-   easy Play Store deployment
-   straightforward realtime viewer updates

Firebase Realtime Database currently provides a no-cost Spark tier with
1 GB storage and 10 GB/month downloads. Its current Spark
simultaneous-connection limit is 100; the overall RTDB platform limit is
much higher on paid configurations. Therefore the free deployment is
appropriate for the personal Kerala test and early private usage, but
the architecture must not assume that the free tier can support
unlimited public concurrent viewers indefinitely.
citeturn0search0turn0search17

The important distinction is:

> **Any number of people may possess valid trip credentials, but the
> free infrastructure has finite concurrent-connection/bandwidth
> limits.**

For the user's test case --- driver + mother + wife + friend --- this is
comfortably within the intended initial architecture.

------------------------------------------------------------------------

# 76. Revised Backend Architecture

``` text
                     DRIVER ANDROID APP
                            │
                 ┌──────────▼──────────┐
                 │ Sensor / GPS Engine  │
                 └──────────┬──────────┘
                            │
                     Local Event Store
                            │
                 ┌──────────▼──────────┐
                 │ Live Sync Controller │
                 └──────────┬──────────┘
                            │
                  INTERNET AVAILABLE?
                       ┌────┴────┐
                      YES        NO
                       │          │
                       ▼          ▼
             Firebase Realtime   Local Queue
                Database             │
                       │             │
                       │      Reconnect / retry
                       │             │
                       └──────┬──────┘
                              │
                    Firebase Realtime DB
                              │
             ┌────────────────┼────────────────┐
             │                │                │
             ▼                ▼                ▼
           Mom             Wife             Friend
         Viewer App       Viewer App       Viewer App
```

------------------------------------------------------------------------

# 77. Two-Tier Location Storage

Do not write every GPS point as a high-volume historical record into the
same realtime node.

Separate:

## A. Live State

One constantly updated record:

``` text
/trips/{trip_access_key}/live
```

Contains:

``` json
{
  "lat": 10.1234,
  "lng": 76.1234,
  "accuracy": 8,
  "speed": 72,
  "bearing": 184,
  "timestamp": 1786453210000,
  "eta": 1786469000000,
  "progress": 0.67,
  "status": "DRIVING",
  "network": "ONLINE",
  "battery": 64,
  "lastSync": 1786453215000
}
```

This node is overwritten, not appended.

This is the **source of truth for live viewing**.

## B. Journey History

Historical events are stored separately:

``` text
/trips/{trip_access_key}/events/{event_id}
```

Examples:

``` text
TRIP_STARTED
STOP_STARTED
BREAK_CHECKPOINT
WATER
FOOD
TOILET
REST
FUEL
ROUTE_DEVIATION
TRIP_RESUMED
ARRIVAL
```

This prevents the realtime dashboard from becoming unnecessarily heavy.

------------------------------------------------------------------------

# 78. Live Update Frequency

The live channel should be adaptive.

### Strong internet + good battery

Target:

``` text
10–20 second live location updates
```

### Normal connectivity

Target:

``` text
20–30 seconds
```

### Weak/intermittent connectivity

Attempt:

``` text
30–60 seconds
```

with aggressive reconnect.

### Stationary

Reduce location sampling.

### Very poor network

Store locally and send the newest location as soon as possible.

The system should prioritize the **latest location** over uploading
every intermediate point immediately.

------------------------------------------------------------------------

# 79. Freshness Optimization

A crucial optimization:

When reconnecting after network loss, do NOT blindly upload thousands of
historical GPS points before sending the current position.

Use this sequence:

``` text
NETWORK RETURNS
       ↓
SEND CURRENT LIVE STATE FIRST
       ↓
VIEWER BECOMES CURRENT
       ↓
UPLOAD MISSED HISTORY IN BACKGROUND
```

This is extremely important for the mother's experience.

Example:

The phone has been offline for 18 minutes.

When network returns:

``` text
Priority 1:
Current location

Priority 2:
Current trip status

Priority 3:
Current ETA

Priority 4:
Recent stop/break events

Priority 5:
Historical GPS points
```

Thus the viewer becomes "live" again immediately.

------------------------------------------------------------------------

# 80. What Happens When the Driver Has No Internet?

There is an unavoidable physical limitation:

> A remote viewer cannot receive a brand-new GPS position from the
> driver's phone while that phone has no communication path to the
> internet or to the viewer.

Therefore the app should **never pretend it can provide live remote
location without connectivity**.

Instead it should provide the closest possible experience:

``` text
Driver GPS continues locally
        ↓
Current location continuously updated on phone
        ↓
Events stored locally
        ↓
Network unavailable
        ↓
Viewer sees:
"Last confirmed location"
        ↓
Network returns
        ↓
Current location immediately uploaded
        ↓
Viewer becomes LIVE again
```

This is the correct definition of "close to live" for an
internet-dependent remote viewer.

------------------------------------------------------------------------

# 81. Optional Future Communication Fallbacks

Do NOT implement these in MVP.

Future versions may investigate:

-   SMS location fallback
-   satellite messaging
-   peer-to-peer nearby communication
-   specialized emergency communication APIs

However, these introduce:

-   additional permissions
-   hardware/device constraints
-   carrier dependencies
-   Play Store policy considerations
-   additional cost

For the first Kerala test, **internet + local buffering + immediate
reconnect** is the correct architecture.

------------------------------------------------------------------------

# 82. Mom-Centric Viewer Experience

Because the primary real-world motivation is that family members may be
anxious during a day-long drive, the viewer should optimize for
reassurance.

Instead of forcing the viewer to interpret raw technical data, show:

``` text
---------------------------------------
        PHOENIX IS ON THE ROAD
---------------------------------------

🟢 LIVE

Currently near Palakkad

Hyderabad → Thrissur

███████████████░░░░░
78% complete

ETA
08:42 PM

Last movement
18 seconds ago

Driving
2h 08m since last break

---------------------------------------
WELLBEING

💧 Water       42 min ago
🍛 Food        2h 15m ago
🚻 Toilet      42 min ago
😴 Rest        42 min ago

---------------------------------------
LAST BREAK

18:12 – 18:29
Water + Toilet + Rest

---------------------------------------
```

The viewer should not have to call the driver simply to answer:

> "Where are you now?"

------------------------------------------------------------------------

# 83. Multiple Viewers

A trip is not tied to one viewer.

Valid viewers can include:

``` text
Mother
Wife
Friend
Sibling
Relative
```

All receive the same read-only trip state.

Architecture:

``` text
                   ┌─────────────┐
                   │ Driver App  │
                   └──────┬──────┘
                          │
                          ▼
                 Firebase RTDB
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
        Mom             Wife            Friend
```

All viewers subscribe to the same live state.

A viewer opening the trip at any point receives the current state
immediately.

------------------------------------------------------------------------

# 84. Viewer Join at Any Time

This is a hard requirement.

Example:

Driver starts:

``` text
06:00 AM
```

Mom opens:

``` text
06:15 AM
```

Wife opens:

``` text
10:40 AM
```

Friend opens:

``` text
04:25 PM
```

All should immediately receive:

``` text
current location
current ETA
current journey state
current progress
last break
wellbeing state
recent timeline
```

They should NOT need to wait for a future update.

------------------------------------------------------------------------

# 85. Trip Access Model

Use:

``` text
TRIP ID
+
SECRET TRIP PASSWORD
```

The secret should be cryptographically random.

For implementation, the password should not be treated as a conventional
weak human password.

Recommended:

``` text
Trip ID:
TP-KERALA-7F4M

Secret:
7K9X-4P2N-8Q7M
```

The driver can share this manually or via QR.

The system should internally derive a secure trip access key from the
secret rather than exposing raw database identifiers.

------------------------------------------------------------------------

# 86. Trip Credential Expiration

The user specifically wants the credentials to self-destruct after each
trip.

Implement:

``` text
ACTIVE
  ↓
ARRIVED
  ↓
COMPLETED
  ↓
ACCESS REVOKED
```

At completion:

-   invalidate viewer access
-   invalidate live stream
-   stop driver tracking
-   stop foreground service
-   remove/revoke trip secret
-   optionally retain only a privacy-preserving trip summary
-   purge raw live location after configured retention

The next trip generates a completely new credential.

------------------------------------------------------------------------

# 87. Overnight Halt Intelligence

A long stop is not automatically a hotel stay.

The system should detect:

``` text
STOP_DURATION > 2 HOURS
```

and classify it as:

``` text
LONG_STOP
```

When the driver eventually resumes:

``` text
You stopped for 3h 14m.

Did you stay overnight?

[ Yes — Hotel / Accommodation ]
[ Yes — Home / Relative ]
[ No — Long Break ]
[ Other ]
```

For a very long stop extending across night/morning, the prompt becomes:

``` text
Good morning!

You were stopped overnight.

Where did you stay?

🏨 Hotel / Lodge
🏠 Home
👨‍👩‍👧 Family / Friend
🚗 Vehicle / Other
```

------------------------------------------------------------------------

# 88. Overnight State

Add a dedicated state:

``` text
OVERNIGHT_REST
```

State machine:

``` text
DRIVING
   ↓
LONG_STOP
   ↓
OVERNIGHT_CANDIDATE
   ↓
DRIVER_CONFIRMED
   ↓
OVERNIGHT_REST
   ↓
MORNING_RESTART
   ↓
DRIVING
```

Viewer sees:

``` text
🌙 OVERNIGHT REST

Driver stopped at:
11:42 PM

Rest location:
Near Salem

Duration:
6h 31m

Journey resumes:
06:13 AM
```

If the driver does not confirm the overnight stay, the viewer should
say:

> "Long stop --- overnight stay not confirmed."

Never infer that the driver slept.

------------------------------------------------------------------------

# 89. Overnight Morning Resume

The morning restart should be treated as a meaningful event.

``` text
OVERNIGHT_REST
       ↓
MOVEMENT DETECTED
       ↓
RESTART CONFIRMATION
       ↓
MORNING CHECKPOINT
```

Checkpoint:

``` text
Good morning ☀️

Ready to continue?

Quick check:

💧 Water
🍛 Food
🚻 Toilet
☕ Tea/Coffee
😴 Rested

[START JOURNEY]
```

This is particularly useful for a trip spanning two days.

------------------------------------------------------------------------

# 90. Long-Stop Escalation

Use progressive prompts.

### 30--60 min

No special question.

### 60--120 min

Possible long break.

### \>2 hours

Long-stop state.

### Overnight candidate

If the stop crosses a configurable night window or remains stationary
for several hours:

``` text
OVERNIGHT_CANDIDATE
```

### Next movement

Ask driver to classify.

Do not notify family that the driver is sleeping unless the driver
explicitly confirms it.

------------------------------------------------------------------------

# 91. Trip-Day Boundary

A 1,100 km journey may span:

``` text
Day 1
Day 2
```

The system must NOT assume:

``` text
trip starts and ends on same calendar date
```

Trip state should support:

``` text
trip_day = 1
trip_day = 2
...
```

Viewer timeline should group events by journey day.

Example:

``` text
DAY 1 — Hyderabad → Salem

06:10 Start
...
23:42 Overnight stop

DAY 2 — Salem → Thrissur

06:13 Resume
...
14:48 Arrival
```

------------------------------------------------------------------------

# 92. Kerala Trip as the Acceptance Test

The first production-like field test should be the user's actual:

``` text
Hyderabad → Thrissur
~1,100 km
```

Test participants:

``` text
Driver:
User

Viewer:
Mother

Additional viewers:
Wife
Friend
```

All use the same trip credentials.

The test should not be treated as a synthetic demo.

It should validate the real-world architecture under:

-   highway driving
-   changing mobile networks
-   long-distance GPS
-   network gaps
-   battery constraints
-   long stops
-   food breaks
-   toilet breaks
-   hydration confirmations
-   overnight stop if required
-   multiple simultaneous viewers
-   arrival
-   trip credential revocation

------------------------------------------------------------------------

# 93. Kerala Test Instrumentation

During the test, expose a hidden/debug screen for the driver containing:

``` text
GPS:
✓

Accuracy:
8m

Network:
4G

Firebase:
CONNECTED

Last upload:
12 sec ago

Pending events:
3

Battery:
61%

Tracking service:
RUNNING

Location interval:
15 sec

Current state:
DRIVING
```

This debug mode should be disabled or protected in production.

------------------------------------------------------------------------

# 94. Kerala Test Success Metrics

The trip should be considered successful if:

### Live tracking

At least 95%+ of connected periods show viewer location freshness within
the configured target window.

### Reconnection

After a network outage, current location reaches the backend before
historical backlog synchronization.

### Stop detection

Major stops are detected without excessive false break prompts.

### Wellbeing

Food/water/toilet/rest records can be entered in seconds.

### Viewer

Mom can understand the journey without contacting the driver for routine
location/status information.

### Overnight

A \>2-hour stop can be correctly classified after restart.

### Security

After trip completion, old credentials no longer provide live access.

### Battery

The application remains practical for the full journey without abnormal
battery drain beyond an acceptable test threshold.

------------------------------------------------------------------------

# 95. Play Store Production Requirements

Because this app's core functionality depends on continuous location
while the driver is traveling, Android/Google Play compliance must be
designed into the application from the beginning.

Current Android guidance requires an appropriate foreground service
declaration for location tracking, including the `location`
foreground-service type and the associated foreground-service permission
requirements on recent Android versions.
citeturn0search1turn0search6

Android also restricts starting foreground services from the background,
so the app should start the tracking session from an explicit user
action while the app is visible --- for example:

``` text
User taps START TRIP
        ↓
Tracking foreground service starts
        ↓
Driver locks phone
        ↓
Trip tracking continues
```

Do not design the application around silently starting location tracking
from the background. citeturn0search3

Google Play currently treats background location as sensitive and
requires a strong core-functionality justification, prominent
disclosure, consent, privacy-policy support and related Play Console
declarations where applicable. This app has a strong core-use
justification --- active private trip tracking --- but the
implementation and Play submission must explicitly document it.
citeturn0search5turn0search10

The Play Store release should therefore include:

-   clear location disclosure
-   clear explanation that tracking occurs during an active trip
-   privacy policy
-   data safety declaration
-   foreground service declaration
-   minimum necessary permissions
-   explicit user initiation of each trip
-   tracking termination after trip completion

------------------------------------------------------------------------

# 96. Revised Recommended Stack

## Driver

``` text
Kotlin
Jetpack Compose
Fused Location Provider
Activity Recognition
Foreground Service
Room
WorkManager
Firebase SDK
```

## Backend / Cloud

``` text
Firebase Realtime Database
Firebase Authentication / secure trip access mechanism
Firebase Analytics only if genuinely required
Firebase Crashlytics optional
Firebase Cloud Messaging optional
```

Avoid a custom backend for MVP unless a Firebase security/credential
requirement makes it necessary.

## Viewer

The viewer can initially be the **same Android app**.

At launch:

``` text
START NEW TRIP
JOIN EXISTING TRIP
```

This directly matches the user's Kerala test:

``` text
User's phone:
START NEW TRIP

Mom's phone:
JOIN EXISTING TRIP

Wife's phone:
JOIN EXISTING TRIP

Friend's phone:
JOIN EXISTING TRIP
```

A web viewer can be added later.

------------------------------------------------------------------------

# 97. Same-App Architecture

This is preferable for the first release.

``` text
                TripPulse Android App
                       │
          ┌────────────┴────────────┐
          │                         │
     DRIVER MODE                VIEWER MODE
          │                         │
  Create / Start Trip          Enter Credentials
          │                         │
  GPS + Sensors                Live Subscription
          │                         │
  Local Event Store            Live Dashboard
          │                         │
          └──────────┬──────────────┘
                     │
              Firebase RTDB
```

Benefits:

-   one APK
-   one codebase
-   easier testing
-   same UI language
-   simpler Play Store release
-   mom doesn't need a separate monitoring application

------------------------------------------------------------------------

# 98. Production Launch Strategy

### Release 0.1

Private APK/internal testing.

### Release 0.2

Kerala field test.

### Release 0.3

Fix:

-   battery
-   GPS
-   network reconnection
-   stop detection
-   checkpoint UX
-   Firebase security
-   Play policy issues

### Release 1.0

Google Play Store.

Core promise:

> **Private live journey sharing with intelligent break and wellbeing
> tracking.**

Do not market the app as a medical safety system.

------------------------------------------------------------------------

# 99. Revised MVP Priority

The order is now:

``` text
P0 — MUST WORK
────────────────────────────
Live location
Realtime viewer
Offline local tracking
Immediate reconnect
Trip credentials
Multiple viewers
ETA
Trip state
Trip completion
Credential expiration

P1 — MUST WORK FOR KERALA TEST
────────────────────────────
Stop detection
Restart detection
Water
Food
Toilet
Rest
Long stop >2h
Overnight stay
Morning resume
Timeline

P2 — AFTER FIELD TEST
────────────────────────────
Route deviation
Fuel/charge
Trip replay
Smart stop classification
Advanced notifications
Web viewer
AI summaries

P3 — FUTURE
────────────────────────────
SMS/satellite fallback
Journey learning
Personalized recommendations
Advanced AI copilot
```

------------------------------------------------------------------------

# 100. Most Important Design Principle

The application should optimize for the following chain:

``` text
                 DRIVER
                   │
                   ▼
            REAL WORLD EVENT
                   │
                   ▼
             PHONE SENSOR
                   │
                   ▼
            LOCAL EVENT STORE
                   │
          ┌────────┴────────┐
          │                 │
      INTERNET YES      INTERNET NO
          │                 │
          ▼                 ▼
      LIVE UPDATE       LOCAL STATE
          │                 │
          ▼                 │
       VIEWERS             │
          ▲                 │
          │                 │
          └──── RECONNECT ──┘
                   │
                   ▼
            CURRENT STATE FIRST
                   │
                   ▼
             HISTORY SECOND
```

The fundamental product promise is therefore:

> **If the driver's phone has connectivity, the family sees the journey
> live. If connectivity disappears, the driver's journey continues
> locally and the family sees exactly when the last confirmed update was
> received. The instant connectivity returns, the current position is
> pushed first, followed by the missing history.**

That is the most honest and technically robust way to achieve the "live
whenever possible, close to live otherwise" requirement.

------------------------------------------------------------------------

# 74. CRITICAL ARCHITECTURAL REVISION --- Offline Breaks Must Never Be Lost

This requirement is fundamental:

> **A network outage must affect when the server receives an event, but
> must never affect whether the event exists.**

A break taken in a network-dead area must be recorded exactly like a
connected break.

Example:

``` text
14:10  Vehicle stops
14:11  GPS confirms stationary
14:25  Driver selects FOOD + TOILET
14:27  Vehicle resumes
       ↓
No internet for the entire stop
       ↓
15:03  Network returns
       ↓
Server receives:
       - STOP_STARTED 14:10
       - BREAK_CHECKPOINT 14:25
       - FOOD_CONFIRMED 14:25
       - TOILET_CONFIRMED 14:25
       - STOP_ENDED 14:27
```

The viewer must then see the complete historical truth.

The implementation must therefore use:

``` text
LOCAL EVENT LOG
      +
CURRENT STATE SNAPSHOT
      +
SYNC QUEUE
```

rather than depending on the network to create events.

------------------------------------------------------------------------

# 75. Two-Lane Synchronization Architecture

The application needs two synchronization lanes.

## Lane A --- Live State

Highest priority.

``` text
Current GPS
Current trip state
Current ETA
Current connectivity
Current battery
Current driving/stop state
```

This lane exists to make the viewer as close to live as technically
possible.

## Lane B --- Historical Event Queue

Reliable priority.

``` text
Stop events
Break events
Food
Water
Toilet
Rest
Fuel
Charging
Route deviation
Manual corrections
```

Every event remains in the local queue until the backend acknowledges
it.

Therefore:

``` text
NETWORK AVAILABLE
      ↓
Live state → immediately
Historical events → immediately/batched

NETWORK UNAVAILABLE
      ↓
Live state → local
Historical events → local
      ↓
NETWORK RETURNS
      ↓
Current state → immediately
Then historical backlog → ordered sync
```

The system must NEVER discard an event merely because it could not
upload it.

------------------------------------------------------------------------

# 76. Offline Break Timeline Example

Assume the driver is travelling through an area with no network.

``` text
12:02  Driving
12:15  Vehicle stops
12:15  STOP_STARTED stored locally

12:27  Driver taps:
       ☑ Food
       ☑ Water
       ☑ Toilet

12:27  BREAK_CHECKPOINT stored locally

12:41  Vehicle resumes
12:41  STOP_ENDED stored locally

12:42  Driver continues driving
```

At this point the viewer may only know:

``` text
Last server update: 12:02
```

That is acceptable.

The viewer MUST NOT see fabricated live information.

When connectivity returns:

``` text
12:42  Current position uploaded first
12:42  Current trip state uploaded
12:42  ETA recalculated
12:42  Offline historical events begin syncing
12:43  Viewer receives:
       Food + Water + Toilet break at 12:27
```

The timeline should preserve the original timestamps.

------------------------------------------------------------------------

# 77. Viewer Data Freshness Model

The viewer must distinguish four states:

## LIVE

``` text
Last update < configurable live threshold
```

Example:

``` text
🟢 LIVE
Updated 18 seconds ago
```

## RECENT

Data is older than the live threshold but still reasonably current.

``` text
🟡 RECENT
Updated 2 min ago
```

## STALE

``` text
🟠 STALE
Last confirmed location 12 min ago
```

## OFFLINE

The backend has explicitly received that the driver device is offline,
or no connectivity heartbeat has been received for a sufficiently long
period.

``` text
🔴 OFFLINE
Last confirmed update 31 min ago
```

Never display stale coordinates with a "LIVE" label.

------------------------------------------------------------------------

# 78. Historical Event Reconciliation

When offline events arrive, the viewer should not simply append them to
"now".

The server must insert them into the correct chronological position.

Example:

``` text
14:02  Driving
14:15  Stop
14:22  Food + Toilet
14:39  Resumed
15:01  Current location received
```

Even though the server receives the 14:22 event at 15:01, the viewer
timeline remains:

``` text
14:02  Driving
14:15  Stop
14:22  🍛 Food + 🚻 Toilet
14:39  Resumed
15:01  Current location
```

Every event needs:

``` text
event_time
received_at
```

These must never be confused.

------------------------------------------------------------------------

# 79. Offline State Machine

The state machine must continue operating locally.

``` text
DRIVING
   ↓
POSSIBLE_STOP
   ↓
STOPPED
   ↓
BREAK_CHECKPOINT
   ↓
BREAK_RECORDED
   ↓
RESTART_DETECTED
   ↓
DRIVING
```

Network availability is NOT a state that blocks the journey state
machine.

It is an orthogonal condition:

``` text
Journey State:
DRIVING / STOPPED / BREAK / ARRIVED

Connectivity State:
ONLINE / DEGRADED / OFFLINE
```

This separation is mandatory.

------------------------------------------------------------------------

# 80. Offline Checkpoint UX

If there is no internet, the driver must still get exactly the same
checkpoint.

The screen may say:

``` text
Break completed?

☑ Water
☑ Food
☑ Toilet
☐ Rest

[ DONE ]

Saved on this phone.
It will sync automatically when network returns.
```

Do not prevent the driver from continuing because synchronization
failed.

------------------------------------------------------------------------

# 81. Persistent Local Event Store

Use an append-oriented local database.

Recommended:

**Room + SQLite**

Tables:

``` text
trip_local
event_queue
location_buffer
current_trip_state
sync_state
break_records
```

Every event should have:

``` text
local_id
client_event_id
trip_id
event_type
event_time
payload
sync_status
retry_count
last_attempt_at
```

Possible sync states:

``` text
PENDING
UPLOADING
ACKNOWLEDGED
FAILED_RETRYABLE
FAILED_PERMANENT
```

A crash must not lose pending events.

------------------------------------------------------------------------

# 82. Crash Recovery

The app must survive:

-   process termination
-   phone restart
-   network transition
-   GPS provider restart
-   Android service restart
-   temporary storage failure

On application/service restart:

``` text
Load active trip
       ↓
Load current local state
       ↓
Load unsynced events
       ↓
Resume tracking
       ↓
Resume synchronization
```

If the device reboots during an active trip, the app should recover the
active trip state and restart tracking according to Android's supported
background/foreground execution model.

------------------------------------------------------------------------

# 83. The ETA Model Must Be More Than Google Maps

This is another critical product differentiator.

A normal navigation ETA generally answers:

> "How long will it take to travel the remaining route under current
> road conditions?"

TripPulse must answer:

> **"What is the realistic time at which this driver is likely to reach
> the destination, including the driving conditions AND the breaks that
> this particular journey is likely to require?"**

Therefore:

``` text
Realistic ETA
=
Navigation travel-time estimate
+
Expected future break time
+
Expected fuel/charging time
+
Expected meal/refreshment time
+
Expected rest time
+
Current uncertainty buffer
```

------------------------------------------------------------------------

# 84. ETA Must Use a Break Budget

At every point in the trip, calculate:

``` text
remaining_distance
remaining_drive_time
expected_break_count
expected_break_duration
expected_refuel_duration
expected_meal_duration
expected_rest_duration
uncertainty_buffer
```

Then:

``` text
REALISTIC_ETA =
NOW
+
remaining_drive_time
+
future_break_budget
+
uncertainty_buffer
```

The system should NOT blindly add exactly one hour.

It should calculate a dynamic budget.

However, the product should enforce a **minimum realism buffer** for
long trips.

------------------------------------------------------------------------

# 85. Minimum Future Break Buffer

For a long-distance journey, TripPulse should never present a
suspiciously optimistic ETA based solely on uninterrupted driving.

Recommended product rule:

``` text
If remaining journey is long enough to require meaningful breaks:

minimum_future_break_budget >= configurable minimum
```

Initial default:

``` text
minimum buffer = 60 minutes
```

This is a product-planning default, not a medical rule.

If the journey model predicts that the driver realistically needs more:

``` text
calculated buffer > minimum buffer
```

use the calculated value.

Example:

``` text
Navigation ETA:
10:10 PM

Expected future breaks:
1 × 20 min
1 × 15 min
1 × 15 min

Expected fuel:
10 min

Uncertainty:
15 min

Realistic ETA:
11:15 PM
```

------------------------------------------------------------------------

# 86. Dynamic Break Budget

The remaining break budget should be recalculated after every meaningful
event.

Example:

``` text
At departure:
Future breaks = 90 min

After a 25-minute food/rest break:
Future breaks = 65 min

After another 15-minute toilet/water break:
Future breaks = 50 min
```

If the driver takes longer than expected:

``` text
Actual stop = 42 min
Expected stop = 20 min

ETA automatically shifts accordingly.
```

If the driver takes fewer breaks than expected:

``` text
Expected future break budget decreases
```

but the system should not assume the driver will indefinitely continue
without rest.

------------------------------------------------------------------------

# 87. Break Planning Model

Use configurable break classes.

Example:

``` text
SHORT_REFRESHMENT
  10–15 min

TOILET_WATER
  10–15 min

MEAL
  25–40 min

REST
  20–45+ min

FUEL
  10–20 min

CHARGING
  provider/vehicle dependent
```

These are planning defaults and should be configurable.

Actual historical stop duration should influence future predictions.

------------------------------------------------------------------------

# 88. Personalized Break Prediction

After sufficient trip history, the engine can learn:

``` text
driver's average break duration
driver's typical meal duration
driver's typical fuel-stop duration
driver's typical number of breaks
driver's preferred stopping pattern
```

For the first trip, use conservative defaults.

After repeated trips:

``` text
Default model
     ↓
Observed behaviour
     ↓
Personalized model
```

Never reduce the safety/reality buffer simply to produce an attractive
ETA.

------------------------------------------------------------------------

# 89. ETA Should Be a Range, Not a Fake Exact Time

The UI should support:

``` text
Likely arrival
10:45–11:15 PM
```

with:

``` text
Most likely: 11:00 PM
```

Example:

``` text
🚗 6h 05m driving
☕ 35m refreshment
🍛 30m food
🚻 15m bio breaks
⛽ 15m fuel
🛌 30m rest
🟡 25m uncertainty

Estimated arrival:
10:45–11:20 PM
```

This gives the family a much more emotionally useful expectation than an
artificially precise navigation ETA.

------------------------------------------------------------------------

# 90. ETA Explanation

The viewer should be able to tap ETA and see:

``` text
Why is the ETA 11:05 PM?

Road travel              6h 05m
Expected refreshment       25m
Expected food              30m
Expected bio breaks        15m
Expected rest              30m
Fuel                       15m
Traffic / uncertainty      25m
--------------------------------
Estimated remaining        7h 25m
```

This makes the system transparent.

------------------------------------------------------------------------

# 91. ETA Recalculation Events

Recalculate whenever:

``` text
location changes materially
route changes
traffic estimate changes
stop begins
stop ends
break is recorded
fuel stop is recorded
food is recorded
rest is recorded
destination changes
long stop occurs
overnight stop occurs
network returns
significant route deviation occurs
```

------------------------------------------------------------------------

# 92. Overnight / Hotel Detection

A stop exceeding two hours must trigger a special journey state.

Example:

``` text
STOP_STARTED
       ↓
30 min
       ↓
60 min
       ↓
120 min
       ↓
OVERNIGHT_CANDIDATE
```

The app should ask:

``` text
You've been stopped for over 2 hours.

Are you staying overnight?

🏨 Hotel
🏠 Home / Family
🛏 Other accommodation
🚗 No, continuing shortly
```

This question should appear only when the driver is stationary.

------------------------------------------------------------------------

# 93. Overnight Mode

If the driver confirms overnight:

``` text
OVERNIGHT_REST
```

The trip remains active.

Tracking becomes low-frequency to save battery.

Viewer sees:

``` text
🌙 OVERNIGHT REST

Stopped:
11:42 PM

Location:
Near Salem

Status:
Staying overnight

Trip resumes:
Not yet known

Last update:
2 min ago
```

The system should not imply sleep quality or duration.

------------------------------------------------------------------------

# 94. Next-Morning Resume

When movement resumes:

``` text
OVERNIGHT_REST
       ↓
MOVEMENT DETECTED
       ↓
TRIP RESUMED
```

The app can ask:

``` text
Good morning 👋

Before you continue:

☑ Water
☑ Food
☑ Toilet
☑ Rest already taken

[Continue]
```

This remains optional and must be extremely low friction.

------------------------------------------------------------------------

# 95. Unexpected Long Stop

If the driver does NOT confirm overnight:

``` text
Long stop continues
```

The viewer sees:

``` text
🟠 Long stop
Stopped for 2h 14m
```

not:

> Driver is sleeping.

If the driver later resumes:

``` text
Long stop ended
```

and the driver can retrospectively classify it.

------------------------------------------------------------------------

# 96. Overnight ETA

If overnight is confirmed, ETA must immediately switch models.

Example:

``` text
Original ETA:
10:30 PM

Driver confirms overnight:
Trip paused overnight.

Expected restart:
06:30–08:00 AM

New arrival estimate:
Tomorrow 05:30–06:15 PM
```

If no restart time is supplied, use:

``` text
ETA:
Pending morning departure
```

rather than pretending to know.

------------------------------------------------------------------------

# 97. Journey-Level Time Budget

The trip should maintain two concepts:

## Physical route time

``` text
navigation_travel_time
```

## Human journey time

``` text
navigation_travel_time
+
break_budget
+
rest_budget
+
fuel_budget
+
uncertainty
```

The second one is what the family primarily needs.

------------------------------------------------------------------------

# 98. Driver-Specific Journey Forecast

As the trip progresses:

``` text
PLANNED
   ↓
FORECAST
   ↓
OBSERVED
   ↓
UPDATED FORECAST
```

Example:

``` text
At 08:00
Expected arrival: 20:30

At 12:00
Actual breaks longer than expected
Expected arrival: 21:05

At 16:00
Traffic improves + short stops
Expected arrival: 20:50

At 19:00
Final forecast: 20:55–21:15
```

This is much more useful than repeatedly showing a raw navigation ETA.

------------------------------------------------------------------------

# 99. Never Lose Offline Break Semantics

The server must preserve:

``` text
event_time
location_at_event
event_type
driver_confirmation
source
```

Example:

``` json
{
  "event_type": "FOOD_REPORTED",
  "event_time": "2026-08-11T14:25:00+05:30",
  "received_at": "2026-08-11T15:03:12+05:30",
  "source": "DRIVER_CONFIRMATION",
  "sync_delay_seconds": 2292
}
```

The viewer can then understand:

> Food was taken at 2:25 PM, and the phone uploaded the information at
> 3:03 PM because the driver was offline.

This is critical for trust.

------------------------------------------------------------------------

# 100. Backend Recommendation for the Finished Product

For the first production-grade version, use:

## Firebase Realtime Database

for:

-   live trip state
-   live location
-   viewer synchronization

## Firebase Authentication / anonymous or custom authentication

for:

-   secure sessions

## Cloud Functions / server-side logic

for:

-   trip expiry
-   notification processing
-   cleanup
-   server validation

## Firebase Crashlytics

for:

-   Android crash monitoring

## Firebase Analytics

Only for privacy-safe product analytics.

## Local Room/SQLite

for:

-   offline event log
-   unsynchronized events
-   trip state

This creates:

``` text
                DRIVER
                  │
          ┌───────▼────────┐
          │ Android App    │
          │                │
          │ GPS            │
          │ Sensors        │
          │ State Machine  │
          │ Room DB        │
          └───────┬────────┘
                  │
          ┌───────▼────────┐
          │ Sync Engine    │
          └───────┬────────┘
                  │
             INTERNET
                  │
          ┌───────▼────────────┐
          │ Firebase RTDB      │
          │ Live State         │
          │ Event Store        │
          └───────┬────────────┘
                  │
       ┌──────────┼───────────┐
       │          │           │
     MOM        WIFE       FRIEND
       │          │           │
       └──────────┼───────────┘
                  │
            Same Trip ID
            + Secret
```

The database should be selected based on **realtime capability, offline
support, simplicity and zero/near-zero initial infrastructure cost**,
rather than introducing a self-hosted PostgreSQL stack before the
product proves itself.

------------------------------------------------------------------------

# 101. Multiple Viewers

There is no reason to limit the trip to one viewer.

The trip is the authorization boundary.

``` text
Trip ID
   +
Trip secret
   ↓
Read-only viewer session
```

Mom, wife and friend can all connect simultaneously.

All receive the same canonical trip state.

The system should NOT create separate copies of the trip for each
viewer.

------------------------------------------------------------------------

# 102. Viewer Synchronization

When a viewer opens an active trip:

``` text
Authenticate
    ↓
Receive current state immediately
    ↓
Receive latest location
    ↓
Receive current ETA
    ↓
Receive recent timeline
    ↓
Subscribe to live updates
```

The viewer should not need to wait for the next GPS event to populate
the screen.

------------------------------------------------------------------------

# 103. Canonical Trip State

The backend should maintain a compact current-state object:

``` json
{
  "status": "DRIVING",
  "latitude": 10.1234,
  "longitude": 76.1234,
  "speed": 72,
  "last_location_at": "...",
  "last_break_at": "...",
  "last_water_at": "...",
  "last_food_at": "...",
  "last_toilet_at": "...",
  "last_rest_at": "...",
  "eta_low": "...",
  "eta_high": "...",
  "connectivity": "ONLINE",
  "battery_percent": 62,
  "distance_remaining_km": 341
}
```

This makes opening the viewer fast.

The event history remains separate.

------------------------------------------------------------------------

# 104. Event Log + State Snapshot Pattern

Use both.

``` text
EVENT LOG
---------
Immutable historical truth

CURRENT STATE
-------------
Fast current representation
```

When an event is processed:

``` text
append event
      +
update current state
```

This gives:

-   auditability
-   replayability
-   fast viewer startup
-   offline reconciliation
-   robust recovery

------------------------------------------------------------------------

# 105. Finished-Product Quality Gate

The Kerala trip must NOT be the first time the entire architecture is
tested.

Before the 1,100 km journey, the product should pass:

## Stage 1 --- Simulation

Simulate:

-   driving
-   stops
-   breaks
-   network loss
-   network return
-   GPS gaps
-   long stops
-   overnight
-   battery reduction

## Stage 2 --- Local road tests

Perform:

``` text
5 km
20 km
50 km
100 km
```

with multiple viewers.

## Stage 3 --- Network failure tests

Physically disable data during:

-   driving
-   stopping
-   food break
-   toilet break
-   restart

Then verify all events synchronize correctly.

## Stage 4 --- Overnight test

Perform a simulated \>2-hour stop and next-morning resume.

## Stage 5 --- Full dress rehearsal

Run a long continuous test with:

-   driver phone
-   Mom's phone
-   wife's phone
-   friend's phone

using the exact production build.

Only after these pass should the Kerala trip become the final field
validation.

------------------------------------------------------------------------

# 106. Enterprise-Level Definition of Done

The application is NOT considered complete merely because:

``` text
GPS works
```

It is complete only when all of the following work reliably:

``` text
✓ Trip creation
✓ Secure trip credentials
✓ Multiple simultaneous viewers
✓ Near-live location
✓ Current-state synchronization
✓ Offline GPS capture
✓ Offline stop detection
✓ Offline break confirmation
✓ Offline food confirmation
✓ Offline toilet confirmation
✓ Offline water confirmation
✓ Offline rest confirmation
✓ Historical synchronization
✓ Event ordering
✓ Duplicate prevention
✓ Crash recovery
✓ App restart recovery
✓ Network recovery
✓ GPS degradation handling
✓ Battery-aware tracking
✓ Long-stop detection
✓ Overnight mode
✓ Morning resume
✓ Dynamic ETA
✓ Future break budget
✓ Minimum ETA realism buffer
✓ Route deviation handling
✓ Stale-location indicators
✓ Arrival detection
✓ Trip completion
✓ Credential expiry
✓ Viewer read-only security
✓ Error monitoring
✓ Play Store compliance
✓ Privacy disclosure
✓ Production logging/observability
✓ Real-device testing
```

------------------------------------------------------------------------

# 107. The Most Important Product Contract

TripPulse should make this promise:

> **If the driver has recorded an event on the phone, the system will
> eventually preserve and deliver that event to authorized viewers, even
> if the internet was unavailable when it happened.**

And separately:

> **If the driver is currently connected, authorized viewers should see
> the journey as close to live as Android, GPS, network and battery
> conditions allow.**

And for ETA:

> **The displayed arrival estimate represents a realistic human journey,
> not merely uninterrupted road travel.**

Those three principles should be treated as non-negotiable architecture
requirements.

------------------------------------------------------------------------

# 108. Final Kerala Trip Scenario

For the actual Hyderabad → Thrissur journey:

``` text
START
  ↓
Trip created
  ↓
Mom/Wife/Friend join
  ↓
Driver begins
  ↓
Near-live tracking
  ↓
Network gap
  ↓
Local tracking continues
  ↓
Stop
  ↓
Food + Toilet recorded offline
  ↓
Resume
  ↓
Network returns
  ↓
Current position synced immediately
  ↓
Offline food/toilet event synced
  ↓
Timeline corrected chronologically
  ↓
ETA recalculated
  ↓
Future break budget recalculated
  ↓
Long driving segment
  ↓
Break
  ↓
Water + Rest
  ↓
Continue
  ↓
Possible >2h stop
  ↓
Overnight question
  ↓
Hotel confirmed
  ↓
Overnight mode
  ↓
Morning movement
  ↓
Trip resumed
  ↓
ETA rebuilt
  ↓
Final journey
  ↓
Arrival
  ↓
Trip completed
  ↓
Live credentials revoked
```

The Kerala trip then becomes both the real journey and the final
end-to-end validation of the product.

------------------------------------------------------------------------

# 109. Revised Implementation Priority

Before adding any AI features, the implementation priority is:

``` text
P0
-----
Local event reliability
Realtime state
Offline synchronization
GPS tracking
Stop detection
Viewer synchronization
Security
Crash recovery

P1
-----
Break checkpoint
Food / water / toilet / rest
Long-stop detection
Overnight mode
Dynamic ETA
Break budget

P2
-----
Route deviation
Notifications
Trip replay
Analytics
Personalized break prediction

P3
-----
AI journey summaries
AI copilot
Predictive stop recommendations
Advanced journey intelligence
```

**Do not move to P2/P3 until P0 and P1 have been proven through
automated tests and real-device network-failure tests.**

------------------------------------------------------------------------

# 110. Quick Driver Notes & Journey Context

TripPulse should provide a **Quick Notes** feature for information that
cannot reliably be inferred from GPS or sensors.

This is important because journey context can become valuable during:

-   unexpected delays
-   accidents
-   medical situations
-   group travel
-   passenger changes
-   route changes
-   family coordination
-   emergency investigation
-   later trip reconstruction

The driver should be able to open the app when safely stationary and
enter a short message.

Examples:

``` text
"Rahul joined me near Kurnool."

"Picked up Mom from the bus stop."

"Took my prescribed medicine."

"Tyre issue — stopping for repair."

"Minor accident. I am okay."

"Taking a longer lunch break."

"Passenger changed."
```

------------------------------------------------------------------------

# 111. Quick Note UX

The driver dashboard should have a prominent but non-distracting:

``` text
[ + Quick Note ]
```

When stationary:

``` text
Quick Note

What would you like to record?

[ __________________________ ]

Recent quick actions:

👤 Passenger joined
💊 Medicine
🚗 Vehicle issue
🍴 Extended break
🛣 Route change
⚠ Incident

[ SAVE NOTE ]
```

The free-text field should support short messages.

The product should discourage typing while the vehicle is moving.

If movement is detected, the note composer should be disabled or
automatically minimized.

------------------------------------------------------------------------

# 112. Structured Quick Notes

Free text is useful, but structured categories make the information more
searchable and useful.

Recommended categories:

``` text
PASSENGER_JOINED
PASSENGER_LEFT
MEDICINE
VEHICLE_ISSUE
ACCIDENT
ROUTE_CHANGE
EXTENDED_BREAK
FUEL
CHARGING
FOOD
ACCOMMODATION
MEETING
OTHER
```

A structured note can contain:

``` json
{
  "event_type": "PASSENGER_JOINED",
  "text": "Rahul joined me near Kurnool",
  "event_time": "...",
  "latitude": "...",
  "longitude": "...",
  "source": "DRIVER_MANUAL"
}
```

------------------------------------------------------------------------

# 113. Passenger / Group Travel Context

A particularly important use case is group travel.

The driver should be able to record:

``` text
Passenger joined
Passenger left
Passenger changed vehicle
Additional person travelling
Child joined
Group member joined
```

The application should NOT require collecting unnecessary personal
information.

A simple note such as:

> "Anil joined me near Kurnool."

is sufficient.

The event is then part of the trip timeline.

Viewer:

``` text
10:42 AM
👤 Passenger joined
"Anil joined me near Kurnool."
```

This information can become important in case of an incident because the
trip timeline preserves who the driver reported as travelling with them.

------------------------------------------------------------------------

# 114. Medicine / Personal Context Notes

The driver can record:

``` text
💊 Medicine
```

with an optional note.

Example:

``` text
Medicine taken

Optional note:
"Regular medication"

[Save]
```

The application should treat this as **private personal information**.

Recommended privacy behaviour:

-   Store it as a sensitive event.
-   Encrypt it locally where practical.
-   Do not expose it to every viewer by default.
-   Provide a trip-level setting for whether sensitive notes are shared.
-   Never use it to make medical diagnoses.
-   Never infer that a medicine was taken from GPS or sensors.
-   Preserve the driver's exact wording if shared.

Viewer could see, depending on permissions:

``` text
💊 Medicine recorded
10:15 AM
```

without necessarily exposing the medication name.

A future privacy control can allow:

``` text
Share:
☑ General note
☐ Sensitive details
```

------------------------------------------------------------------------

# 115. Incident / Accident Reporting

TripPulse should provide a dedicated **SOS / Incident** control rather
than relying only on a text note.

The driver dashboard should contain:

``` text
                 [ SOS ]
```

It should be deliberately separated from normal controls to reduce
accidental activation.

Recommended interaction:

``` text
Press and hold for 2 seconds
          ↓
SOS ACTIVATED
          ↓
Current location captured
          ↓
Incident event created
          ↓
Emergency notification sent
          ↓
Live location priority mode
```

A long press is preferable to a single tap to reduce accidental
activation.

------------------------------------------------------------------------

# 116. SOS Event

When SOS is activated, create an immutable event:

``` text
SOS_ACTIVATED
```

with:

``` text
timestamp
latitude
longitude
GPS accuracy
speed
bearing
current trip state
battery
network state
last known location
destination
ETA
```

Example:

``` json
{
  "event_type": "SOS_ACTIVATED",
  "event_time": "...",
  "latitude": 10.1234,
  "longitude": 76.1234,
  "accuracy": 7.2,
  "speed": 0,
  "trip_state": "STOPPED",
  "battery_percent": 46,
  "connectivity": "ONLINE"
}
```

------------------------------------------------------------------------

# 117. Automatic SOS Message

Immediately after SOS activation, authorized viewers should receive a
high-priority notification.

Example:

> 🚨 SOS --- TripPulse\
> The driver has activated an SOS alert.\
> Last known location: near Salem.\
> Time: 6:42 PM.\
> Open TripPulse to view the live location.

The message should include a secure link/deep link to the authorized
trip view where appropriate.

Do not expose the location publicly.

------------------------------------------------------------------------

# 118. SOS Offline Behaviour

SOS must work even without internet.

If the driver is offline:

``` text
SOS pressed
    ↓
SOS event saved locally
    ↓
Current GPS captured
    ↓
Local SOS state activated
    ↓
Network unavailable
    ↓
Keep retrying
    ↓
Network returns
    ↓
SOS event transmitted with original timestamp
    ↓
High-priority viewer notification
```

The phone should show:

``` text
SOS saved.

No network is currently available.
The alert will be sent automatically when connectivity returns.
```

Do not falsely display:

> "Emergency alert sent"

until the server acknowledges receipt.

------------------------------------------------------------------------

# 119. SOS Priority Synchronization

During SOS, synchronization should change priority.

Normal:

``` text
CURRENT STATE
    ↓
IMPORTANT EVENTS
    ↓
HISTORICAL BACKLOG
```

SOS:

``` text
SOS EVENT
    ↓
CURRENT GPS
    ↓
CURRENT TRIP STATE
    ↓
LATEST LOCATIONS
    ↓
OTHER EVENTS
```

If the network is available, location updates can temporarily become
more frequent subject to battery and Android platform constraints.

------------------------------------------------------------------------

# 120. SOS Viewer Experience

The viewer should immediately see:

``` text
╔════════════════════════════════════╗
║ 🚨 SOS ACTIVATED                   ║
║                                    ║
║ Time: 6:42 PM                      ║
║ Location: Near Salem               ║
║                                    ║
║ ● LIVE                             ║
║                                    ║
║ [ OPEN LIVE LOCATION ]             ║
╚════════════════════════════════════╝
```

The trip map should switch into an incident-focused mode.

Display:

``` text
Last known speed
Last known location
Location accuracy
Time of SOS
Battery
Network state
Current journey state
```

Do not overwhelm the viewer with unrelated information during an SOS.

------------------------------------------------------------------------

# 121. SOS Follow-Up

Once the driver is safely stationary and able to interact, show:

``` text
SOS is active.

Are you okay?

[ I'm Safe — Cancel SOS ]

[ Continue SOS ]
```

Cancellation should require deliberate confirmation.

If the driver cancels:

``` text
SOS_RESOLVED
```

The timeline should preserve both events:

``` text
18:42  🚨 SOS activated
18:48  ✅ SOS resolved
```

Never delete the original SOS event.

------------------------------------------------------------------------

# 122. Accident Auto-Detection --- Future Capability

A future version can optionally detect a **possible severe incident**
using signals such as:

-   abrupt deceleration
-   unusual accelerometer spike
-   sudden speed change
-   device orientation change
-   vehicle Bluetooth disconnect
-   subsequent stationary state

However:

> **Automatic accident detection must initially be treated as a possible
> incident, not a confirmed accident.**

Example:

``` text
POSSIBLE_INCIDENT
      ↓
Vehicle suddenly stopped
      ↓
High acceleration/deceleration event
      ↓
Driver phone stationary
      ↓
Safe checkpoint available
```

The app could ask:

``` text
Are you okay?

[ I'm Safe ]
[ Activate SOS ]
```

This should only be implemented after extensive false-positive testing.

------------------------------------------------------------------------

# 123. Do Not Automatically Claim an Accident

A pothole, hard braking event, phone drop or emergency braking can
resemble an accident to sensors.

Therefore:

``` text
Sensor event ≠ confirmed accident
```

Correct terminology:

``` text
Possible incident detected
```

until the driver or an authorized workflow confirms it.

------------------------------------------------------------------------

# 124. Incident Timeline

The journey timeline should support:

``` text
18:40  Driving
18:42  ⚠ Possible incident detected
18:42  🚨 SOS activated
18:42  Location captured
18:48  Driver marked safe
18:55  Journey resumed
```

This becomes valuable for later reconstruction.

------------------------------------------------------------------------

# 125. Emergency Contacts

During trip creation, optionally configure:

``` text
Emergency contacts
```

The driver can choose from contacts or enter them manually according to
Android permissions and platform constraints.

Important:

-   Emergency contacts are different from ordinary viewers.
-   They should not automatically gain complete trip history.
-   SOS can notify selected emergency contacts.
-   The product must clearly state what information is shared.

Recommended initial model:

``` text
Trip viewers
    ↓
Live trip information

Emergency contacts
    ↓
SOS information + live incident location
```

------------------------------------------------------------------------

# 126. SOS Sharing Permissions

Trip creation should allow:

``` text
SOS contacts
-----------------
☑ Mom
☑ Wife
☐ Friend
```

and:

``` text
Trip viewers
-----------------
☑ Mom
☑ Wife
☑ Friend
```

This separates routine tracking from emergency escalation.

------------------------------------------------------------------------

# 127. Quick Notes in Offline Mode

Quick notes follow the same event architecture as all other events.

Example:

``` text
10:20
Passenger joined
```

No network.

Locally stored:

``` text
PASSENGER_JOINED
```

Network returns:

``` text
Current location
      ↓
Passenger note
      ↓
Historical events
```

Viewer timeline receives the note at its original event time.

------------------------------------------------------------------------

# 128. Notes With Location Context

Every quick note should optionally capture:

``` text
timestamp
latitude
longitude
accuracy
trip state
```

This turns:

> "Passenger joined"

into:

``` text
10:20 AM
Passenger joined

Location:
Near Kurnool

Trip state:
Stopped

GPS accuracy:
11m
```

This contextual information should be available to the driver and
authorized viewers.

------------------------------------------------------------------------

# 129. Notes With Automatic Context

The driver should not have to type obvious context.

If the driver presses:

``` text
👤 Passenger joined
```

the app automatically records:

``` text
Current time
Current location
Current trip state
```

The driver only needs to optionally add:

> "Rahul joined."

Similarly:

``` text
💊 Medicine
```

automatically captures time/location without requiring manual entry.

------------------------------------------------------------------------

# 130. Incident Data Retention

Normal trip notes can follow the trip retention policy.

SOS/incident events should have a stronger retention option because they
may be important later.

Recommended:

``` text
Normal note:
trip retention policy

SOS:
retain with trip record until explicit deletion/retention expiry
```

Never silently discard an SOS event when live credentials expire.

Viewer access can expire while the underlying incident record follows
the configured retention policy.

------------------------------------------------------------------------

# 131. Security for Notes and SOS

Notes can contain sensitive information.

Therefore:

``` text
Trip credentials
       ↓
Authorized viewer
       ↓
Trip data
       ↓
Sensitive-note permission
```

Do not put sensitive note content into:

-   public URLs
-   notification previews by default
-   analytics events
-   crash logs
-   application logs

Notifications should use minimal information.

For example:

> "A new private trip note was added."

rather than:

> "Driver took \[specific medication\]."

------------------------------------------------------------------------

# 132. Quick Notes as a Safety Context Layer

The long-term data model should treat manual context as another layer of
the journey:

``` text
SENSORS
  ↓
What the device observed

INFERENCE
  ↓
What the system calculated

DRIVER CONFIRMATION
  ↓
What the driver explicitly reported

QUICK NOTES
  ↓
Context the system cannot infer
```

This four-layer model is important.

Example:

``` text
Sensor:
Vehicle stopped

Inference:
Long stop

Driver confirmation:
Food + Toilet

Quick note:
"Met friend Ravi"

Result:
Complete journey context
```

------------------------------------------------------------------------

# 133. Revised Trip Timeline

The timeline should combine all event types:

``` text
06:20  🚗 Trip started

08:05  💧 Water

09:12  🚻 Toilet + 💧 Water

11:30  🍛 Food + 😴 Rest

12:15  👤 Passenger joined
       "Rahul joined near Kurnool"

14:10  ⛽ Fuel

16:45  💊 Medicine
       "Regular medication"

18:42  🚨 SOS activated
       Location captured

18:48  ✅ SOS resolved

22:15  🏨 Overnight stay confirmed

06:45  🌅 Trip resumed

17:30  🏁 Arrived
```

This is much richer than a location history.

------------------------------------------------------------------------

# 134. Updated Product Definition

TripPulse is now defined as:

> **A private, real-time, offline-resilient journey companion that
> records where the driver is, what happened during the journey,
> relevant wellbeing checkpoints, passenger/group context, incidents and
> emergency events, while minimizing driver interaction and providing
> authorized family/friends with a trustworthy view of the journey.**

The system should answer:

``` text
Where is the driver?
How long have they been driving?
When did they last stop?
Did they eat?
Did they drink water?
Did they take a toilet break?
Did they rest?
Who joined or left?
Did the driver add any important context?
Did something unusual happen?
Was an SOS activated?
Is the current location actually live?
If not, when was it last confirmed?
When are they realistically expected to arrive?
Is an overnight halt occurring?
```

------------------------------------------------------------------------

# 135. Final Critical Architecture Rule

All journey information must pass through the same durable event
architecture:

``` text
GPS
Sensors
Driver Checkpoints
Quick Notes
SOS
System Inferences
       │
       ▼
LOCAL EVENT LOG
       │
       ├── Current State
       │
       └── Sync Queue
                │
        ┌───────┴────────┐
        │                │
     ONLINE           OFFLINE
        │                │
        ▼                ▼
   Realtime sync    Durable local
        │             storage
        │                │
        └───────┬────────┘
                │
                ▼
          BACKEND EVENT LOG
                │
                ▼
          CURRENT STATE
                │
                ▼
       AUTHORIZED VIEWERS
```

No feature --- including notes, food, medicine, passenger changes,
incidents or SOS --- should bypass this architecture.

This guarantees that the application has **one consistent source of
journey truth**, whether an event originated from a sensor, the driver,
an inference engine, or an emergency action.
