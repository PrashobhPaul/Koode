# Developing Koode

The engineering guide: what is in the repository, how to build and test it, and how the pieces fit. The product story lives in the [README](../README.md).

---

## What's in the box

- **Android app** (`apps/android/`) — Kotlin, Jetpack Compose, single `:app` module, manual DI.
  - Foreground-service GPS tracking with adaptive sampling and Activity-Recognition corroboration.
  - Local-first **event log** (Room) + **current-state snapshot** + **two-lane sync** (live state first, historical backlog second).
  - Journey **state machine**, **stop detection** (traffic-light-safe), **break checkpoints**, **overnight** flow, **SOS** (offline-safe), **quick notes**, **route deviation**, inline **journey playback**, **journey summary**.
  - **Transport rule engine** (`domain/Transport.kt`) — one catalog of per-mode profiles decides break prompts, deviation, refuelling questions, sampling cadence and quick actions, so no screen has to ask "is this a train?".
  - **Hybrid journeys** — a journey is a list of legs, each with its own mode; the rule set switches when the vehicle does, and legs can be added or re-pointed *while the journey runs*.
  - **Journey analytics** (`domain/JourneyAnalytics.kt`) — moving vs stopped time, average moving speed against door-to-door speed, break cadence, cost by category with each share, cost per km and per hour, fuel efficiency, per-stage split, plus plain-English insights. One report feeds the dashboard, the closure review and both PDFs.
  - **Region intelligence** (`domain/Units.kt`) — ₹ and kilometres in India, $ and miles in the US, € in Europe, worked out from the network's country. No model needed; overridable in Settings.
  - **PDF export** of the timeline and the money tracker: flat, watermarked, generated on device, opening with the analysed dashboard.
  - Realistic **ETA engine**: route travel time + future break budget + uncertainty, presented as a *range* with an explainable breakdown.
  - **Freshness** model for viewers: `LIVE / RECENT / STALE / OFFLINE / COMPLETED` — a stale location is never shown as live.
- **Supabase backend** (`supabase/schema.sql`) — the ENTIRE server side in one SQL file: capability-token security (only the creating driver device can write; viewers are read-only), expiry-gated reads, and self-destruction of expired trips. No functions, no auth service, no push infrastructure.
- **Browser viewer** (`web/`) — a single static page, no build step, that derives the same capability with WebCrypto and calls the same read-only RPCs. Published to GitHub Pages.
- **CI** (`.github/workflows/`) — builds the debug APK and runs unit tests on every push/PR, and publishes the browser viewer.
- **Docs** (`docs/`) — the full spec plus setup/architecture/testing/release guides.

---

## Local mode vs cloud mode

The app runs immediately in **local mode** with no backend: full on-device tracking, stop detection, checkpoints, ETA, replay and summary all work; only *remote* viewer sharing is inactive. Fill in the two lines of `apps/android/supabase.properties` and rebuild to activate cloud mode — no code changes.

| Capability | Local mode | Cloud mode |
|---|---|---|
| Tracking, stop/break detection, ETA, playback, summary, PDF export | ✅ | ✅ |
| Remote followers (journey number + passcode) | — | ✅ |
| Browser viewer | — | ✅ |
| Live state + historical backlog sync | — | ✅ |
| Forced start/SOS/arrival alerts on followers' phones | — | ✅ |

See **`docs/SUPABASE_SETUP.md`** to enable cloud mode (one-time, ~5 minutes, free). The live map needs no setup at all — it renders free OpenFreeMap vector tiles (OpenStreetMap data) via MapLibre, and routing uses the free OSRM public server (**`docs/MAPS_SETUP.md`**).

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

### Configuration

- **Maps/routing:** nothing to configure — OpenFreeMap vector tiles and OSRM routing are free and keyless.
- **Cloud sharing:** fill in the two values in `apps/android/supabase.properties` (see `docs/SUPABASE_SETUP.md`). Empty values build in local mode. The anon key is public by design — all access control lives in `supabase/schema.sql`.

---

## Continuous integration

`.github/workflows/android-build.yml` builds `:app:assembleDebug`, runs unit tests, and uploads the APK + test results as artifacts on every push/PR. `.github/workflows/release-apk.yml` (run manually from the Actions tab, or by pushing a `v*` tag) builds the APK and publishes it as the **latest GitHub Release**, which is what the download link in the README serves. CI is the reproducible build path — it doesn't depend on a local machine's JDK setup.

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
   Supabase (Postgres + REST)  ────────────►  live state + timeline + freshness
   (SQL-enforced: owner-token writes only,
    expiry-gated reads, 30-min self-destruct)
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
    data/            Room DB, event codec, routing, sync, Supabase transport, TripManager, ViewerRepository
    service/         driver tracking service, viewer follow/alert service, receivers
    notifications/   channels + notifications
    di/              manual composition root
    ui/              Compose screens, navigation, view models
  app/src/test/      unit tests
supabase/            schema.sql — the entire backend (tables, security, expiry) in one file
docs/                spec + setup/architecture/testing/release guides
.github/workflows/   CI
```

---

## Scope

This build delivers **P0 + full P1** at production quality (no mocked functionality): live/near-live sharing, offline resilience, multiple viewers, credentials + expiry, stop/restart detection, water/food/toilet/rest, long-stop + overnight + morning resume, dynamic ETA with break budget, timeline, SOS, notes, route deviation, replay and summary. AI/predictive features (P2/P3) are intentionally out of scope; the deterministic engine comes first.

> **Not a medical or safety-guarantee product.** Sensor-derived states are inferences, driver-confirmed states are explicit, and the two are always distinguished. Don't market it as a medical safety system.
