# Google Maps & Routes Setup (enables the live map + route/ETA)

Without a key, map panels render a graceful “map unavailable” placeholder and the app remains fully functional (tracking, checkpoints, ETA via the fallback estimator, replay, summary). Adding a key enables the interactive map, the route corridor, route-distance-based remaining time, and route-deviation detection.

## 1. Create an API key

1. In the Google Cloud console, create/select a project (it can be the same one backing Firebase or a separate one).
2. Enable these APIs:
   - **Maps SDK for Android** (renders the map)
   - **Routes API** (route polyline + travel-time estimate; powers deviation detection and distance-accurate ETA)
   - *(Optional)* **Geocoding API** if you want more robust place-name → coordinate lookup; the app also falls back to the on-device geocoder and map long-press.
3. Create an **API key** under Credentials.

## 2. Restrict the key (recommended)

- **Application restriction:** Android apps → add package `com.trippulse.app` and your debug/release signing SHA-1 fingerprints.
  ```bash
  # debug keystore SHA-1
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
  ```
- **API restriction:** limit the key to the APIs enabled above.

## 3. Provide the key to the build

Put it in `apps/android/local.properties` (git-ignored):

```properties
MAPS_API_KEY=YOUR_ANDROID_MAPS_KEY
```

Alternatively, export `MAPS_API_KEY` as an environment variable (used by CI via a repository secret). The build injects it as the manifest `com.google.android.geo.API_KEY` placeholder and exposes `BuildConfig.MAPS_KEY_SET` so the UI can switch between the real map and the placeholder.

## 4. Rebuild

```bash
cd apps/android
./gradlew :app:assembleDebug
```

## What uses the key

- **Map panels** (driver, viewer, replay, and the destination picker) use the Maps SDK.
- **Routing** uses the Routes API (`computeRoutes`) to fetch the route polyline and travel-time estimate. When the key/route is absent, a haversine × road-factor fallback drives distance and ETA, and route-deviation detection is disabled (there's no corridor to deviate from).
