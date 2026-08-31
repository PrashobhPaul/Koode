# 17 — v6.1.0: what the first real journey taught us

v6.0 was designed. v6.1 was *travelled*. Someone took the app from Bangalore
to home overnight, and almost everything in this document is a consequence of
what happened on that trip rather than of anything anyone specified.

That distinction matters for the next person changing this code: these are not
preferences. Each one is a thing the app did to a real traveller.

---

## 0. The two failures, and why they were one failure

**Symptom A.** Tracking stopped after a water log in Bangalore, late at night,
and never resumed — all the way home, on a phone with working network the
whole time.

**Symptom B.** Ending the journey froze the app. Reproducibly, at the same
point, on a second attempt.

They read like separate bugs. They were the same incident.

`completeInternal` used to push the entire cloud backlog inline:

```kotlin
if (completed.cloudEnabled) {
    sync.pushLiveState(...)   // one round trip
    sync.drain(completed)     // every unsent event, every location batch
    cloud.setExpiry(...)      // one more
}
```

`drain()` walks the queue one network round-trip at a time. That is fine for a
journey that has been syncing all along and catastrophic for one that has not
— and symptom A guaranteed a night's worth of unsent rows. So the traveller
tapped **End journey** and the app began a multi-minute upload while holding
`TripManager.lock`, with the UI awaiting a callback behind no progress
indicator and no timeout.

**The rule now:** a journey is over the instant it is written locally.
Everything after that is delivery, and delivery must never hold the traveller.
The push moved to `appScope`, ordered so followers see the arrival first and
the backlog follows. Nothing can be lost by this — every row is already
durable in Room, and a later drain collects whatever this one does not finish.

Both drain loops are also bounded. An unbounded `while (pending)` trusts the
server and the queue to agree about what "pending" means; if they ever
disagree it is an infinite upload rather than a bug report.

---

## 1. Recovery must not depend on the thing it recovers

The reason tracking never came back is worth stating plainly, because it is a
shape of bug that will recur elsewhere.

The only code that re-requested location updates lived **inside the location
callback**:

```kotlin
override fun onLocationResult(result: LocationResult) {
    ...
    scope.launch { graph.tripManager.onLocation(fix); maybeAdjustInterval() }
}                                                     // ^ the only re-request
```

So the recovery path could only run while the thing it was meant to recover
was already working. Doze, or one of the aggressive OEM battery managers
common on the phones our travellers actually carry, had to interrupt delivery
exactly *once* for tracking to stay dead for the rest of the journey.

**The rule now:** anything that heals a subsystem runs on a clock that
subsystem cannot stop.

- `TripTrackingService.watchdog()` runs on the 30-second ticker, which does
  not care whether a fix ever arrives. Three missed cycles (floor: five
  minutes, so a tunnel is not a crisis) and it re-subscribes and asks for one
  fix outright.
- After repeated failures it drops to `PRIORITY_BALANCED_POWER_ACCURACY`. A
  fix Doze will actually hand over beats a precise one it keeps withholding.
- A service that has been killed cannot notice it was killed, so
  `JourneyKeeperWorker` checks from **outside the process** every fifteen
  minutes, deliberately with **no network constraint** — it matters most when
  the network is the thing that broke.
- Where Android 12+ forbids a background foreground-service start, we do not
  fight it. That restriction is a good one. The traveller gets a notification
  they can tap.
- `onTaskRemoved` restarts tracking: swiping the app out of recents is not the
  same as ending a journey.

A location is now stored **at least hourly**, moving or parked. An hour-wide
hole in the timeline is indistinguishable, to whoever is following, from
tracking having died — and the travelled line needs the point either way.

---

## 2. Multi-leg was the wrong shape

The original design let a traveller edit a running journey's destination and
add stages with new destinations. That was wrong twice over.

**It asked questions the app could already answer.** Where are you now? The
phone knows. Where are you going? The journey has known since it started.
Asking someone standing on a platform to type either is a small humiliation.

**It let the journey become a different journey.** People agreed to follow a
trip to a named place. Quietly re-pointing it means what they are watching is
no longer what they consented to watch.

**The rule now:** the destination is fixed at creation and never changes.
`changeDestination` is deleted. What changes mid-journey is the *vehicle*:

```kotlin
suspend fun switchMode(newMode, details, breakdown): SwitchResult
```

It closes the current stage where the traveller is standing and opens a new
one to the point that stage was already heading for — the next waypoint on a
journey with stages planned ahead, the destination otherwise. The new stage is
*inserted after* the current one and later stages shift up, so switching
vehicles part-way never silently skips what came after.

**Naming the switch point avoids reverse geocoding on purpose.** It needs the
network at exactly the moment a traveller changing vehicles may not have it,
and a stage that failed to be created because a lookup timed out would be
indefensible. A saved place within 500 m is free and usually a better name
anyway — "Home" beats a road name — and otherwise "En route" is simply true.

**Leaving a private vehicle requires a breakdown.** A car journey has no
natural stages; you drive the whole way. The only honest reason to switch out
of one is that the car stopped being an option, and the timeline should say
that rather than record a neutral "changed mode".

---

## 3. What we ask about a vehicle, and how hard

`domain/TravelDetails.kt` — each mode declares its own fields and every
surface renders from that declaration, so the create screen and the
mid-journey switch cannot drift apart and adding a field is one change.

| Mode | Asked | Required |
|---|---|---|
| Cab | Service, vehicle number | no |
| Bus | Operator, seat, PNR | no |
| Train | Train number, coach, seat, PNR | no |
| Flight | Flight number, seat, booking reference | no |
| Private vehicle | Vehicle, body type, fuel, registration | **yes, all** |

The asymmetry is the whole design. In your own vehicle those details *are* the
safety information — "a white Swift, KL-08-AC-1234" is what a family member
repeats to someone who can help — so a blank field there is a gap in exactly
the moment this app exists for. Everywhere else they are conveniences, and a
passenger settling into a seat must never be blocked by a form.

**Every operator list ends in "Other"**, which turns the field into free text.
A traveller on a bus company we have never heard of must not be stuck.

**Fuel is the one closed list**, and deliberately so: the app has to know
whether a refill is litres, kilograms or kilowatt-hours to record it at all,
and "Other" answers none of those. `TravelDetails.fuelUnit()` owns that
question and matches case-insensitively — because it did not, once, and an
electric car logged a 30 kWh charge as 30 litres, which then fed the
efficiency figure in the dashboard and both PDFs.

Stored as one JSON column so the next mode's questions need no migration. The
v5→v6 migration is additive and builds its backfill by string concatenation
rather than `json_object()`: the JSON1 extension is only guaranteed from
Android 11, and a migration that throws on an older phone is precisely the
failure this codebase refuses to risk.

---

## 4. Closing a journey

Only the traveller may end one — rule zero, unchanged. But a journey whose
traveller simply *forgot* stays live, and everyone watching keeps seeing a
moving dot for somebody already home and asleep.

**Nudges widen rather than repeat:** fifteen minutes after arrival, then
forty-five, then two hours, then it stops. The second nudge is useful; the
tenth is an app to be uninstalled.

**Dismissing "you seem to have arrived" now means it.** It used to clear only
the flag, leaving the journey stuck in `ARRIVED` — so someone who stopped at a
friend's house on the way would never be asked again when they genuinely did
arrive, and would meanwhile be nagged to close a journey they were still
travelling on.

**Journeys still open 72 hours after they began are deleted**, with everything
attached. Long enough for a genuinely long haul; short enough that a forgotten
one does not live forever.

**One live journey per phone**, enforced in `createTrip` rather than only on
the screen with the button. Two would be two simultaneous claims about where
one person is, and a follower would have no way to tell which to believe.

---

## 5. Smaller things, same principle

- **Playback keeps both ends on screen.** It used to chase the moving dot,
  which zooms into it and loses the two points the movement is only meaningful
  between. Also fixed: the first fit framed whatever had arrived by the first
  composition — often a single fix — and latched, so the map never re-framed
  once the route turned up.
- **Places you have already been** are offered as chips under From and To.
  Almost nobody's next journey starts somewhere they have never been, and the
  worst part of the create screen was typing a name and hoping search agreed.
- **One mark, everywhere.** The launcher, splash, PDF header, PDF watermark
  and browser viewer were four different logos. `res/drawable/ic_koode_mark.xml`
  is now the single definition; the PDF rasterises it rather than redrawing it,
  and the web viewer's SVG matches it path for path. The watermark is the mark
  itself, faint — a word stamped diagonally reads as something applied to
  someone else's paper.
