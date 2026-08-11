# TripPulse --- Production Release Runbook

## 1. Firebase

Create separate projects/environments:

``` text
trippulse-dev
trippulse-test
trippulse-prod
```

Never use production credentials in local development.

## 2. Android signing

-   secure keystore
-   Play App Signing
-   release build configuration
-   no debug logging in production

## 3. Environment

Configure:

-   Firebase IDs
-   map/routing provider key
-   notification configuration
-   Remote Config defaults
-   support URL
-   privacy policy URL

Use restricted API keys where provider supports it.

## 4. Monitoring

Enable:

-   Crashlytics
-   Firebase performance where useful
-   backend/function logs
-   RTDB usage monitoring
-   alerting for error spikes

## 5. Privacy

Before release verify:

-   privacy policy published
-   in-app disclosure
-   data safety form
-   location declaration if required
-   sensitive permission declarations
-   deletion/retention process
-   support contact

## 6. Play Store assets

Prepare:

-   app icon
-   feature graphic
-   screenshots
-   short description
-   full description
-   privacy policy
-   support contact
-   demo/reviewer instructions if required

## 7. Release tracks

Use:

``` text
Internal
 ↓
Closed
 ↓
Open
 ↓
Production
```

Do not jump directly to production.

## 8. Production smoke test

After release:

1.  install from Play
2.  create test trip
3.  join from second device
4.  start tracking
5.  lock driver phone
6.  verify live location
7.  disable network
8.  record break
9.  restore network
10. verify event history
11. end trip
12. verify expiration

## 9. Kerala trip checklist

Before departure:

-   100%/adequate phone battery
-   charging available
-   permissions granted
-   trip created
-   credentials shared
-   Mom joined
-   wife joined
-   friend joined
-   map works
-   live state confirmed
-   ETA confirmed
-   SOS tested previously
-   network/online state visible

Do not perform a first-ever permission or configuration change on the
long trip.

## 10. During trip

Do not debug or modify production configuration while driving.

If a failure occurs:

-   driver safety first
-   app continues locally
-   note issue only when safely stopped
-   do not repeatedly restart the app unless necessary

## 11. Post-trip

Capture:

-   total distance
-   event sync delays
-   offline duration
-   battery drain
-   GPS anomalies
-   ETA accuracy
-   crashes
-   viewer feedback

Use the results for version 1.1.

## 12. Rollback

Maintain a tested previous production build.

If a critical release issue occurs:

-   stop rollout
-   communicate issue
-   roll back where supported
-   investigate before resuming
