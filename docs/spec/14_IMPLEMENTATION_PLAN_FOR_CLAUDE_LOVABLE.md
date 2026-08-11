# TripPulse --- Implementation Plan for Claude / Lovable

## 1. Important instruction

Do not build the entire app in one speculative pass.

Implement vertical slices, test each slice, and keep the application
runnable after every milestone.

## 2. Repository

Recommended:

``` text
/apps/android
/apps/web
/functions
/docs
```

If the chosen Lovable environment cannot directly build native Android,
use it for the web/PWA and backend/dashboard while the Android
application is developed as a separate Kotlin project. Do not fake
Android background tracking in a web app.

## 3. Milestone 0 --- foundation

Create:

-   repository
-   documentation
-   environment configuration
-   Firebase project configuration
-   CI
-   linting
-   test framework
-   secret handling

No production secrets committed.

## 4. Milestone 1 --- trip lifecycle

Build:

-   Start Trip
-   Join Trip
-   credential generation
-   active trip
-   completion
-   expiration

## 5. Milestone 2 --- local event engine

Build:

-   Room schema
-   event envelope
-   event repository
-   state machine
-   durable queue
-   idempotent event IDs

Write tests before adding realtime.

## 6. Milestone 3 --- realtime

Build:

-   Firebase current state
-   location updates
-   viewer listener
-   freshness state
-   reconnect

Acceptance: driver location appears on viewer with realistic latency.

## 7. Milestone 4 --- offline

Test:

-   network loss
-   stop
-   food/toilet/water/rest
-   network return
-   current state first
-   historical event reconciliation

Do not continue until this passes.

## 8. Milestone 5 --- stop and checkpoint

Build:

-   stop detection
-   restart detection
-   safe checkpoint
-   multi-select
-   local save
-   sync

## 9. Milestone 6 --- ETA

Build deterministic ETA service:

``` text
route time
+ break budget
+ fuel/charge
+ uncertainty
```

Add explanation UI.

## 10. Milestone 7 --- overnight

Build:

-   2h detection

-   overnight prompt

-   accommodation state

-   next morning resume

-   ETA rebuild

## 11. Milestone 8 --- notes

Build:

-   quick note
-   passenger joined/left
-   medicine
-   vehicle issue
-   route change
-   sensitive flag

## 12. Milestone 9 --- SOS

Build:

-   long press
-   local SOS
-   online delivery
-   offline delivery
-   FCM
-   viewer incident mode
-   resolution

## 13. Milestone 10 --- hardening

Build:

-   security rules
-   rate limiting
-   expiry
-   crash reporting
-   analytics with privacy
-   battery tuning
-   Play compliance

## 14. Milestone 11 --- testing

Run the complete matrix in `11_TESTING_QA_RELEASE.md`.

## 15. Milestone 12 --- polish

Only now add:

-   trip replay
-   analytics
-   personalization
-   AI summaries

## 16. Coding rules

-   no fake sensor data in production code
-   no mocked live state hidden behind UI
-   no secrets in source
-   no direct Firebase calls from UI where repository abstraction is
    appropriate
-   all async operations cancellable
-   all events idempotent
-   all important state transitions tested
-   no blocking network dependency for local trip operation

## 17. AI coding-agent workflow

For each milestone:

1.  inspect existing code
2.  state intended changes
3.  implement smallest coherent slice
4.  run tests
5.  fix failures
6.  update docs
7.  provide changed files and validation
8.  only then move forward

## 18. Do not prematurely optimize

First achieve correctness.

Then measure:

-   battery
-   GPS accuracy
-   sync latency
-   database usage
-   notification latency

Then optimize.

## 19. Completion condition

The coding agent must not declare the project complete because the UI
works.

Completion requires passing the release gates and real-device
network/restart/long-stop tests.
