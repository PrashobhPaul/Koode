# Release & Play Store Compliance

TripPulse's core function needs continuous location while the driver travels, which Google Play treats as sensitive. Compliance is designed into the app; this is the checklist to keep it compliant through submission.

## Foreground service (location)

- The manifest declares the tracking service with `android:foregroundServiceType="location"` and the app requests `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION` (Android 14+).
- Tracking starts from an **explicit user action** (the driver taps **Start trip** while the app is visible) — never silently from the background. Android forbids starting a location foreground service from the background, and the boot receiver respects this (it only auto-restarts when background location is granted, otherwise it posts a resume notification).
- The foreground notification clearly states the trip is being tracked, and the service stops (removing the notification and ending location updates) when the trip completes — no accidental tracking outside a trip.

## Background location

- `ACCESS_BACKGROUND_LOCATION` is used only to support restart-after-reboot and lock-screen tracking during an active trip. Because a location foreground service counts as "in use", core tracking works with while-in-use location; background permission is requested with a clear rationale and is not required to start a trip.
- For the Play listing: provide the **prominent in-app disclosure**, **strong core-functionality justification** (active private trip tracking that the user starts and shares), the **Data safety** form, and a **privacy policy**. Record a short demo of the start-trip flow for review.

## Permissions (and why)

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Track the journey during an active trip |
| `ACCESS_BACKGROUND_LOCATION` | Continue during an active trip when backgrounded / after reboot |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Run the tracking service |
| `POST_NOTIFICATIONS` | Foreground + arrival/SOS notifications (Android 13+) |
| `ACTIVITY_RECOGNITION` | Corroborating in-vehicle hint for stop detection (optional) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Sync + connectivity awareness |
| `RECEIVE_BOOT_COMPLETED` | Resume an active trip after reboot |

Permissions are requested progressively with explanations; nothing unnecessary is requested.

## Data safety / privacy posture

- Trip credentials are ephemeral and revoked after each trip; viewer access is capability-scoped and expiry-gated.
- Raw location retention is minimized by default (Cloud Functions purge live state + raw location on expiry; the event log is retained per policy).
- Sensitive notes (e.g. medicine) are stored locally, published to the cloud only as a marker, and kept out of logs/analytics/notification previews.
- **Do not** market the app as a medical or safety-guarantee system. Positioning: *private live journey sharing with intelligent break and wellbeing tracking.*

## Release checklist

1. Bump `versionCode` / `versionName` in `apps/android/app/build.gradle.kts`.
2. Provide `google-services.json` (cloud mode) and a release Maps key restricted to the release signing SHA-1.
3. Configure a real **release signing** config (the debug build currently signs release with the debug key for convenience — replace before publishing).
4. Deploy database rules + Cloud Functions (`docs/FIREBASE_SETUP.md`).
5. Build the release artifact: `./gradlew :app:bundleRelease` (AAB for Play).
6. Complete the Play Console **Data safety** form and **location permissions** declaration; attach the privacy policy and the prominent-disclosure demo.
7. Ship to internal testing (Release 0.1) → field test (0.2) → fixes (0.3) → production (1.0).
