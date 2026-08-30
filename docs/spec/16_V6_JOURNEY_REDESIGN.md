# 16 — v6.0.0: journeys that only their traveller can end

This document records the decisions behind the v6 redesign. It is written for
the next person to change this code, so it explains *why* each rule exists and
what breaks if it is removed.

---

## 0. The one rule

> **A journey is over only when its traveller says so.**

Everything else in this document follows from it.

A person watching a journey reads "ended" as "they're home and safe". If the
app can say that for any reason other than the traveller ending the journey,
the app can lie about the single fact it exists to report. So there is exactly
one path to `JourneyStatus.COMPLETED`: `TripManager.completeTrip()`, called
from a button the traveller presses.

What used to end journeys, and no longer does:

| Old behaviour | Why it was wrong | Now |
|---|---|---|
| Auto-complete 20 min after arrival was detected | The app decided; parked-at-destination is not "finished" | Arrival is detected, an `arrivalPromptDue` flag is set, and the traveller is *asked* |
| `TripFollowService` marked a followed journey expired whenever `fetchMeta` returned null | A REST failure is a statement about the network, not the traveller | Records `unreachableSinceMs`; the UI says "Waiting for updates" |
| Capability expired 30 min after *arrival detection* | A journey running longer than expected locked its own followers out | Capability lives 7 days, and a one-hour self-destruct starts when the traveller ends the journey |

The follower side has one predicate, `ViewerRepository.isEndedByOwner`, which
requires a **positive** signal: `endedByOwner` or `status == COMPLETED` in the
traveller's own live state, or a `TRIP_COMPLETED` event in the journey log.
Absence of data is never evidence.

---

## 1. Transport decides the rules

`domain/Transport.kt` holds a catalog of `TransportProfile`s. Every
mode-dependent decision in the app reads from a profile; nothing anywhere else
asks "is this a train?".

| Flag | What it gates | True for |
|---|---|---|
| `isPrivateVehicle` | fuel questions, driving-fatigue rules | CAR, BIKE |
| `isRoadMode` | whether a road polyline means anything | CAR, BIKE, CAB, BUS |
| `stopPromptsEnabled` | break prompt + `STOP_STARTED`/`STOP_ENDED` events on a confirmed halt | CAR, BIKE |
| `deviationEnabled` | route-deviation detection | CAR, BIKE, CAB |
| `wellbeingIsBreak` | whether food/tea/water also writes a break record | CAR, BIKE |
| `expectsOfflineStretches` | silence is normal, not a concern | TRAIN, FLIGHT |
| `quickActions` | the sentences offered on the journey screen | all |
| `defaultCadence` | baseline location sampling | all |

Two consequences worth stating plainly:

- **A train cannot leave its rails.** Deviation on rail is pure noise in a
  family's timeline, so `TRAIN.deviationEnabled = false`. The same applies to a
  bus on a timetabled route we don't hold.
- **Eating on a train is not a break.** A halt on public transport is the
  timetable, not a decision the traveller made. Wellbeing logs there are notes;
  no break record is written, `lastBreakEndAt` is untouched, and the viewer's
  Rest row reads "Not applicable on this leg".

Public transport gets milestones in its own words — "Boarded the train",
"Train halted", "Moving again", "Deboarded the train" — carried in the event
payload so the viewer renders a sentence without knowing the mode.

Unknown modes fall back to `CAR`, so a journey created by a newer build never
breaks an older viewer.

---

## 2. Hybrid journeys are the base case

Real journeys are rarely single-mode: Thrissur → Bangalore by train, then
Bangalore → Hyderabad by bus.

A journey is therefore a **list of legs** (`trip_legs`), each with its own mode,
endpoints and ticket details. A single-mode journey is a one-leg list, so there
is exactly one code path rather than a simple case and an advanced case.

`TripManager.advanceToNextLeg()` closes the current leg and opens the next. It
resets the stop detector and the deviation detector (a new leg is a new road,
and detector state must not leak across a change of vehicle), refetches the
route, and re-reads the rule set — so break prompts, deviation and sampling
cadence all switch by themselves.

"Distance to go" always means to the *final* destination: the current leg's
remaining distance plus a road-factored estimate for every leg still ahead.

---

## 3. Credentials people can read out loud

The old scheme was `TP-XXXX-XXXX` plus `XXXX-XXXX-XXXX`. Two dash groups and a
mixed alphabet, shared over WhatsApp and typed by someone's parent. A paste
that clipped a dash produced a different credential and an unexplained "not
found".

- **Journey number**: 8 digits, always rendered with an app-supplied `TP-`.
- **Passcode**: 6 digits, chosen by the traveller (a random one is offered).
- **Access key**: `SHA-256(journeyId:passcode)` — unchanged, so credentials
  issued by any earlier build still resolve.

`TripCredentials.resolve` accepts everything a real person produces:
`40381927`, `TP-40381927`, `TP-4038 1927`, `TP40381927`. Anything containing
letters is treated as a legacy id and only trimmed/upper-cased.

### The "optional password" bug

The passcode was advertised as optional. Without one, `requestJoinById`
returned `Pending` — correct — but the screen mapped every non-`Ok` outcome to
an error and told the user to check their network. The passcode really was
optional; the *reporting* was broken.

`JoinVm` now has five distinct outcomes, and waiting is a state rather than a
failure: approved, waiting for approval, declined, wrong passcode, or genuinely
offline. `ViewerRepository.join` probes `serverReachable()` before blaming the
user for credentials, so "wrong passcode" and "no signal" never share a message.

---

## 4. Battery

Location sampling and follower polling are the app's entire battery budget, and
the right answer differs per journey. Both are now settings layered over
per-mode defaults:

```
effective cadence = max(user setting, mode default)   // less precise wins
                    → SAVER if battery <= user threshold
                    → SOS cadence if an SOS is active
```

Follower polling is priced per read (`ViewerRepository.statePollMs`) instead of
a fixed 5 s: a moving journey earns a frequent check, one resting overnight or
already ended earns almost none. `TripCloud.pollingFlow` backs off
exponentially on consecutive failures, so a phone in a tunnel doesn't spend the
journey retrying. The follower foreground service went from a fixed 30 s sweep
to the user's own refresh cadence, with a 3-minute floor when nothing is
readable.

---

## 5. Meal intelligence

A traveller should not do paperwork while travelling. One tap on "food" and
`MealClassifier` names the meal:

- 04:00–10:59 → breakfast, 11:00–15:59 → lunch, 19:00–03:59 → dinner
- 16:00–18:59 has no anchor meal, so food there is a snack
- the **first** food inside a window is that window's meal; anything later in
  the same window is a snack

An explicit tap ("Snack", "Tea / coffee", or naming the meal) always wins. The
app guesses only when the traveller didn't say.

---

## 6. The map

`ui/map/JourneyOverlay.kt` draws the journey itself rather than using osmdroid's
bitmap pins. One overlay, mutated and invalidated at animation rate — rebuilding
overlays every frame is what makes maps stutter.

- **Live dot**: solid blue core, white collar, expanding halo driven by a
  `pulsePhase` the caller owns (so it stops when the screen isn't visible), and
  a heading wedge when bearing is known.
- **Origin**: a ringed node with a solid core — quieter, because it's done.
- **Destination**: a pennant on a mast standing on a base, so it reads as a
  place on the ground.
- **Travelled path**: drawn solid over the faint planned route, ending where the
  traveller actually got to.

Playback lives on the map, not on a screen of its own: a ▶ control that steps
5× → 10× → 20× → 30×. Starting at 5× is deliberate — a real-time replay of a
six-hour drive is not a feature. The separate Replay screen and the viewer's
Leave button are both gone.

---

## 7. Updating mid-journey

Koode ships as a direct APK, so nothing nags on our behalf: a phone that
installed v5 will run v5 forever unless told. `UpdateChecker` does one
unauthenticated GET against the latest GitHub release, at most daily, and
surfaces a dismissible card. It is advisory, silent on failure, and never
blocks.

The thing that makes an update safe to install *mid-journey* is not the checker
but the migration policy: **every migration is additive with a default**, and
`fallbackToDestructiveMigration()` is gone. A journey in progress keeps its row,
its event queue and its location buffer, and gains columns whose defaults
describe exactly what was already true. Losing a traveller's live journey to a
missing migration would be the worst possible failure mode, so a missing
migration must now fail loudly in testing rather than silently wipe data in the
field.

---

## 8. The browser viewer

`web/` is a single static page — no framework, no build step. It derives the
same access key with WebCrypto and calls the same read-only RPCs, so a parent
who will never install an APK still sees the live map, the timeline and the
arrival estimate. The passcode never leaves the browser; only the hash is sent.

It obeys the same rules as the app: it never says "ended" without a positive
completion signal, it keeps the last known picture on screen when a read fails,
and it scales its own polling to what is happening.

---

## 9. Understanding the journey, not just recording it

Collecting events is the easy half; the half that matters is answering "how did
that go?" without the reader doing arithmetic.

`domain/JourneyAnalytics.kt` produces one `JourneyReport` covering time (moving
vs stopped, and the ratio between them), pace (average moving speed against
door-to-door speed — the gap is what stops cost you), breaks (count, cadence,
longest gap, longest unbroken stretch), wellbeing by kind, and money (by
category with each share, per kilometre, per hour, fuel efficiency).

On top of the numbers it produces **insights**: short factual sentences derived
from the data. Three rules govern anything added there — it must be derivable,
it must be factual, and it must be worth reading. "83% of the journey was spent
moving." "Breaks came about every 2h." "Fuel was 75% of what the journey cost."
Never praise, never scolding: a journey record, not a report card.

One report feeds the summary dashboard, the pre-closure review and both PDFs, so
the screen and the document a follower receives can never disagree.

---

## 10. Currency and units, worked out rather than assumed

A traveller in Kerala should see kilometres and ₹; one in Texas should see miles
and $. Neither should configure anything, and neither should be shown the
other's units because the app was written somewhere else. This needs no model —
a country code and a lookup table (`domain/Units.kt`, `core/RegionDetector.kt`).

Detection order, and the reason for it: the **mobile network's** country first,
because a phone bought in Kerala and carried to Dubai should price a journey in
dirhams while it is there; then the **SIM**; then the **device locale**. No
permission, no network call.

Currency comes from the JDK's own ISO tables rather than a list we would have to
maintain, with a symbol table only for the currencies our travellers actually
meet (the JDK returns "INR" rather than "₹" in many locales, which reads badly
next to a number). Imperial units are a four-entry exception — US, GB, LR, MM —
because Britain is metric everywhere except the road.

Both are overridable in Settings and default to auto.

---

## 11. Editing a journey while it runs — and never after

Plans change more often mid-journey than at the start. Someone takes the train
to Bangalore intending to stop there and decides to carry on to Hyderabad by
bus. `TripManager.appendLeg` / `updateLeg` / `removeLeg` keep that as **one**
journey, rather than forcing a second one with new credentials that the family
would have to be re-invited to. A finished stage is history and refuses to
change; the current and future ones move.

Inviting someone is on the live journey card for the same reason — remembering a
person halfway through ("send it to my sister too") is the normal case, and it
should not mean hunting back to a screen shown once at the start.

**After completion, nothing is editable by anyone.** Every mutating entry point
in `TripManager` calls `editableTrip()` first, so there is no path to a
post-completion edit — not through the UI, not through a stale screen still
holding an old view model. The timeline everyone followed and the summary they
were sent have to stay the thing that actually happened.

---

## 12. The review before closure

Ending a journey is the one irreversible action in the app: it publishes a
summary to everyone watching, it may put a document on their phones, and nothing
can be changed afterwards. So it gets a review — the same analysed dashboard
everyone else will see, a chance to add a missed expense or a closing note, and
only then a confirmation.

The closing note is inserted into the timeline *before* the completion event, so
the document that goes out is the one that was just verified.

---

## 13. Sending the timeline on WhatsApp

Opt-in, off by default: it puts a message into other people's hands, and that is
never something an app should start doing on its own.

**What is possible, stated honestly.** Android gives no app the ability to send
a WhatsApp message on someone's behalf without them seeing it — and it should
not; an app that could silently message your contacts from your account is not
one anybody should install. What Koode does instead is prepare the message
completely: build the PDF, address it to one recipient (`jid` extra), write the
covering text, and open WhatsApp on that conversation. The traveller taps send.
One tap per person, and it genuinely comes from them.

**What goes.** The timeline only. Costs are never attached, never summarised and
never mentioned — see `TimelineDelivery.buildMessage`. The money PDF exists and
is shared by hand from the summary screen, by nothing automatic.

**Who it goes to.** The circle: emergency contacts, who are both the people a
journey is shared with by default and the only followers whose phone number the
app holds. Someone who joined with a journey number alone has never given us a
number to message.
