# TripPulse --- Build Specification Index

TripPulse is a private, trip-scoped, realtime/offline-resilient
road-trip companion for Android, with the same app acting as both driver
and viewer.

## Canonical documents

Build in this order:

1.  `01_PRODUCT_REQUIREMENTS.md` --- product scope, actors, journeys,
    acceptance requirements.
2.  `02_SYSTEM_ARCHITECTURE.md` --- production architecture and
    non-negotiable design principles.
3.  `03_DOMAIN_EVENT_MODEL.md` --- event taxonomy, state machines,
    provenance and data contracts.
4.  `04_ANDROID_DRIVER_APP.md` --- Android architecture, location
    tracking, sensors, offline operation and safe interaction.
5.  `05_FIREBASE_BACKEND.md` --- Firebase Realtime Database, Auth,
    Functions, notifications and data lifecycle.
6.  `06_REALTIME_SYNC_OFFLINE.md` --- live state, offline queues,
    reconciliation, freshness and conflict handling.
7.  `07_ETA_JOURNEY_INTELLIGENCE.md` --- realistic human ETA, break
    budget, overnight mode and adaptive forecasting.
8.  `08_VIEWER_UI_UX.md` --- driver/viewer screens, states,
    accessibility and interaction rules.
9.  `09_NOTES_SOS_INCIDENTS.md` --- notes, passenger context, medicine
    notes, SOS and incident workflows.
10. `10_SECURITY_PRIVACY_PLAYSTORE.md` --- threat model, privacy,
    permissions and Google Play release requirements.
11. `11_TESTING_QA_RELEASE.md` --- automated tests, simulation, field
    testing and release gates.
12. `12_API_DATA_CONTRACTS.md` --- client/server contracts and canonical
    payloads.
13. `13_FIREBASE_SCHEMA_RULES.md` --- RTDB schema, indexes and
    authorization rules.
14. `14_IMPLEMENTATION_PLAN_FOR_CLAUDE_LOVABLE.md` --- exact
    implementation sequence and coding-agent instructions.
15. `15_RELEASE_RUNBOOK.md` --- production configuration, monitoring,
    Play Store release and Kerala-trip readiness.

## Source of truth

These documents collectively replace informal chat requirements. If two
documents conflict, resolve in this priority:

`01 Product Requirements` → `02 Architecture` → `03 Domain/Event Model`
→ implementation-specific documents.

## Mandatory product guarantees

-   Connected trips are as close to live as device, GPS and network
    conditions permit.
-   Offline events are never intentionally discarded.
-   Offline breaks, food, water, toilet, rest, notes and SOS events
    retain original timestamps.
-   Current state is synchronized with higher priority than historical
    backlog after reconnection.
-   ETA represents a realistic human journey, not uninterrupted
    navigation time.
-   Long stops over two hours trigger an overnight/accommodation
    checkpoint.
-   Multiple authorized viewers can monitor the same trip
    simultaneously.
-   The driver is not asked to interact while moving.
-   Sensor observation, software inference and driver confirmation are
    always distinguishable.
-   Trip credentials expire after the trip; live viewer access is not
    permanent.
-   The app is designed for Google Play publication, not merely a
    prototype.

## Target field validation

The first production-like field test is a long road trip from Hyderabad
to Thrissur. The test should happen only after the release gates in
`11_TESTING_QA_RELEASE.md` pass.
