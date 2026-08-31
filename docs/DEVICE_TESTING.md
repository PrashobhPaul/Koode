# Testing Koode on a real phone

CI proves what a machine can: the code compiles, the pure logic is correct,
the Room migration runs against real SQLite, and the device reads behave on an
Android emulator. None of that is a phone in a pocket for a day.

Four things can only be proven on real Motorola and Samsung hardware, because
they are behaviours the OEM adds on top of Android — and they are exactly the
behaviours a safety app lives or dies by:

1. whether the app keeps tracking when the screen is off for hours,
2. whether an update installs over the old app without losing the journey,
3. whether the phone powering off is actually captured,
4. whether tracking comes back after a reboot.

This checklist is written to be run start to finish on one device before a
release reaches the family. Do it on at least one Samsung and one Motorola,
because their power management is where they differ most: Samsung is
aggressive (it will "put the app to sleep" unless told not to), Motorola is
close to stock Android and usually just works — which is exactly why testing
only the Motorola would give false confidence.

---

## 0. Before you start

- Install the **release** build (the signed `Koode.apk` from the GitHub
  release), not a debug build from Android Studio. Doze and background limits
  behave differently for a debuggable build, so a debug build can pass while
  the real one fails.
- Note the phone: **make, model, Android version, One UI / MyUX version.**
- Have a second phone or the browser viewer open as the follower, so you can
  see what the family would see.

## 1. Permissions granted the way a real user grants them

- [ ] Location permission prompt appears; choose **While using the app** first
      on purpose (the common mistake), confirm Koode explains it needs
      **Allow all the time** and the follow-up path works.
- [ ] Grant **Allow all the time** (background location). On Android 11+ this
      is a second, separate trip to Settings — confirm Koode guides you there.
- [ ] Notifications permission (Android 13+) granted.
- [ ] The tracking notification, once a journey starts, shows only **"Koode"** —
      no route, no destination, no journey number — and does **not** appear on
      the lock screen. (Requirement: a thief glancing at the phone learns
      nothing.)

## 2. The overnight test — the one that matters most

This is the bug that started all of this: tracking that stopped in the night
and never came back.

- [ ] Start a journey. Confirm the follower sees live updates.
- [ ] Lock the phone, put it down, and **leave it untouched for at least 6
      hours** (overnight is better). Do not open the app.
- [ ] Next morning, without touching the phone first, check the **follower**:
      is the last update recent, or did it stop hours ago?
- [ ] If it stopped: note the exact gap, the battery level, and whether the
      follower was shown "waiting"/"went quiet" (correct) or nothing (bug).

**Samsung specifically:** repeat this test **with and without** exempting Koode
from battery optimisation:
- Settings → Battery → Background usage limits → **Never sleeping apps** → add
  Koode. Also Settings → Apps → Koode → Battery → **Unrestricted**.
- [ ] Without the exemption, does the hourly breadcrumb still land? (It may
      not — that is the point of testing.) Koode should tell the traveller if
      it detects it is being restricted.
- [ ] With the exemption, does the overnight test pass cleanly?

**Motorola:** usually passes without exemptions, but confirm — Moto's "Optimised"
battery setting can still doze aggressively on some carriers.

## 3. The update test — a journey must survive an upgrade

Requirement: updating the app, even mid-journey, must not lose the journey.

- [ ] Install an **older** signed release (e.g. the previous tag's `Koode.apk`).
- [ ] Start a journey and log a few things (a break, an expense).
- [ ] **Without ending it**, download and install the **newer** `Koode.apk`
      over the top.
- [ ] Confirm Android offers an **update** (not "app not installed" — that
      means the signing key changed; stop and fix signing before release).
- [ ] After updating, open Koode: is the journey still running, with its
      timeline and expenses intact?
- [ ] Does the follower's view carry on uninterrupted?

## 4. The going-dark tests

- [ ] **Ordinary power-off.** Mid-journey, hold power and switch the phone off
      properly. Wait a few minutes, then check the follower and the safety
      report: does it say the phone was **switched off**, with the battery
      level and the last known position captured? (This relies on Android
      broadcasting the shutdown; some phones/quick-power-off paths do not send
      it — note which behaviour you see.)
- [ ] **Battery death.** Let the phone run down to 0 mid-journey (or simulate
      with a low battery). Does the follower eventually see **"ran out of
      battery"** rather than an alarm?
- [ ] **The distinction.** Confirm a switch-off at high battery reads as
      *concerning* to the follower, and a battery death does not. This is the
      core of the feature.
- [ ] **Reboot recovery.** Mid-journey, restart the phone. After it boots
      (without opening Koode), does tracking resume on its own within a few
      minutes? Does the follower see it come back? (Needs background location
      granted; if not, confirm the traveller gets a tap-to-resume notice.)
- [ ] **SIM removed.** Mid-journey, power off, remove the SIM, power on. Does
      the follower see **"SIM changed or removed"**, and does Koode keep
      reporting over Wi-Fi?

## 5. The safety report

- [ ] From the follower (app and browser), generate the safety report while
      the journey is live.
- [ ] Confirm it names the actual phone (e.g. "Samsung SM-A546E · Android 14",
      "Motorola moto g84 · Android 13").
- [ ] Confirm it shows a **public IP** and a last known position.
- [ ] Confirm **IMEI** and **hardware MAC** print as "not available" with the
      reason — never blank, never a fake value.
- [ ] Reboot onto a different network, regenerate: has the **public IP
      changed**? (This is the thief-joined-their-own-network lead.)

## 6. The gesture / layout pass (quick)

- [ ] Rotate; use on a small phone and, if you have one, a tablet — the layout
      adapts, nothing is clipped.
- [ ] The map's pulsing dot, start/end markers and ▶ playback work by touch.
- [ ] Splash screen shows the Koode mark, and the mark matches the launcher
      icon and the PDF.

---

## Recording the result

For each phone, note against every box: **pass / fail / not-applicable**, plus
the make, model and Android version. A release is ready for the family when the
overnight test (2) and the update test (3) pass on both a Samsung and a
Motorola. The going-dark tests (4) are best-effort by nature — some depend on
whether the specific phone broadcasts a shutdown at all — so record what each
device actually does rather than expecting a uniform pass.
