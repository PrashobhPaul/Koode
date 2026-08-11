# Maps & Routing (free — no API key, no billing)

TripPulse no longer uses Google Maps or any paid/metered Google API. The map
and routing stack is completely free:

| Capability | Provider | Cost |
|---|---|---|
| Map rendering | **osmdroid** + OpenStreetMap tiles | Free, no key |
| Route polyline + travel time | **OSRM** public router (`router.project-osrm.org`) | Free, no key |
| Place-name → coordinates | On-device Android `Geocoder` (+ map long-press pin) | Free |

There is **nothing to set up**. No API key, no Google Cloud project, no billing
account. Build the app and the map works.

## How it behaves

- **Map panels** (create-trip picker, driver dashboard, viewer, replay) render
  OpenStreetMap tiles via osmdroid. Long-press drops the destination pin, so a
  trip can go from **any start point to any end point** — type a place name,
  use "Current location", or drop a pin anywhere.
- **Routing/ETA** asks the public OSRM server for the route polyline and travel
  time. OSRM's demo server is community-run with no SLA; if it is unreachable
  (or the phone is offline) the app silently falls back to the deterministic
  estimator (haversine × road factor at a configurable average speed), so the
  ETA always resolves. Route-deviation detection needs a real polyline and is
  disabled only while running on the fallback.
- **Tile cache** lives in app-private storage (`Android/data/…/files/osm_tiles`),
  so no storage permissions are required, and recently viewed map areas keep
  working offline.

## OSM tile usage policy

OpenStreetMap's tile servers are donation-funded. The app complies with the
[tile usage policy](https://operations.osmfoundation.org/policies/tiles/) by
sending the app's package name as the user agent and caching tiles on device.
For a large-scale public release, switch to a commercial OSM tile host or
self-host tiles; for personal/family use the public servers are fine.

## Self-hosting (optional)

If you ever want your own router, OSRM is open source and can be self-hosted
(`osrm-backend` + an OSM extract). Point `OsrmRoutingProvider(baseUrl = …)` at
your instance in `di/AppGraph.kt` — nothing else changes.
