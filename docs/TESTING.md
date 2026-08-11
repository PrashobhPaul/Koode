# Testing

## Unit tests

Pure logic is covered by JVM unit tests (`apps/android/app/src/test/`). Run:

```bash
cd apps/android
./gradlew :app:testDebugUnitTest
```

Coverage:

- **JourneyStateMachineTest** — valid transitions, invalid inputs return null (never crash), `EXPIRE`/`COMPLETE` handling, premature-arrival roll-again.
- **StopDetectorTest** — traffic-light stop is *not* a break, genuine multi-minute stop is detected, restart emits stop-ended with correct duration, confirmed stop matures into a long stop.
- **EtaEngineTest** — arrived/overnight modes, overnight yields null times, long-trip minimum break buffer is enforced, range ordering (`low ≤ likely ≤ high`), realistic ETA exceeds pure travel time, fallback provider is low-confidence.
- **IdsTest** — credential shape, ambiguous characters excluded, access-key determinism + case-insensitivity, different secret → different key, batch uniqueness.
- **DeviationTest** — no detection without a route, no flag on-corridor, brief detour under the persist window doesn't flag, sustained off-corridor flags then recovers.
- **SummaryTest** — stop/break counts, driving time = total − confirmed stop durations, longest break, longest leg, distance, calendar-day count.

## Real-device staged plan

The 1,100 km field trip must **not** be the first end-to-end test. Progress through these stages on the exact production build before the field test.

**Stage 1 — Simulation.** Exercise driving, stops, breaks, network loss/return, GPS gaps, long stops, overnight, and battery reduction (mock/emulator location + airplane mode toggling).

**Stage 2 — Short drives.** 5 km → 20 km → 50 km → 100 km with at least two viewers connected. Verify near-live freshness, stop/restart detection, and one-tap checkpoints.

**Stage 3 — Network-failure.** Physically disable mobile data during driving, stopping, a food break, a toilet break, and restart. Verify: the journey continues locally; on reconnect the **current position uploads before** the historical backlog; every offline event lands at its **original** timestamp; no duplicate events.

**Stage 4 — Overnight.** A simulated >2 h stop and next-morning resume: overnight prompt appears only while stationary, ETA switches to `OVERNIGHT_PENDING`, and morning movement resumes cleanly.

**Stage 5 — Dress rehearsal.** A long continuous run with the driver phone + all viewer phones on the production build.

**Field test — Hyderabad → Thrissur (~1,100 km).** Driver + mother + wife + friend on the same credentials. This is both the real journey and the final validation.

## Success metrics (field test)

- **Live tracking:** ≥95% of connected periods show viewer freshness within the target window.
- **Reconnection:** current location reaches the backend before the historical backlog every time.
- **Stop detection:** major stops detected without excessive false break prompts.
- **Wellbeing:** water/food/toilet/rest recordable in seconds.
- **Viewer:** the family can follow the journey without calling for routine status.
- **Overnight:** a >2 h stop is correctly classified after restart.
- **Security:** after completion, old credentials no longer grant live access.
- **Battery:** practical for the full journey without abnormal drain.

## Debug instrumentation

A protected debug screen can surface GPS availability/accuracy, network state, backend connection, last upload, pending event count, battery, tracking-service state, sampling interval, and current journey state. Keep it disabled/guarded in production.
