# Architecture

TripPulse is built around one idea: **a single, durable event architecture is the source of journey truth**, whether an event came from a sensor, an inference, the driver, or an emergency action. Everything else — live viewing, timeline, ETA, replay, summary — is derived from that.

## Layers

```
ui/            Compose screens + navigation + view models (thin; no business logic)
di/            manual composition root (AppGraph) — no Hilt, explicit wiring
service/       foreground tracking service + boot/activity receivers
notifications/ channels + user-facing notifications
data/          Room DB, event codec, routing, sync engine, Supabase transport,
               TripManager (orchestrator), ViewerRepository
domain/        pure models, config, state machine, stop/eta/deviation/summary engines
core/          geo math + id/time helpers
```

The `domain/` layer holds no Android references and is fully unit-tested. `TripManager` is the orchestrator that turns location fixes, ticks and driver actions into events + state, and hands work to the sync engine under a single mutex so nothing races.

## Event log + current-state snapshot

Two representations, written together:

- **Event log** (`event_queue` / events node) — immutable, append-only historical truth. Every event carries `event_time` (when it happened) and `received_at` (when the server got it); these are never conflated, so an offline break shows at its real time even though it uploaded later.
- **Current-state snapshot** (`current_trip_state` / `currentState` node) — a compact object (position, journey status, ETA range, wellbeing timestamps, connectivity, battery) that makes opening a viewer instant without replaying history.

Every event also records **provenance** via `EventSource`:

```
SENSOR_OBSERVED  — what the device measured
SYSTEM_INFERRED  — what an engine calculated (e.g. LONG_STOP)
DRIVER_CONFIRMATION — what the driver explicitly reported (e.g. WATER)
DRIVER_MANUAL    — notes, SOS, manual corrections
SERVER_DERIVED   — e.g. SOS delivery acknowledgement
```

Inferred state is never presented as driver-confirmed. The viewer shows “Rest — confirmed 42 min ago”, not “rest detected”, only when the driver actually confirmed it.

## Two-lane synchronization

Connectivity affects *timing*, never *existence*. When offline, events and state persist locally and the journey continues; when connectivity returns, the sync engine drains in a deliberate order.

- **Lane A — live state:** highest priority, throttled overwrite of the `currentState` node so viewers are as close to live as conditions allow.
- **Lane B — historical backlog:** append-only events (and compacted locations), drained by priority (`SOS = 0`, other critical events = 1, raw locations = 2).

**Reconnect ordering** is the key freshness optimization: after an outage the current position + state + ETA go up *first*, so the viewer becomes live again immediately; only then does the missed history sync in the background. Location samples are the only thing ever compacted; events never are.

**Idempotency:** every event has a stable `client_event_id` and is written write-once (`!data.exists()`), matching the database rule. A retry that races an existing write is rejected with permission-denied, which the transport verifies and treats as an acknowledgement rather than an error. This makes unreliable-network retries safe.

`onDisconnect` flips the live node's `connectivity` to `OFFLINE`, so viewers can distinguish “driver device dropped” from “app closed cleanly”.

## Journey state machine (orthogonal to connectivity)

Journey status is an explicit state machine (`READY → DRIVING → POSSIBLE_STOP → STOPPED → LONG_STOP → OVERNIGHT → … → ARRIVED → COMPLETED`, plus `PAUSED`/`EXPIRED`). **Connectivity is deliberately not a state** — it's an orthogonal condition (`ONLINE / OFFLINE`). A network outage must never block the journey machine. Invalid transitions return null and are ignored rather than crashing.

## Stop detection (traffic-light-safe)

Speed alone is unreliable, so detection combines GPS speed, displacement from a stop anchor, and dwell time, with Activity Recognition as a corroborating hint (never required). A candidate stop only becomes a confirmed `STOPPED` after a multi-minute dwell (default 5 min), so traffic lights and jams don't generate break prompts. A confirmed stop crossing the long-stop threshold (default 2 h) becomes an overnight candidate; the driver classifies it on restart. The driver confirms *what happened* during a stop; the system only detects *that* it happened.

## Realistic ETA

A navigation ETA answers “how long is the remaining road”. TripPulse answers “when will this driver realistically arrive”:

```
realistic ETA = remaining travel time
              + future break budget (meals / bio / rest / fuel, by interval)
              + uncertainty buffer (max of a floor and a fraction of travel time)
```

Long trips enforce a **minimum break buffer** so an ETA is never suspiciously optimistic. Output is a **range** (`low ≤ likely ≤ high`) with an explainable breakdown, and confidence scales with the routing provider. An overnight halt with no declared restart yields **no invented time** (`OVERNIGHT_PENDING`) rather than a fake arrival.

## Viewer freshness

Every viewer session resolves to one of `LIVE / RECENT / STALE / OFFLINE / COMPLETED`, computed from the last-update age corrected by server-clock skew and the reported connectivity. A stale location is **never** labelled live.

## Security & privacy

- **Capability-scoped access:** the RTDB path is `SHA-256(tripId:secret)`. The secret is never stored server-side; there's no directory, no enumeration, no public URL.
- **Owner-writes, read-only viewers:** the driver's anonymous uid is stamped as `ownerUid`; rules require it for writes.
- **Auto-revocation:** reads are gated on `expiresAt > now`; on completion the trip is expired after a short grace, and Cloud Functions purge live state + raw location (event log retained per policy).
- **Sensitive data:** medicine/notes are stored locally, and sensitive events publish only a marker to the cloud (no content); notifications carry minimal text. Nothing sensitive goes into logs, analytics, or notification previews.

## Resilience

- **Crash / restart recovery:** on service or process restart, the active trip, current state and unsynced events are reloaded and tracking + sync resume. Pending events survive process death (durable Room queue with per-event sync status + retry count).
- **Boot restart:** if a trip is still active after reboot, tracking restarts when background-location allows; otherwise a resume notification is posted (Android forbids silently starting a location foreground service from the background).
- **Adaptive sampling:** GPS interval adapts to journey state and battery (frequent while driving, sparse while stationary/overnight, most frequent during SOS, backed off on low battery).
