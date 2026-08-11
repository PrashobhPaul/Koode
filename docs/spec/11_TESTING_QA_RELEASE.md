# TripPulse --- Testing, QA and Release Gates

## 1. Testing philosophy

The 1,100 km Kerala trip must be a validation of an already-tested
product, not the first test of core reliability.

## 2. Unit tests

Test:

-   state transitions
-   stop detection
-   break classification
-   ETA
-   break budget
-   overnight threshold
-   event serialization
-   event ordering
-   deduplication
-   credential expiry

## 3. Android integration tests

Test:

-   foreground service
-   location permission
-   GPS loss
-   network loss
-   network recovery
-   app backgrounding
-   process death
-   reboot recovery
-   battery saver
-   low battery
-   screen lock

## 4. Offline event test matrix

For each:

-   stop
-   food
-   water
-   toilet
-   rest
-   note
-   passenger
-   medicine
-   SOS

test:

``` text
online
offline
offline → online
app killed while pending
device reboot while pending
duplicate upload
```

## 5. Realtime test

With driver + Mom + wife + friend:

-   all viewers receive state
-   all viewers receive location
-   no viewer sees unauthorized trip
-   reconnecting viewer gets current state
-   late event appears chronologically

## 6. Long-stop test

Simulate:

-   30 min
-   90 min
-   120 min
-   180 min
-   overnight
-   morning resume

Verify correct state transitions.

## 7. ETA tests

Verify:

-   road ETA only is not displayed as final human ETA
-   break budget included
-   completed breaks reduce future budget appropriately
-   longer-than-expected breaks shift ETA
-   overnight changes ETA mode
-   uncertain cases produce ranges

## 8. SOS tests

Test:

-   accidental tap resistance
-   online SOS
-   offline SOS
-   delivery
-   retry
-   viewer notification
-   resolution
-   duplicate SOS
-   stale GPS
-   low battery

## 9. Battery test

Measure:

-   1 hour
-   4 hours
-   8 hours
-   12+ hours

under realistic driving conditions.

Record GPS accuracy, sync frequency and battery drain.

## 10. Network test

Use controlled conditions:

-   Wi-Fi off
-   mobile data off
-   airplane mode
-   intermittent connectivity
-   slow network
-   packet loss
-   network switching

## 11. GPS test

Test:

-   open sky
-   urban canyon
-   tunnel
-   highway
-   stationary POI
-   poor accuracy
-   sudden jump

## 12. Security test

-   brute force
-   invalid trip ID
-   expired trip
-   revoked trip
-   viewer write attempt
-   forged event
-   replay event
-   unauthorized RTDB path
-   secret leakage

## 13. Release gates

Release only if:

-   all P0 tests pass
-   no known data-loss bug
-   no critical authorization bug
-   no critical crash
-   offline events survive restart
-   SOS works
-   ETA tests pass
-   long-stop tests pass
-   battery is acceptable
-   Play policy checklist is complete

## 14. Final dress rehearsal

Use production-like build.

Driver: - actual device

Viewers: - Mom - wife - friend

Run a sufficiently long real drive before Kerala.

## 15. Field-test logging

Record:

-   timestamp
-   network state
-   GPS state
-   displayed ETA
-   actual ETA
-   stops
-   event sync delay
-   battery
-   crashes

Do not log sensitive content unnecessarily.
