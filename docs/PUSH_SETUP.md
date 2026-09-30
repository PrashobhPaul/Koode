# Journey push notifications and verified reports — setup

Koode tells the people following a journey what matters, even when their
Koode app is closed. It uses **Firebase Cloud Messaging (FCM)**, sent from
Supabase. There is no SMS fallback and no WhatsApp Business API.

This document gives the exact places where the remaining values must be
configured. Everything else is already deployed.

---

## What is already live (Supabase project `hpijaryujcjtuhghongx`)

| Piece | Where | State |
|---|---|---|
| Journey followers (`tp_viewers`, approval by name) | database | live |
| Follower device tokens (`tp_push_tokens`) | database | live |
| Per-recipient delivery ledger (`tp_push_deliveries`) | database | live |
| Approved journey analytics (`tp_journey_analytics`) | database | live |
| Journey report metadata and storage references (`tp_journey_reports`) | database | live |
| Private report bucket `journey-reports` (PDF only, 10 MB) | Storage | live |
| Insert trigger, 1-minute retry sweeper, report clean-up | database + `pg_cron` | live |
| `tp-push` (sends FCM) | Edge Function | deployed |
| `tp-report` (signed upload/download URLs, clean-up) | Edge Function | deployed |
| `tp_push_url`, `tp_push_secret` | Vault | set |
| **Firebase project, client ids and `FCM_SERVICE_ACCOUNT`** | — | **not configured yet** |

Until the Firebase step below is done, `tp-push` answers
`{"status":"fcm-not-configured"}` and sends nothing. Nothing is lost:
followers keep getting updates from the in-app follow service while Koode
is running, exactly as before.

---

## The remaining configuration (about 5 minutes, all in the browser)

### 1. Create the Firebase project

1. Open **https://console.firebase.google.com** and choose **Create a project**.
2. Name it `Koode`. Turn **Google Analytics off**. Create it.

### 2. Register the Android app → public client ids

1. On the project page, click the **Android** icon (**Add app → Android**).
2. **Android package name:** `app.koode` (exactly). Click **Register app**.
3. Download **`google-services.json`**. Skip the remaining console steps.
4. Put these four values into **`apps/android/firebase.properties`**:

   | Key in `firebase.properties` | Where it is in `google-services.json` |
   |---|---|
   | `FIREBASE_PROJECT_ID` | `project_info.project_id` |
   | `FIREBASE_SENDER_ID` | `project_info.project_number` |
   | `FIREBASE_APP_ID` | `client[0].client_info.mobilesdk_app_id` |
   | `FIREBASE_API_KEY` | `client[0].api_key[0].current_key` |

   These are public client identifiers: they ship inside every APK and grant
   nothing on their own. The release build reads them from this file (or
   from `FIREBASE_*` environment variables). Do **not** commit
   `google-services.json` itself; it is git-ignored.

### 3. The server credential → `FCM_SERVICE_ACCOUNT`

This is the only secret. It must never be committed or pasted into a chat.

1. In the Firebase console: **⚙ Project settings → Service accounts →
   Generate new private key**. A JSON file downloads.
2. Open the **Supabase dashboard → project `hpijaryujcjtuhghongx` → Edge
   Functions → Secrets** (Manage secrets) and **Add new secret**:
   - **Name:** `FCM_SERVICE_ACCOUNT`
   - **Value:** the entire contents of that JSON file.
3. Save. `tp-push` reads it on its next call; no redeploy is needed.
4. Delete the downloaded JSON file from your computer.

*Alternative:* store the same JSON in Supabase Vault (SQL editor):
`select vault.create_secret('<the JSON>', 'fcm_service_account');`.
`tp-push` uses the Edge Function secret first, then Vault.

### 4. Release the app

Rebuild and release the APK (GitHub → Actions → **Release APK** → Run
workflow on `main`). Followers must install that build: it is what registers
their phone for push. Travellers need it to publish approved reports.

### 5. Check it works

With two phones (a traveller and an approved follower, Koode closed on the
follower's phone):

1. Start a journey. Log water. The follower's phone should show
   "Amma · Water" within seconds.
2. In the Supabase SQL editor:
   ```sql
   select event_id, recipient, status, attempts, sent_at
   from tp_push_deliveries order by last_attempt_at desc limit 10;
   ```
   `SENT` means FCM accepted it. `FAILED` rows are retried every minute (up
   to 6 attempts). `DEAD` means the device token was removed.
3. **Edge Functions → tp-push → Logs** show any FCM error text.

---

## What followers are told, and when

- **Meaningful journey events** (start, breaks, food/water, tolls, halts,
  stages, wellbeing alerts, the periodic update): pushed as they happen.
- **Destination, mode and plan changes, big ETA shifts**: pushed as
  important.
- **SOS and incidents**: always pushed, whatever state the journey is in.
- **Journey completed** (`TRIP_COMPLETED`): pushed **only after the traveller
  approves the journey analytics**. Once the traveller closes a journey,
  nothing except SOS/incidents is pushed until they approve. Approval then
  releases anything that was held.
- **Verified report available** (`JOURNEY_REPORT_AVAILABLE`): pushed when the
  approved report has been uploaded. It replaces the completion notification
  instead of ringing again. Followers open it through a 10-minute signed link.

The server enforces all of this (`tp_push_eligible`), not only the app.

## What followers are never sent

- **Expenses of any kind.** The approved analytics are built without expense
  input, and the server refuses any analytics document with a money-shaped key
  or a ₹ value (`tp_is_financial`). The uploaded report is the journey
  timeline. It is built with no expense input and no FASTag balance, and the
  table only accepts `kind = 'JOURNEY_TIMELINE'`. The expense PDF is saved on
  the traveller's phone only.
- **The passcode-derived access key**, if they follow by journey number: they
  are addressed by journey number alone, so denying them later is final.

## Idempotency

A notification's identity is **journey / event / recipient / channel**
(`tp_push_deliveries` primary key). A retry goes only to recipients whose
send failed. A recipient who has it is never sent it again, and a replayed or
duplicated trigger call cannot double-send. On the phone, a second
de-duplication ledger makes sure each event shows once, even if both the
push and the in-app follow service deliver it.

## Never commit

Service-account JSON, private keys, `google-services.json`,
`FCM_SERVICE_ACCOUNT`, the Supabase service-role key, `.env` files. The
`.gitignore` covers these patterns. The four public ids in
`firebase.properties` are the only Firebase values that belong in the repo.

## Rebuilding the backend from scratch (a new Supabase project)

1. Run `supabase/schema.sql` in the SQL editor. It is idempotent.
2. In the SQL editor, register the push endpoint once:
   `select vault.create_secret('https://<ref>.supabase.co/functions/v1/tp-push', 'tp_push_url');`
   (`tp_push_secret` is created by the schema.)
3. Deploy both functions **without JWT verification**. Each one does its own
   authorisation (shared secret, owner token or follower approval, checked in
   Postgres):
   `supabase functions deploy tp-push --no-verify-jwt`
   `supabase functions deploy tp-report --no-verify-jwt`
4. Then steps 1–4 above.

## Tests

- `supabase/tests/run.sh`: loads `schema.sql` twice (idempotency) into a
  throwaway local PostgreSQL with stand-ins for Vault, Storage, pg_net and
  pg_cron. It then checks the approval gate, per-recipient idempotency,
  financial refusal, the report flow, privileges and clean-up.
- `ApprovedAnalyticsTest` (Android unit tests): the approved analytics carry
  no money and match the server's rule, and the report notification replaces
  the completion notice.
