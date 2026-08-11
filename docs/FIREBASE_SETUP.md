# Firebase Setup (enables cloud mode)

The app runs in **local mode** with no backend. Completing these steps switches it to **cloud mode** (remote viewers, live sync, SOS/arrival push). No app code changes are required — the presence of `google-services.json` flips the mode at build time.

## 1. Create a Firebase project

1. Go to the Firebase console and create a project (Analytics optional).
2. Add an **Android app** with package name **`com.trippulse.app`**.
3. Download the generated **`google-services.json`** and place it at:
   ```
   apps/android/app/google-services.json
   ```
   This repository intentionally commits this file so CI builds produce a
   cloud-enabled APK. The values inside are app identifiers, not secrets —
   actual access control is enforced by the database security rules and the
   trip-id + password capability scheme.

## 2. Enable Realtime Database

1. Build → **Realtime Database** → Create database.
2. Choose a location (e.g. the one nearest your users). The Spark (free) tier is sufficient for personal/early use (1 GB storage, 10 GB/month download, 100 concurrent connections).
3. Start in **locked mode** — the rules below replace the defaults.

> **Important — database URL.** A `google-services.json` downloaded *before*
> the Realtime Database was created has no `firebase_url` entry. The app now
> falls back to `https://<project-id>-default-rtdb.firebaseio.com` (the
> default for US-region databases). If you created the database in a
> **non-US region** (e.g. `asia-southeast1`), you must re-download
> `google-services.json` from the console **after** creating the database so
> it contains the regional `firebase_url` — otherwise cloud sync will not
> connect.

## 3. Enable Anonymous Authentication

Build → **Authentication** → Sign-in method → enable **Anonymous**.

The app signs in anonymously so every device has a stable uid. The driver's uid is stamped onto the trip as `ownerUid`; the security rules use it to keep viewers read-only.

## 4. Deploy the security rules

The rules are in `firebase/database.rules.json` and are wired by `firebase/firebase.json`.

```bash
npm install -g firebase-tools
firebase login
firebase use --add            # select your project
firebase deploy --only database
```

What the rules enforce (see the file for specifics):

- **Reads are gated on `meta/expiresAt > now`** → viewer access auto-revokes when the trip expires.
- **The access key is a capability** — it's `SHA-256(tripId:secret)` and isn't derivable without the secret the driver shares. There's no trip enumeration or public listing.
- **Writes are owner-only** (`meta/ownerUid === auth.uid`); viewers can't write.
- **Events and locations are append-only and immutable** — an existing node can't be overwritten, which makes idempotent client retries safe.

## 5. Deploy Cloud Functions (optional but recommended)

`functions/` contains two functions:

- `cleanupExpiredTrips` — every 10 minutes; **deletes the whole trip node** for any trip whose `expiresAt` has passed. A trip expires **30 minutes after the driver reaches the destination**, so the trip id is fully destroyed shortly after. (Viewer *access* is cut off at exactly the 30-minute mark by the security rules, independent of this job's schedule.)
- `onTripEvent` — pushes a minimal message to the trip topic on **trip start**, SOS, **arrival**, completion and overnight, so viewers are alerted even when the app is backgrounded.

```bash
cd functions
npm install
cd ..
firebase deploy --only functions
```

Cloud Messaging works out of the box for topic sends once the Android app has `google-services.json`; viewers subscribe to `trip_{accessKey}` automatically when they join.

## 6. Rebuild the app

```bash
cd apps/android
./gradlew :app:assembleDebug
```

On launch the home screen will now show **“Cloud sync: enabled”**, and viewers can follow a trip with its Trip ID + password.

## Notes on limits and cost

The free tier is appropriate for the driver + a handful of viewers. Anyone with valid credentials can view, but the free tier caps concurrent connections/bandwidth — for wider use, move to the pay-as-you-go plan. The architecture doesn't change; only the plan does.
