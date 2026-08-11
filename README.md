# TripPulse

Private, trip-scoped live journey sharing with intelligent break/wellbeing tracking and realistic ETAs — built to be **live whenever there's connectivity, and honest about staleness when there isn't**.

A single Android app is both the **driver** app and the **viewer** app. The driver creates a trip, shares a `Trip ID` + secret password, and family/friends follow read-only. The journey is captured as a durable local event log first, then synced to Firebase; a network outage changes *when* the server receives an event, never *whether* it exists.

This repository is the working implementation of the product/engineering plan in `docs/spec/`.

---

## What's in the box

- **Android app** (`apps/android/`) — Kotlin, Jetpack Compose, single `:app` module, manual DI.
  - Foreground-service GPS tracking with adaptive sampling and Activity-Recognition corroboration.
  - Local-first **event log** (Room) + **current-state snapshot** + **two-lane sync** (live state first, historical backlog second).
  - Journey **state machine**, **stop detection** (traffic-light-safe), **break checkpoints**, **overnight** flow, **SOS** (offline-safe), **quick notes** (passenger/medicine, medicine treated as sensitive), **route deviation**, **trip replay**, **trip summary**.
  - Realistic **ETA engine**: route travel time + future break budget + uncertainty, presented as a *range* with an explainable breakdown.
  - **Freshness** model for viewers: `LIVE / RECENT / STALE / OFFLINE / COMPLETED` — a stale location is never shown as live.
- **Firebase** (`firebase/`) — Realtime Database security rules (capability-scoped, owner-writes, expiry-gated reads) + project config.
- **Cloud Functions** (`functions/`) — scheduled expiry cleanup + SOS/arrival push fan-out.
- **CI** (`.github/workflows/`) — builds the debug APK and runs unit tests on every push/PR.
- **Docs** (`docs/`) — the full spec plus setup/architecture/testing/release guides.

---

## Local mode vs cloud mode

The app runs immediately in **local mode** with no backend: full on-device tracking, stop detection, checkpoints, ETA, replay and summary all work; only *remote* viewer sharing is inactive. Drop in a Firebase config file and rebuild to activate cloud mode — no code changes.

| Capability | Local mode | Cloud mode |
|---|---|---|
| Driver tracking, stop/break detection, ETA, replay, summary | ✅ | ✅ |
| Remote viewers (Trip ID + password) | — | ✅ |
| Live state + historical backlog sync | — | ✅ |
| SOS / arrival push to viewers | — | ✅ |

See **`docs/FIREBASE_SETUP.md`** to enable cloud mode and **`docs/MAPS_SETUP.md`** to enable the live map.

---

## Build

Prerequisites: JDK 17, Android SDK (compileSdk 35, build-tools 35.0.0). The Gradle wrapper is committed.

```bash
cd apps/android

# Debug APK
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Unit tests
./gradlew :app:testDebugUnitTest
```

Install on a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Optional keys (never commit these)

- **Maps:** put `MAPS_API_KEY=...` in `apps/android/local.properties` (or export it as an env var). Without it, map panels show a graceful placeholder and everything else works.
- **Firebase:** place `google-services.json` at `apps/android/app/google-services.json`. Its presence switches the app to cloud mode automatically at build time.

---

## Continuous integration

`.github/workflows/android-build.yml` builds `:app:assembleDebug`, runs unit tests, and uploads the APK + test results as artifacts. It builds in local mode by default; set a `MAPS_API_KEY` repository secret to enable the map in CI builds. CI is the reproducible build path — it doesn't depend on a local machine's JDK setup.

---

## Testing

- **Unit tests** cover the pure logic: journey state machine, stop detection (traffic-light protection, genuine stop, restart, long stop), realistic ETA (range ordering, overnight pending, long-trip minimum buffer), credential/access-key derivation, route deviation, and summary computation. Run with `./gradlew :app:testDebugUnitTest`.
- **Real-device staged plan** (simulation → short drives → network-failure → overnight → dress rehearsal → the Hyderabad→Thrissur field test) is in **`docs/TESTING.md`**.

---

## Architecture at a glance

```
Driver device                                   Viewers (same app, viewer mode)
──────────────                                  ───────────────────────────────
GPS + sensors + driver actions + SOS + notes
        │
        ▼
   Local event log ── current-state snapshot
        │
   two-lane sync
   (live state first, backlog second)
        │  (only when connectivity permits)
        ▼
   Firebase Realtime DB  ───────────────────►  live state + timeline + freshness
   (rules: capability-scoped, owner-writes,
    expiry-gated reads)
```

Full detail in **`docs/ARCHITECTURE.md`**. The three non-negotiable contracts:

1. **No lost events.** Anything the driver records is preserved and eventually delivered, even if offline when it happened.
2. **Live when possible, honest otherwise.** With connectivity, viewers are near-live; without it, they see exactly when the last confirmed update arrived — never a fake current position. On reconnect, the current position is pushed *before* the historical backlog.
3. **Realistic ETA.** The arrival estimate models a human journey (breaks, fuel, rest, uncertainty), not uninterrupted road travel.

---

## Project structure

```
apps/android/        Android app (Kotlin/Compose)
  app/src/main/java/com/trippulse/app/
    core/            geo + id/time helpers
    domain/          models, config, state machine, stop/eta/deviation/summary engines
    data/            Room DB, event codec, routing, sync, Firebase transport, TripManager, ViewerRepository
    service/         foreground tracking service + boot/activity receivers
    notifications/   channels + notifications
    fcm/             viewer push handling
    di/              manual composition root
    ui/              Compose screens, navigation, view models
  app/src/test/      unit tests
firebase/            database.rules.json + firebase.json
functions/           Cloud Functions (expiry cleanup, SOS/arrival push)
docs/                spec + setup/architecture/testing/release guides
.github/workflows/   CI
```

---

## Scope

This build delivers **P0 + full P1** at production quality (no mocked functionality): live/near-live sharing, offline resilience, multiple viewers, credentials + expiry, stop/restart detection, water/food/toilet/rest, long-stop + overnight + morning resume, dynamic ETA with break budget, timeline, SOS, notes, route deviation, replay and summary. AI/predictive features (P2/P3) are intentionally out of scope; the deterministic engine comes first.

> **Not a medical or safety-guarantee product.** Sensor-derived states are inferences, driver-confirmed states are explicit, and the two are always distinguished. Don't market it as a medical safety system.
