# Koode: working rules

## The web tracker must never go down

`web/` is the **journey tracker**: the page at https://prashobhpaul.github.io/Koode/
that the app's invite links open. It is how people who never install the app,
often elderly parents, follow a journey. It is **not** marketing: the product
page is the README. Never publish anything else to the Pages root, and never
turn the tracker into a landing page.

Every change, to any part of the repo, must leave the tracker working:

- `.github/workflows/pages.yml` publishes `web/` on every push to `main`
  (whatever changed), after every transit-data rebuild, and every six hours.
  It checks the page first (`tools/web/check.mjs --config`, then the browser
  smoke test `tools/web/smoke.mjs`); a failed check publishes nothing, so
  the last good tracker stays live. After publishing it checks the live URL.
- `.github/workflows/web-check.yml` runs the same checks on every pull
  request. Do not merge a PR with this check red, and never skip, disable or
  weaken these checks, the path-free `push` trigger, the `workflow_run` on
  "Transit data" or the schedule.
- Keep `<title>` in `web/index.html` saying "follow a journey", keep it
  loading `app.js`, `map3d.js`, `config.js` and `styles.css`, and keep the
  link format the app writes (`Links.WEB_VIEWER` + `#CODE-PIN`) working.
- `web/config.js` is generated from `apps/android/supabase.properties` by
  `tools/web/write-config.sh`; change the backend there, never only in one place.
- After merging anything, confirm the newest "Publish web viewer" run is green.
  If it is red, fixing it comes before any other work.

Run locally before pushing a change to `web/`:

    node tools/web/check.mjs
