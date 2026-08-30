# Cloud backend: Supabase (open source, free, one-time 5-minute setup)

TripPulse's cloud side runs on [Supabase](https://supabase.com) — the
open-source Postgres platform. The free tier is more than enough for a driver
plus family/friends viewers, and **the entire backend is one SQL file** —
there are no Edge Functions, no auth providers to enable, no push services,
no CLI tools and no billing plan.

## The one-time setup (~5 minutes, done once, never again)

1. **Create a project**: sign up at [supabase.com](https://supabase.com) (free),
   create a project — any name, any region, any database password (you'll never
   need it again).
2. **Run the schema**: open **SQL Editor** in the project, paste the entire
   contents of [`supabase/schema.sql`](../supabase/schema.sql), press **Run**.
   That's the whole backend: tables, security, expiry, cleanup.
3. **Connect the app**: open **Settings → API**, copy two values into
   [`apps/android/supabase.properties`](../apps/android/supabase.properties):
   - *Project URL* → `SUPABASE_URL=`
   - *anon public* key → `SUPABASE_ANON_KEY=`

   Commit and push (you can edit the file straight in the GitHub web UI).
   CI rebuilds and republishes the downloadable APK automatically.

Done. There is no ongoing maintenance:

- A scheduled GitHub workflow (`supabase-maintenance.yml`) pings the project
  every 6 hours, which both **prevents the free-tier auto-pause** and
  **destroys expired trips**.
- The anon key is *designed* to be public (it's compiled into the APK). It can
  only call the RPC functions defined in `schema.sql`; direct table access is
  revoked and row-level security is enabled with no policies.

## Updating the backend

`supabase/schema.sql` is **idempotent** — whenever it changes (e.g. the v2
viewer-approval additions), just paste the whole file into the SQL editor and
Run again. Existing data is preserved; only new tables/columns/functions are
added.

## Security model — who can do what

Everything is enforced *inside Postgres*, not in the app:

| Actor | Holds | Can |
|---|---|---|
| **Driver (trip creator)** | `owner_token` — random secret generated on their phone at trip creation, never displayed or shared | The **only** party able to write: update meta/live state, append events and locations, change expiry |
| **Viewer** | Trip ID + password (→ `access_key` = SHA-256 hash) | Read-only: live state, timeline, ETA — and only while the trip is alive; plus register their name so the driver knows who's watching |
| **Everyone else** | The public anon key | Nothing: no listing, no enumeration, no reads without a valid `access_key`, no writes without the `owner_token` |

## Trip lifecycle

- A trip is created with a default 36-hour expiry.
- The moment the driver **reaches the destination**, the app stamps
  `expires_at = completion + 1 hour` — set when the traveller ends the journey, never at mere arrival.
- At expiry the trip is instantly unreadable (every read RPC checks
  `expires_at > now()`), and the row — with all its state, events, locations
  and viewer names — is deleted by whichever comes first: the first request
  that touches it, the in-database `pg_cron` job (every 10 min, if available),
  or the GitHub maintenance workflow.

## Viewer alerts (no push service)

Phones that join a trip run a small foreground "Following trip" service that
polls the backend every 30 seconds and raises high-priority notifications for
**trip started**, **SOS**, **arrival at destination**, **completion** and
**overnight stops** — even when the app is in the background. No FCM, no
Google services, nothing to configure.

## Costs & limits

Supabase free tier (as of 2025): 500 MB database, 5 GB egress/month, pauses
after ~1 week of inactivity (which the maintenance workflow prevents). A trip
uses a few MB at most and is deleted an hour after the traveller ends the journey, so a personal /
family deployment stays far inside the free limits indefinitely.
