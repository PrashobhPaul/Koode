# Maps & Routing (free — no API key, no billing)

TripPulse no longer uses Google Maps or any paid/metered Google API. The map
and routing stack is completely free:

| Capability | Provider | Cost |
|---|---|---|
| Map rendering | **MapLibre Native** (Android) / **MapLibre GL JS** (web viewer) + **OpenFreeMap** vector tiles (OpenStreetMap data) | Free, no key |
| Route polyline + travel time | **OSRM** public router (`router.project-osrm.org`) | Free, no key |
| Place-name → coordinates | On-device Android `Geocoder` (+ map long-press pin) | Free |

There is **nothing to set up**. No API key, no Google Cloud project, no billing
account. Build the app and the map works.

## How it behaves

- **Map panels** (create-trip picker, driver dashboard, viewer, replay) render
  OpenFreeMap vector styles (`liberty` for light, `dark` for dark theme) via
  MapLibre. Because the tiles are vectors, the camera can tilt and the vehicle
  is drawn as a low-poly 3D model (extruded solids — no model files). A trip
  can go from **any start point to any end point** — type a place name, use
  "Current location", drop a pin on the map, or pick the place in Google Maps
  and share it to Koode (plain Android share sheet; no Google API or key).
- **Routing/ETA** asks the public OSRM server for the route polyline and travel
  time. OSRM's demo server is community-run with no SLA; if it is unreachable
  (or the phone is offline) the app silently falls back to the deterministic
  estimator (haversine × road factor at a configurable average speed), so the
  ETA always resolves. Route-deviation detection needs a real polyline and is
  disabled only while running on the fallback.
- **Tile cache** is MapLibre's own cache in app-private storage, so no
  storage permissions are required.

## Tile hosting

Tiles and styles come from [OpenFreeMap](https://openfreemap.org), which is
free, keyless and needs no registration. The style URLs live in one place —
`MapStyles` in `ui/map/MapSupport.kt` (Android) and `STYLE_URL` in
`web/map3d.js` (web viewer) — so switching to a self-hosted OpenFreeMap
instance or another MapLibre-compatible style is a one-line change.

## Self-hosting (optional)

If you ever want your own router, OSRM is open source and can be self-hosted
(`osrm-backend` + an OSM extract). Point `OsrmRoutingProvider(baseUrl = …)` at
your instance in `di/AppGraph.kt` — nothing else changes.
