# Koode — *Always with you*

> **Know they're okay, without having to ask.**

**Koode** (Malayalam: *"together / with you"*) is a **personal journey companion** that keeps the people you love informed about your journey, wellbeing and safety — without requiring you to constantly call or message them.

**Category:** Personal Journey Safety & Wellbeing
**Philosophy:** *Travel freely. Stay connected. Let the app do the reassuring.*
**Secondary tagline:** *Your journey. Their peace of mind.*

Koode is not a location tracker. The family doesn't monitor the traveller — **the app monitors the journey** and evaluates its health continuously:

| | Journey Health | Means |
|---|---|---|
| 🟢 | **Normal** | Journey progressing normally |
| 🟡 | **Attention** | Something unusual — late-night travel, a long spell without a logged break, low phone battery, off the usual route, updates arriving slowly |
| 🔴 | **Concern** | Potentially significant — SOS, no location updates, an unusually long unexplained stop |

What the family sees is a **reassurance channel**, not a GPS console: *"Prashobh's Journey · Hyderabad → Thrissur · 🟢 Journey progressing normally · 📍 Currently near Vijayawada · ⏱ ETA 8:40 PM · 🍛 Last logged 1:15 PM"* — with a live map and friendly timeline below. Wellbeing is always **factual, never medical**: "Last logged water: 1h 20m ago", never a health claim.

The traveller's experience is deliberately minimal: **Start journey → forget the app → travel.** Koode works quietly in the background; the people who matter get a few timely notifications — journey started, needs attention, arrived safely.

Private by design: journeys are invitation-only (the owner approves each viewer **by name**), and every journey **self-destructs from the cloud 30 minutes after arrival**. It stays open source and zero-cost end to end.

---

## 📲 Download the app

**[⬇️ Download Koode.apk (latest build)](https://github.com/PrashobhPaul/TripPulse/releases/latest/download/Koode.apk)**

For family & friends who want to follow a trip:

1. Open the link above on your Android phone and download `Koode.apk`.
2. Open the downloaded file and allow **Install from unknown sources** if asked.
3. Open Koode → **Their journeys** → enter the **Journey ID and your name** (the traveller approves you by name; a shared password gives instant access).

You'll then see the driver's live position, ETA, breaks and timeline — and get **forced alerts** on your phone when the trip **starts**, on **SOS**, and when the driver **reaches the destination**, even with the app in the background. Joining needs only the **Trip ID and your name — the driver approves each viewer by name** before anything is visible (a password shared by the driver skips the approval step). A trip can run from **any start point to any destination**; the driver types a place, uses their current location, long-presses the map to drop a start or destination pin, or picks a **saved place** (Home, Office, or any custom label) with one tap. For privacy, the trip id **self-destructs 30 minutes after the driver/rider reaches the destination** — viewer access is cut off and the trip's cloud data is deleted.

The whole stack is **fully open source and zero cost**: OpenStreetMap + OSRM for maps/routing (no API keys) and Supabase (open-source Postgres, free tier) for live sharing — no Google Maps, no Firebase, no billing accounts anywhere.

A single Android app is both the **driver** app and the **viewer** app. The driver creates a trip, shares a `Trip ID` + secret password, and family/friends follow read-only. The journey is captured as a durable local event log first, then synced to the cloud backend (Supabase — open-source Postgres, free tier); a network outage changes *when* the server receives an event, never *whether* it exists.

This repository is the working implementation of the product/engineering plan in `docs/spec/`.

---

## What's in the box

- **Android app** (`apps/android/`) — Kotlin, Jetpack Compose, single `:app` module, manual DI.
  - Foreground-service GPS tracking with adaptive sampling and Activity-Recognition corroboration.
  - Local-first **event log** (Room) + **current-state snapshot** + **two-lane sync** (live state first, historical backlog second).
  - Journey **state machine**, **stop detection** (traffic-light-safe), **break checkpoints**, **overnight** flow, **SOS** (offline-safe), **quick notes** (passenger/medicine, medicine treated as sensitive), **route deviation**, **trip replay**, **trip summary**.
  - Realistic **ETA engine**: route travel time + future break budget + uncertainty, presented as a *range* with an explainable breakdown.
  - **Freshness** model for viewers: `LIVE / RECENT / STALE / OFFLINE / COMPLETED` — a stale location is never shown as live.
- **Supabase backend** (`supabase/schema.sql`) — the ENTIRE server side in one SQL file: capability-token security (only the creating driver device can write; viewers are read-only), expiry-gated reads, and self-destruction of expired trips. No functions, no auth service, no push infrastructure.
- **CI** (`.github/workflows/`) — builds the debug APK and runs unit tests on every push/PR.
- **Docs** (`docs/`) — the full spec plus setup/architecture/testing/release guides.

---

## Local mode vs cloud mode

The app runs immediately in **local mode** with no backend: full on-device tracking, stop detection, checkpoints, ETA, replay and summary all work; only *remote* viewer sharing is inactive. Fill in the two lines of `apps/android/supabase.properties` and rebuild to activate cloud mode — no code changes.

| Capability | Local mode | Cloud mode |
|---|---|---|
| Driver tracking, stop/break detection, ETA, replay, summary | ✅ | ✅ |
| Remote viewers (Trip ID + password) | — | ✅ |
| Live state + historical backlog sync | — | ✅ |
| Forced start/SOS/arrival alerts on viewer phones | — | ✅ |

See **`docs/SUPABASE_SETUP.md`** to enable cloud mode (one-time, ~5 minutes, free). The live map needs no setup at all — it renders OpenStreetMap tiles via osmdroid, and routing uses the free OSRM public server (**`docs/MAPS_SETUP.md`**).

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

- **Maps/routing:** nothing to configure — OpenStreetMap tiles and OSRM routing are free and keyless.
- **Cloud sharing:** fill in the two values in `apps/android/supabase.properties` (see `docs/SUPABASE_SETUP.md`). Empty values build in local mode. The anon key is public by design — all access control lives in `supabase/schema.sql`.

---

## Continuous integration

`.github/workflows/android-build.yml` builds `:app:assembleDebug`, runs unit tests, and uploads the APK + test results as artifacts on every push/PR. `.github/workflows/release-apk.yml` (run manually from the Actions tab, or by pushing a `v*` tag) builds the APK and publishes it as the **latest GitHub Release**, which is what the download link at the top of this README serves. CI is the reproducible build path — it doesn't depend on a local machine's JDK setup.

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
